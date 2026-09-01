package pink.alex.ashlar.di.ksp

import com.google.devtools.ksp.getConstructors
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSTypeParameter
import com.google.devtools.ksp.symbol.Nullability
import com.google.devtools.ksp.symbol.Variance

internal class DiModelReader {
    fun factory(declaration: KSClassDeclaration): FactoryModel {
        val constructor = injectableConstructor(declaration)
        return FactoryModel(
            packageName = declaration.packageName.asString(),
            typeNames = declaration.typeNames(),
            qualifiedName = requireNotNull(declaration.qualifiedName?.asString()),
            lifetime = declaration.lifetime(),
            typeParameters = declaration.typeParameters.map { parameter -> parameter.name.asString() },
            parameters = constructor.parameters.map { parameter ->
                val parameterName = requireNotNull(parameter.name?.asString())
                val dependencyType = dependencyType(
                    type = parameter.type.resolve(),
                    subject = "Dependency parameter '${declaration.qualifiedName?.asString()}.$parameterName'",
                    nested = false,
                )
                val qualifier = parameter.annotations.mapNotNull { annotation ->
                    val declaration = annotation.annotationType.resolve().declaration as? KSClassDeclaration
                        ?: return@mapNotNull null
                    if (declaration.annotationOrNull(DEPENDENCY_QUALIFIER) == null) return@mapNotNull null
                    BindingModel(declaration.packageName.asString(), declaration.typeNames())
                }.toList().also { qualifiers ->
                    require(qualifiers.size <= 1) {
                        "Dependency parameter '${parameter.name?.asString()}' has multiple qualifiers"
                    }
                }.singleOrNull()
                FactoryParameterModel(
                    name = parameterName,
                    type = dependencyType,
                    qualifier = qualifier,
                )
            },
        )
    }

    fun rootComponent(declaration: KSClassDeclaration): RootComponentModel {
        val component = declaration.annotation(FRAMEWORK_COMPONENT)
        val configuredName = component.arguments
            .single { argument -> argument.name?.asString() == "name" }
            .value as String
        val phase = component.arguments
            .single { argument -> argument.name?.asString() == "phase" }
            .value
            .let { value ->
                when (value) {
                    is KSClassDeclaration -> value.simpleName.asString()
                    is KSType -> value.declaration.simpleName.asString()
                    else -> value.toString().substringAfterLast('.')
                }
            }
            .let { name -> if (name == "FRAMEWORK") 1 else 0 }
        val bindings = declaration.annotations
            .filter { annotation -> annotation.qualifiedName() == BINDS }
            .flatMap { annotation ->
                @Suppress("UNCHECKED_CAST")
                (annotation.arguments.single().value as List<KSType>).asSequence()
            }
            .map { type ->
                val binding = type.declaration as KSClassDeclaration
                BindingModel(binding.packageName.asString(), binding.typeNames())
            }
            .toList()
        return RootComponentModel(
            packageName = declaration.packageName.asString(),
            typeNames = declaration.typeNames(),
            qualifiedName = requireNotNull(declaration.qualifiedName?.asString()),
            name = configuredName.ifBlank { null },
            phase = phase,
            bindings = bindings,
        )
    }

    fun contribution(declaration: KSClassDeclaration): ContributionModel =
        ContributionModel(
            packageName = declaration.packageName.asString(),
            typeNames = declaration.typeNames(),
            qualifiedName = requireNotNull(declaration.qualifiedName?.asString()),
        )

    fun injectableClass(symbol: KSAnnotated): KSClassDeclaration? =
        when (symbol) {
            is KSClassDeclaration -> symbol
            is KSFunctionDeclaration -> symbol.parentDeclaration as? KSClassDeclaration
            else -> null
        }

    private fun injectableConstructor(declaration: KSClassDeclaration): KSFunctionDeclaration {
        val constructors = declaration.getConstructors().toList()
        return constructors.singleOrNull { constructor -> constructor.annotationOrNull(INJECT) != null }
            ?: declaration.primaryConstructor
            ?: error("${declaration.qualifiedName?.asString()} has no primary constructor")
    }

    private fun KSClassDeclaration.lifetime(): LifetimeModel {
        val declared = buildList {
            if (annotationOrNull(PLUGIN_SCOPED) != null) add(LifetimeModel.PLUGIN)
            if (annotationOrNull(INVOCATION_SCOPED) != null) add(LifetimeModel.INVOCATION)
            if (annotationOrNull(FACTORY) != null) add(LifetimeModel.FACTORY)
        }
        require(declared.size <= 1) { "A dependency may declare only one lifetime" }
        return declared.singleOrNull() ?: LifetimeModel.PLUGIN
    }

    internal fun dependencyType(
        type: KSType,
        subject: String,
    ): DependencyTypeModel = dependencyType(type, subject, nested = false)

    private fun dependencyType(
        type: KSType,
        subject: String,
        nested: Boolean,
    ): DependencyTypeModel {
        require(type.nullability != Nullability.NULLABLE) {
            if (nested) {
                "$subject has a nullable nested type argument '$type'; nullable dependency arguments are not supported"
            } else {
                "$subject has nullable type '$type'; dependencies must be non-null"
            }
        }
        val declaration = type.declaration
        require(declaration !is KSTypeParameter) {
            "$subject contains unresolved type parameter '${declaration.simpleName.asString()}'; " +
                "dependencies must use closed types"
        }
        require(declaration is KSClassDeclaration) {
            "$subject has no concrete class type"
        }
        val arguments = type.arguments.map { argument ->
            require(argument.variance != Variance.STAR) {
                "$subject contains a star projection; dependencies must use closed types"
            }
            require(argument.variance == Variance.INVARIANT) {
                "$subject contains use-site variance '${argument.variance.label}'; " +
                    "dependency arguments must be invariant"
            }
            val argumentType = requireNotNull(argument.type) {
                "$subject contains an unresolved type argument"
            }.resolve()
            dependencyType(argumentType, subject, nested = true)
        }
        return DependencyTypeModel(
            packageName = declaration.packageName.asString(),
            typeNames = declaration.typeNames(),
            arguments = arguments,
        )
    }

    private fun KSDeclaration.typeNames(): List<String> = buildList {
        var current: KSDeclaration? = this@typeNames
        while (current is KSClassDeclaration) {
            add(current.simpleName.asString())
            current = current.parentDeclaration
        }
    }.asReversed()

    private fun KSClassDeclaration.annotation(name: String): KSAnnotation =
        requireNotNull(annotationOrNull(name)) { "Missing annotation $name" }

    private fun KSClassDeclaration.annotationOrNull(name: String): KSAnnotation? =
        annotations.singleOrNull { annotation -> annotation.qualifiedName() == name }

    private fun KSFunctionDeclaration.annotationOrNull(name: String): KSAnnotation? =
        annotations.singleOrNull { annotation -> annotation.qualifiedName() == name }

    private fun KSAnnotation.qualifiedName(): String? =
        annotationType.resolve().declaration.qualifiedName?.asString()

    private companion object {
        const val INJECT = "pink.alex.ashlar.di.Inject"
        const val BINDS = "pink.alex.ashlar.di.Binds"
        const val PLUGIN_SCOPED = "pink.alex.ashlar.di.PluginScoped"
        const val INVOCATION_SCOPED = "pink.alex.ashlar.di.InvocationScoped"
        const val FACTORY = "pink.alex.ashlar.di.Factory"
        const val FRAMEWORK_COMPONENT = "pink.alex.ashlar.AshlarComponent"
        const val DEPENDENCY_QUALIFIER = "pink.alex.ashlar.di.DependencyQualifier"
    }
}
