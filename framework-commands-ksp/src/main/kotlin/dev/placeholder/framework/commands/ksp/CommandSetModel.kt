package dev.placeholder.framework.commands.ksp

internal data class CommandSetModel(
    val packageName: String,
    val typeName: String,
    val rootName: String,
    val aliases: List<String>,
    val optionalAliases: List<String> = emptyList(),
    val permission: String?,
    val routes: List<RouteModel>,
    val helpName: String? = "help",
    val schemaVersion: Int = 1,
    val fragment: Boolean = false,
    val graphFunctions: List<GraphFunctionModel> = emptyList(),
)

internal data class GraphFunctionModel(
    val name: String,
    val parameters: List<InvocationParameterModel>,
)

internal data class RouteModel(
    val name: String,
    val functionName: String,
    val returnType: String,
    val suspending: Boolean,
    val parameters: List<ParameterModel>,
    val segments: List<SegmentModel> =
        listOf(SegmentModel.Literal(listOf(name), emptyList())) +
            parameters.indices.map(SegmentModel::Argument),
    val containers: List<ContainerModel> = emptyList(),
    val handlerParameters: List<InvocationParameterModel> =
        parameters.indices.map { index ->
            InvocationParameterModel(parameters[index].name, parameters[index].type, index)
        },
    val aliases: List<String> = emptyList(),
    val permissions: List<String> = emptyList(),
    val documentation: DocumentationModel = DocumentationModel(),
    val policies: List<PolicyModel> = emptyList(),
    val cancelOnExecutorRetire: Boolean = false,
)

internal sealed interface PolicyModel {
    data class Cooldown(val seconds: Long, val mode: String) : PolicyModel

    data class RateLimit(val permits: Int, val seconds: Long, val mode: String) : PolicyModel

    data object SingleFlight : PolicyModel

    data class Confirm(val seconds: Long) : PolicyModel

    data class Custom(
        val annotationType: String,
        val arguments: List<AnnotationArgumentModel>,
        val interceptorType: String,
        val phase: String,
        val order: Int,
    ) : PolicyModel
}

internal data class AnnotationArgumentModel(val name: String, val value: Any?)

internal data class ParameterModel(
    val name: String,
    /** Declared handler type, used only for direct generated calls. */
    val type: String,
    val optional: Boolean,
    /** Simple declared generic arguments retained for direct calls and typed routes. */
    val typeArguments: List<String> = emptyList(),
    val greedy: Boolean = false,
    val repeated: Boolean = false,
    /** Element type resolved by a runtime codec. */
    val valueType: String = type,
    val nullable: Boolean = false,
    val collection: Boolean = false,
    val vararg: Boolean = false,
    val option: OptionModel? = null,
    val options: OptionsModel? = null,
    val centerIntegers: Boolean = false,
    val minimumTicks: Int = 0,
    val registry: String? = null,
    val sensitive: Boolean = false,
    val observed: Boolean = false,
    val qualifier: String? = null,
)

internal data class OptionModel(
    val name: String,
    val shortName: Char? = null,
    val valueType: String,
    val nullable: Boolean = false,
    val repeated: Boolean = false,
    val presenceAware: Boolean = false,
    val qualifier: String? = null,
)

internal data class OptionsModel(
    val type: String,
    val members: List<OptionMemberModel>,
)

internal data class OptionMemberModel(
    val propertyName: String,
    val declaredType: String,
    val option: OptionModel,
    val hasDefault: Boolean,
    val collection: Boolean = false,
    val sensitive: Boolean = false,
    val observed: Boolean = false,
)

internal sealed interface SegmentModel {
    data class Literal(val names: List<String>, val permissions: List<String>) : SegmentModel

    data class Argument(val parameterIndex: Int) : SegmentModel

    /** One raw handler tail scanned for positionals and named options at runtime. */
    data class ScannedArguments(val firstParameterIndex: Int) : SegmentModel
}

internal data class ContainerModel(
    val typeName: String,
    val parameters: List<InvocationParameterModel>,
)

internal data class InvocationParameterModel(
    val name: String,
    val type: String,
    val argumentIndex: Int?,
)

internal data class DocumentationModel(
    val summary: String = "",
    val parameters: Map<String, String> = emptyMap(),
    val examples: List<String> = emptyList(),
)
