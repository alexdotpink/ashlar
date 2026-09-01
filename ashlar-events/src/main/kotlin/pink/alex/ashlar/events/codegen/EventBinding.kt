package pink.alex.ashlar.events.codegen

import pink.alex.ashlar.events.ApplicationEvent
import pink.alex.ashlar.events.LifecycleEventRegistry
import kotlin.reflect.KClass
import org.bukkit.event.Event
import org.bukkit.event.EventPriority

/** Immutable metadata for one generated synchronous server event handler. */
public data class ServerEventHandlerDefinition(
    public val name: String,
    public val eventType: KClass<out Event>,
    public val priority: EventPriority,
    public val ignoreCancelled: Boolean,
    public val kind: ServerEventHandlerKind = ServerEventHandlerKind.SYNCHRONOUS,
)

/** Runtime execution kind of one generated server event handler. */
public enum class ServerEventHandlerKind {
    SYNCHRONOUS,
    OBSERVER,
}

/** Immutable metadata for one generated event set. */
public data class EventSetDefinition(
    public val handlers: List<ServerEventHandlerDefinition>,
    public val applicationHandlers: List<ApplicationEventHandlerDefinition> = emptyList(),
)

/** Immutable metadata for one generated application event handler. */
public data class ApplicationEventHandlerDefinition(
    public val name: String,
    public val eventType: KClass<out ApplicationEvent>,
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

    public suspend fun observe(
        target: Any,
        handler: Int,
        event: Event,
    ): Unit = invalidEventHandler(handler)

    public suspend fun invokeApplication(
        target: Any,
        handler: Int,
        event: ApplicationEvent,
    ): Unit = invalidEventHandler(handler)

    public fun configureLifecycle(
        target: Any,
        registry: LifecycleEventRegistry,
    ) {}
}

/** Reports an invalid generated event handler index. */
public fun invalidEventHandler(handler: Int): Nothing =
    error("Generated event binding received unknown handler index $handler")
