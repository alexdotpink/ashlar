package pink.alex.ashlar.di.ksp

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.LIST
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.TypeName
import java.security.MessageDigest

internal class DiCodeWriter {
    fun factory(model: FactoryModel): FileSpec {
        val target = model.className()
        val factoryName = "${model.typeNames.joinToString("_")}__AshlarFactory"
        val typeBuilder = TypeSpec.classBuilder(factoryName)
            .addModifiers(KModifier.INTERNAL)
            .addSuperinterface(DEPENDENCY_FACTORY.parameterizedBy(target))
        model.parameters.forEach { parameter ->
            typeBuilder.addProperty(parameter.keyProperty())
        }
        val type = typeBuilder
            .addProperty(
                PropertySpec.builder("type", KCLASS.parameterizedBy(target))
                    .addModifiers(KModifier.OVERRIDE)
                    .initializer("%T::class", target)
                    .build(),
            )
            .addProperty(
                PropertySpec.builder("lifetime", DEPENDENCY_LIFETIME)
                    .addModifiers(KModifier.OVERRIDE)
                    .initializer("%T.%L", DEPENDENCY_LIFETIME, model.lifetime.name)
                    .build(),
            )
            .addProperty(
                PropertySpec.builder(
                    "dependencies",
                    LIST.parameterizedBy(DEPENDENCY_KEY.parameterizedBy(STAR)),
                )
                    .addModifiers(KModifier.OVERRIDE)
                    .initializer(dependencies(model.parameters))
                    .build(),
            )
            .addFunction(create(model, target))
            .build()
        return FileSpec.builder(model.packageName, factoryName).addType(type).build()
    }

    fun contributionModule(
        roots: List<RootComponentModel>,
        contributions: List<ContributionModel>,
    ): Pair<String, FileSpec> {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(
                (roots.map { root -> root.qualifiedName } +
                    contributions.map { contribution -> contribution.qualifiedName })
                    .joinToString("|")
                    .toByteArray(),
            )
            .take(6)
            .joinToString("") { byte -> "%02x".format(byte) }
        val className = "AshlarDependencyContributions_$digest"
        val type = TypeSpec.classBuilder(className)
            .addModifiers(KModifier.PUBLIC)
            .addSuperinterface(DEPENDENCY_CONTRIBUTION_MODULE)
            .addProperty(
                PropertySpec.builder(
                    "rootComponents",
                    LIST.parameterizedBy(ROOT_COMPONENT_CONTRIBUTION),
                )
                    .addModifiers(KModifier.OVERRIDE)
                    .initializer(rootComponents(roots))
                    .build(),
            )
            .addProperty(
                PropertySpec.builder(
                    "contributions",
                    LIST.parameterizedBy(KCLASS.parameterizedBy(STAR)),
                )
                    .addModifiers(KModifier.OVERRIDE)
                    .initializer(contributionTypes(contributions))
                    .build(),
            )
            .build()
        return className to FileSpec.builder(GENERATED_PACKAGE, className).addType(type).build()
    }

    private fun dependencies(parameters: List<FactoryParameterModel>): CodeBlock =
        list(parameters) { parameter -> CodeBlock.of("%N", parameter.keyName()) }

    private fun create(
        model: FactoryModel,
        target: ClassName,
    ): FunSpec {
        val body = CodeBlock.builder().add("return %T(\n", target).indent()
        model.parameters.forEach { parameter ->
            body.add("%N = resolver.get(%N),\n", parameter.name, parameter.keyName())
        }
        body.unindent().add(")\n")
        return FunSpec.builder("create")
            .addModifiers(KModifier.OVERRIDE)
            .addParameter("resolver", DEPENDENCY_RESOLVER)
            .returns(target)
            .addCode(body.build())
            .build()
    }

    private fun rootComponents(roots: List<RootComponentModel>): CodeBlock = list(roots) { root ->
        CodeBlock.of(
            "%T(type = %T::class, bindings = %L, name = %L, phase = %L)",
            ROOT_COMPONENT_CONTRIBUTION,
            root.className(),
            list(root.bindings) { binding -> CodeBlock.of("%T::class", binding.className()) },
            root.name?.let { CodeBlock.of("%S", it) } ?: CodeBlock.of("null"),
            root.phase,
        )
    }

    private fun contributionTypes(contributions: List<ContributionModel>): CodeBlock =
        list(contributions) { contribution ->
            CodeBlock.of("%T::class", ClassName(contribution.packageName, contribution.typeNames))
        }

    private fun <T> list(values: List<T>, render: (T) -> CodeBlock): CodeBlock {
        if (values.isEmpty()) return CodeBlock.of("emptyList()")
        val result = CodeBlock.builder().add("listOf(\n").indent()
        values.forEach { value -> result.add("%L,\n", render(value)) }
        return result.unindent().add(")").build()
    }

    private fun FactoryModel.className(): ClassName = ClassName(packageName, typeNames)

    private fun FactoryParameterModel.keyProperty(): PropertySpec =
        PropertySpec.builder(
            keyName(),
            DEPENDENCY_KEY.parameterizedBy(type.typeName()),
        )
            .addModifiers(KModifier.PRIVATE)
            .initializer(CodeBlock.builder().apply {
                add("%T(\n", DEPENDENCY_KEY).indent()
                add("dependencyType = %L,\n", type.dependencyType())
                qualifier?.let { qualifier -> add("qualifier = %T::class,\n", qualifier.className()) }
                unindent().add(")")
            }.build())
            .build()

    private fun FactoryParameterModel.keyName(): String = "${name}Key"

    private fun DependencyTypeModel.dependencyType(): CodeBlock =
        if (arguments.isEmpty()) {
            CodeBlock.of("%T<%T>(%T::class)", DEPENDENCY_TYPE, typeName(), className())
        } else {
            CodeBlock.builder()
                .add("%T<%T>(\n", DEPENDENCY_TYPE, typeName())
                .indent()
                .add("rawType = %T::class,\n", className())
                .add("arguments = %L,\n", list(arguments) { argument -> argument.dependencyType() })
                .unindent()
                .add(")")
                .build()
        }

    private fun DependencyTypeModel.typeName(): TypeName =
        if (arguments.isEmpty()) className() else className().parameterizedBy(arguments.map { it.typeName() })

    private fun DependencyTypeModel.className(): ClassName = ClassName(packageName, typeNames)

    private fun RootComponentModel.className(): ClassName = ClassName(packageName, typeNames)

    private fun BindingModel.className(): ClassName = ClassName(packageName, typeNames)

    companion object {
        const val GENERATED_PACKAGE = "pink.alex.ashlar.generated"
        const val CONTRIBUTION_MODULE = "pink.alex.ashlar.di.DependencyContributionModule"
        private val DEPENDENCY_FACTORY = ClassName("pink.alex.ashlar.di", "DependencyFactory")
        private val DEPENDENCY_RESOLVER = ClassName("pink.alex.ashlar.di", "DependencyResolver")
        private val DEPENDENCY_LIFETIME = ClassName("pink.alex.ashlar.di", "DependencyLifetime")
        private val DEPENDENCY_KEY = ClassName("pink.alex.ashlar.di", "DependencyKey")
        private val DEPENDENCY_TYPE = ClassName("pink.alex.ashlar.di", "DependencyType")
        private val DEPENDENCY_CONTRIBUTION_MODULE =
            ClassName("pink.alex.ashlar.di", "DependencyContributionModule")
        private val ROOT_COMPONENT_CONTRIBUTION =
            ClassName("pink.alex.ashlar.di", "RootComponentContribution")
        private val KCLASS = ClassName("kotlin.reflect", "KClass")
        private val STAR = com.squareup.kotlinpoet.STAR
    }
}
