package pink.alex.ashlar.events.ksp

internal class EventSetValidator {
    fun validate(model: EventSetModel): List<String> = buildList {
        if (!model.abstract) {
            if (model.open) add("A concrete event-set class must be final")
            if (model.handlers.isEmpty() &&
                model.applicationHandlers.isEmpty() &&
                model.lifecycleFunctions.isEmpty()
            ) {
                add("A concrete event-set class must declare at least one event handler")
            }
        }
        model.handlers.forEach { handler -> validate(handler, this) }
        model.applicationHandlers.forEach { handler -> validate(handler, this) }
        model.lifecycleFunctions.forEach { function -> validate(function, this) }
    }

    private fun validate(
        function: LifecycleFunctionModel,
        problems: MutableList<String>,
    ) {
        val label = function.functionName
        if (function.receiverType != LIFECYCLE_REGISTRY_TYPE) {
            problems += "Lifecycle configuration '$label' must extend LifecycleEventRegistry"
        }
        if (function.parameterCount != 0) {
            problems += "Lifecycle configuration '$label' cannot declare value parameters"
        }
        if (function.suspending) problems += "Lifecycle configuration '$label' cannot suspend"
        if (function.returnType != UNIT_TYPE) problems += "Lifecycle configuration '$label' must return Unit"
        if (function.private || function.protected) {
            problems += "Lifecycle configuration '$label' must be public or internal"
        }
        if (function.generic) problems += "Lifecycle configuration '$label' cannot declare type parameters"
    }

    private fun validate(
        handler: ApplicationHandlerModel,
        problems: MutableList<String>,
    ) {
        val label = handler.functionName
        if (handler.eventQualifiedName.isBlank()) {
            problems += "Application event handler '$label' must declare an event extension receiver"
        } else if (!handler.applicationEvent) {
            problems += "Application event handler '$label' receiver must implement ApplicationEvent"
        }
        if (handler.parameterCount != 0) {
            problems += "Application event handler '$label' cannot declare value parameters"
        }
        if (handler.returnType != UNIT_TYPE) problems += "Application event handler '$label' must return Unit"
        if (handler.private || handler.protected) {
            problems += "Application event handler '$label' must be public or internal"
        }
        if (handler.generic) problems += "Application event handler '$label' cannot declare type parameters"
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
        const val LIFECYCLE_REGISTRY_TYPE = "pink.alex.ashlar.events.LifecycleEventRegistry"
    }
}
