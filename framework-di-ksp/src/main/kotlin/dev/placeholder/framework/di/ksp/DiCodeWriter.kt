package dev.placeholder.framework.di.ksp

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.LIST
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeSpec
import java.security.MessageDigest

internal class DiCodeWriter {
    fun factory(model: FactoryModel): FileSpec {
        val target = model.className()
        val factoryName = "${model.typeNames.joinToString("_")}__FrameworkFactory"
        val type = TypeSpec.classBuilder(factoryName)
            .addModifiers(KModifier.INTERNAL)
            .addSuperinterface(DEPENDENCY_FACTORY.parameterizedBy(target))
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
        val className = "FrameworkDependencyContributions_$digest"
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

    private fun dependencies(parameters: List<FactoryParameterModel>): CodeBlock = list(parameters) { parameter ->
        parameter.qualifier?.let { qualifier ->
            CodeBlock.of("%T(%T::class, %T::class)", DEPENDENCY_KEY, parameter.className(), qualifier.className())
        } ?: CodeBlock.of("%T(%T::class)", DEPENDENCY_KEY, parameter.className())
    }

    private fun create(
        model: FactoryModel,
        target: ClassName,
    ): FunSpec {
        val body = CodeBlock.builder().add("return %T(\n", target).indent()
        model.parameters.forEach { parameter ->
            parameter.qualifier?.let { qualifier ->
                body.add(
                    "%N = resolver.get(%T::class, %T::class),\n",
                    parameter.name,
                    parameter.className(),
                    qualifier.className(),
                )
            } ?: body.add("%N = resolver.get(%T::class),\n", parameter.name, parameter.className())
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
        val result = CodeBlock.builder().add("listOf(\n")
        values.forEach { value -> result.add("%L,\n", render(value)) }
        return result.add(")").build()
    }

    private fun FactoryModel.className(): ClassName = ClassName(packageName, typeNames)

    private fun FactoryParameterModel.className(): ClassName = ClassName(packageName, typeNames)

    private fun RootComponentModel.className(): ClassName = ClassName(packageName, typeNames)

    private fun BindingModel.className(): ClassName = ClassName(packageName, typeNames)

    companion object {
        const val GENERATED_PACKAGE = "dev.placeholder.framework.generated"
        const val CONTRIBUTION_MODULE = "dev.placeholder.framework.di.DependencyContributionModule"
        private val DEPENDENCY_FACTORY = ClassName("dev.placeholder.framework.di", "DependencyFactory")
        private val DEPENDENCY_RESOLVER = ClassName("dev.placeholder.framework.di", "DependencyResolver")
        private val DEPENDENCY_LIFETIME = ClassName("dev.placeholder.framework.di", "DependencyLifetime")
        private val DEPENDENCY_KEY = ClassName("dev.placeholder.framework.di", "DependencyKey")
        private val DEPENDENCY_CONTRIBUTION_MODULE =
            ClassName("dev.placeholder.framework.di", "DependencyContributionModule")
        private val ROOT_COMPONENT_CONTRIBUTION =
            ClassName("dev.placeholder.framework.di", "RootComponentContribution")
        private val KCLASS = ClassName("kotlin.reflect", "KClass")
        private val STAR = com.squareup.kotlinpoet.STAR
    }
}
