package dev.placeholder.framework.events.ksp

internal class EventSetValidator {
    fun validate(model: EventSetModel): List<String> = buildList {
        if (model.abstract) return@buildList
        if (model.open) add("A concrete @Events class must be final")
        if (model.handlers.isEmpty()) add("A concrete @Events class must declare at least one event handler")
        model.handlers.forEach { handler -> validate(handler, this) }
    }

    private fun validate(
        handler: ServerHandlerModel,
        problems: MutableList<String>,
    ) {
        val label = handler.functionName
        if (handler.eventQualifiedName.isBlank()) {
            problems += "Event handler '$label' must declare an event extension receiver"
        } else if (!handler.event) problems += "Event handler '$label' receiver must extend Event"
        if (handler.parameterCount != 0) {
            problems += "Server event handler '$label' cannot declare value parameters"
        }
        if (handler.observer && !handler.suspending) {
            problems += "@Observe server event handler '$label' must suspend"
        }
        if (!handler.observer && handler.suspending) {
            problems += "@On server event handler '$label' cannot suspend"
        }
        if (handler.returnType != UNIT_TYPE) problems += "Server event handler '$label' must return Unit"
        if (handler.private || handler.protected) {
            problems += "Server event handler '$label' must be public or internal"
        }
        if (handler.generic) problems += "Server event handler '$label' cannot declare type parameters"
        if (handler.ignoreCancelled && !handler.cancellable) {
            problems += "Server event handler '$label' can ignore cancellation only for a Cancellable event"
        }
    }

    private companion object {
        const val UNIT_TYPE = "kotlin.Unit"
    }
}
