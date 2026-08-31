package dev.placeholder.framework.events.codegen

import kotlin.reflect.KClass
import org.bukkit.event.Event
import org.bukkit.event.EventPriority

/** Immutable metadata for one generated synchronous server event handler. */
public data class ServerEventHandlerDefinition(
    public val name: String,
    public val eventType: KClass<out Event>,
    public val priority: EventPriority,
    public val ignoreCancelled: Boolean,
)

/** Immutable metadata for one generated event set. */
public data class EventSetDefinition(
    public val handlers: List<ServerEventHandlerDefinition>,
)

/** Non-generic runtime view contributed by each generated event binding. */
public interface EventSetContribution {
    public val targetType: KClass<*>

    public val definition: EventSetDefinition

    public fun invoke(
        target: Any,
        handler: Int,
        event: Event,
    )
}

/** Reports an invalid generated event handler index. */
public fun invalidEventHandler(handler: Int): Nothing =
    error("Generated event binding received unknown handler index $handler")
