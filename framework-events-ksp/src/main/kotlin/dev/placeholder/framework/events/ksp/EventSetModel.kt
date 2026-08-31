package dev.placeholder.framework.events.ksp

internal data class EventSetModel(
    val packageName: String,
    val typeNames: List<String>,
    val abstract: Boolean,
    val open: Boolean,
    val handlers: List<ServerHandlerModel>,
) {
    val typeName: String
        get() = typeNames.joinToString("_")
}

internal data class ServerHandlerModel(
    val functionName: String,
    val eventPackageName: String,
    val eventTypeNames: List<String>,
    val eventQualifiedName: String,
    val event: Boolean,
    val priority: String,
    val ignoreCancelled: Boolean,
    val cancellable: Boolean,
    val suspending: Boolean,
    val returnType: String,
    val parameterCount: Int,
    val private: Boolean,
    val protected: Boolean,
    val generic: Boolean,
)
