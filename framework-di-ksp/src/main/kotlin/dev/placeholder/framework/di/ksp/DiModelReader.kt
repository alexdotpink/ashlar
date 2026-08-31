package dev.placeholder.framework.di.ksp

import com.google.devtools.ksp.getConstructors
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSType

internal class DiModelReader {
    fun factory(declaration: KSClassDeclaration): FactoryModel {
        val constructor = injectableConstructor(declaration)
        return FactoryModel(
            packageName = declaration.packageName.asString(),
            typeNames = declaration.typeNames(),
            qualifiedName = requireNotNull(declaration.qualifiedName?.asString()),
            lifetime = declaration.lifetime(),
            parameters = constructor.parameters.map { parameter ->
                val parameterDeclaration = parameter.type.resolve().declaration as KSClassDeclaration
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
                    name = requireNotNull(parameter.name?.asString()),
                    packageName = parameterDeclaration.packageName.asString(),
                    typeNames = parameterDeclaration.typeNames(),
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
        const val INJECT = "dev.placeholder.framework.di.Inject"
        const val BINDS = "dev.placeholder.framework.di.Binds"
        const val PLUGIN_SCOPED = "dev.placeholder.framework.di.PluginScoped"
        const val INVOCATION_SCOPED = "dev.placeholder.framework.di.InvocationScoped"
        const val FACTORY = "dev.placeholder.framework.di.Factory"
        const val FRAMEWORK_COMPONENT = "dev.placeholder.framework.FrameworkComponent"
        const val DEPENDENCY_QUALIFIER = "dev.placeholder.framework.di.DependencyQualifier"
    }
}
