package dev.placeholder.framework.events

import dev.placeholder.framework.di.DependencyGraph
import dev.placeholder.framework.di.Inject
import dev.placeholder.framework.di.PluginScoped
import dev.placeholder.framework.events.codegen.EventSetContribution
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.reflect.KClass
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.supervisorScope
import org.bukkit.plugin.Plugin

/** Marker for an immutable event published only inside one framework plug-in. */
public interface ApplicationEvent

/** One failed application event handler with its generated identity. */
public class ApplicationEventHandlerFailure(
    public val eventSet: KClass<*>,
    public val handler: String,
    cause: Throwable,
) : RuntimeException(
    "Application event handler '${eventSet.qualifiedName}.$handler' failed",
    cause,
)

/** Aggregate thrown after all matching application event handlers finish. */
public class ApplicationEventException(
    public val eventType: KClass<out ApplicationEvent>,
    failures: List<ApplicationEventHandlerFailure>,
) : RuntimeException(
    "${failures.size} handler(s) failed while publishing ${eventType.qualifiedName}",
    failures.firstOrNull(),
) {
    public val failures: List<ApplicationEventHandlerFailure> = failures.toList()

    init {
        failures.drop(1).forEach(::addSuppressed)
    }
}

/** Plug-in-scoped publisher and stream source for application events. */
@Inject
@PluginScoped
public class ApplicationEvents(
    plugin: Plugin,
    private val graph: DependencyGraph,
) : AutoCloseable {
    private val excluded: Set<KClass<*>> = plugin.javaClass
        .getAnnotation(ExcludeEventContributions::class.java)
        ?.types
        ?.toSet()
        .orEmpty()
    private val streams: MutableList<ApplicationStream> = CopyOnWriteArrayList()
    private val closed = AtomicBoolean()

    /** Publishes [event] and waits for every matching handler and backpressured stream. */
    public suspend fun publish(event: ApplicationEvent) {
        check(!closed.get()) { "Application events are closed" }
        val handlers = graph.contributions(EventSetContribution::class)
            .filterNot { contribution -> contribution.targetType in excluded }
            .flatMap { contribution ->
                contribution.definition.applicationHandlers.mapIndexedNotNull { index, definition ->
                    definition.takeIf { handler -> handler.eventType.java.isInstance(event) }
                        ?.let { handler -> ApplicationHandler(contribution, index, handler.name) }
                }
            }
        val failures = supervisorScope {
            val handlerWork = handlers.map { handler ->
                async {
                    runCatching {
                        handler.contribution.invokeApplication(
                            graph.get(handler.contribution.targetType),
                            handler.index,
                            event,
                        )
                    }.exceptionOrNull()?.let { cause ->
                        ApplicationEventHandlerFailure(
                            handler.contribution.targetType,
                            handler.name,
                            cause,
                        )
                    }
                }
            }
            val streamWork = streams.filter { stream -> stream.type.java.isInstance(event) }
                .map { stream -> async { stream.deliver(event) } }
            streamWork.forEach { delivery -> delivery.await() }
            handlerWork.mapNotNull { handler -> handler.await() }
        }
        if (failures.isNotEmpty()) {
            throw ApplicationEventException(event::class, failures)
        }
    }

    /** Creates a bounded non-replaying stream of assignable application events. */
    public inline fun <reified E : ApplicationEvent> stream(
        capacity: Int,
        overflow: BufferOverflow,
    ): Flow<E> {
        require(capacity > 0) { "An application event stream needs a positive capacity" }
        return stream(E::class, overflow).buffer(capacity, overflow)
    }

    @PublishedApi
    internal fun <E : ApplicationEvent> stream(
        type: KClass<E>,
        overflow: BufferOverflow,
    ): Flow<E> = callbackFlow {
        check(!closed.get()) { "Application events are closed" }
        @Suppress("UNCHECKED_CAST")
        val stream = ApplicationStream(type, overflow, this as ProducerScope<ApplicationEvent>)
        streams += stream
        if (closed.get()) stream.close()
        awaitClose { streams.remove(stream) }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        streams.forEach(ApplicationStream::close)
        streams.clear()
    }

    private data class ApplicationHandler(
        val contribution: EventSetContribution,
        val index: Int,
        val name: String,
    )

    private class ApplicationStream(
        val type: KClass<out ApplicationEvent>,
        private val overflow: BufferOverflow,
        private val producer: ProducerScope<ApplicationEvent>,
    ) {
        suspend fun deliver(event: ApplicationEvent) {
            if (overflow == BufferOverflow.SUSPEND) {
                runCatching { producer.send(event) }
            } else {
                producer.trySend(event)
            }
        }

        fun close() {
            producer.close()
        }
    }
}

/** Publishes this event through the application-event capability in context. */
context(events: ApplicationEvents)
public suspend fun ApplicationEvent.publish() {
    events.publish(this)
}
