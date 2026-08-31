package dev.placeholder.framework.di.ksp

internal data class FactoryModel(
    val packageName: String,
    val typeNames: List<String>,
    val qualifiedName: String,
    val lifetime: LifetimeModel,
    val parameters: List<FactoryParameterModel>,
)

internal data class FactoryParameterModel(
    val name: String,
    val packageName: String,
    val typeNames: List<String>,
    val qualifier: BindingModel? = null,
)

internal enum class LifetimeModel {
    PLUGIN,
    INVOCATION,
    FACTORY,
}

internal data class RootComponentModel(
    val packageName: String,
    val typeNames: List<String>,
    val qualifiedName: String,
    val name: String?,
    val phase: Int,
    val bindings: List<BindingModel>,
)

internal data class BindingModel(
    val packageName: String,
    val typeNames: List<String>,
)

internal data class ContributionModel(
    val packageName: String,
    val typeNames: List<String>,
    val qualifiedName: String,
)
