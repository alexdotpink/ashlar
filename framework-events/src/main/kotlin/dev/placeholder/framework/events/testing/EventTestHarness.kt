package dev.placeholder.framework.events.testing

import dev.placeholder.framework.di.DependencyGraph
import dev.placeholder.framework.events.ApplicationEvents
import dev.placeholder.framework.events.EventRegistration
import dev.placeholder.framework.events.ServerEventFailure
import dev.placeholder.framework.events.ServerEventFailureReporter
import dev.placeholder.framework.events.ServerEventRegistrar
import dev.placeholder.framework.events.ServerEvents
import dev.placeholder.framework.events.codegen.EventSetContribution
import dev.placeholder.framework.events.codegen.ServerEventHandlerKind
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.reflect.KClass
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import org.bukkit.event.Cancellable
import org.bukkit.event.Event
import org.bukkit.event.EventPriority

/** Result of one server-free event dispatch. */
public data class EventTestResult(
    public val serverFailures: List<ServerEventFailure>,
    public val observerFailures: List<Throwable>,
) {
    /** Throws when any synchronous handler or coroutine observer failed. */
    public fun checkSuccessful() {
        serverFailures.firstOrNull()?.let { failure -> throw failure.cause }
        observerFailures.firstOrNull()?.let { failure -> throw failure }
    }
}

/** Server-free executor for generated event bindings and temporal event operations. */
public class EventTestHarness(
    private val graph: DependencyGraph,
) : AutoCloseable {
    private val serverFailures = CopyOnWriteArrayList<ServerEventFailure>()
    private val registrar = InMemoryServerEventRegistrar()

    public val serverEvents: ServerEvents = ServerEvents.testing(
        registrar,
        ServerEventFailureReporter(serverFailures::add),
    )

    public val applicationEvents: ApplicationEvents = ApplicationEvents(graph)

    /** Dispatches one event through generated and dynamic handlers in native priority order. */
    public suspend fun dispatch(event: Event): EventTestResult = coroutineScope {
        val failureStart = serverFailures.size
        val observerFailures = CopyOnWriteArrayList<Throwable>()
        val observers = mutableListOf<Job>()
        val actions = buildList {
            graph.contributions(EventSetContribution::class).forEach { contribution ->
                val target = graph.get(contribution.targetType)
                contribution.definition.handlers.forEachIndexed { index, definition ->
                    if (!definition.eventType.java.isInstance(event)) return@forEachIndexed
                    add(
                        TestAction(
                            priority = definition.priority,
                            ignoreCancelled = definition.ignoreCancelled,
                            observer = definition.kind == ServerEventHandlerKind.OBSERVER,
                        ) {
                            if (definition.kind == ServerEventHandlerKind.OBSERVER) {
                                contribution.observe(target, index, event)
                            } else {
                                runCatching { contribution.invoke(target, index, event) }
                                    .onFailure { cause ->
                                        serverFailures += ServerEventFailure(
                                            contribution.targetType,
                                            definition.name,
                                            definition.eventType,
                                            cause,
                                        )
                                    }
                            }
                        },
                    )
                }
            }
            registrar.snapshot(event).forEach { registration ->
                add(
                    TestAction(
                        registration.priority,
                        registration.ignoreCancelled,
                        observer = false,
                    ) { registration.handler(event) },
                )
            }
        }.sortedBy { action -> action.priority.ordinal }

        actions.forEach { action ->
            if (action.ignoreCancelled && (event as? Cancellable)?.isCancelled == true) return@forEach
            if (action.observer) {
                observers += launch(start = CoroutineStart.UNDISPATCHED) {
                    runCatching { action.invoke() }.onFailure(observerFailures::add)
                }
            } else {
                action.invoke()
            }
        }
        observers.joinAll()
        EventTestResult(
            serverFailures = serverFailures.drop(failureStart),
            observerFailures = observerFailures.toList(),
        )
    }

    override fun close() {
        registrar.close()
        applicationEvents.close()
    }

    private data class TestAction(
        val priority: EventPriority,
        val ignoreCancelled: Boolean,
        val observer: Boolean,
        val invoke: suspend () -> Unit,
    )
}

internal class InMemoryServerEventRegistrar : ServerEventRegistrar, AutoCloseable {
    private val registrations = CopyOnWriteArrayList<Registration>()
    private val closed = AtomicBoolean()

    override fun <E : Event> register(
        type: KClass<E>,
        priority: EventPriority,
        ignoreCancelled: Boolean,
        handler: (Event) -> Unit,
    ): EventRegistration {
        check(!closed.get()) { "The event test runtime is closed" }
        val registration = Registration(type, priority, ignoreCancelled, handler)
        registrations += registration
        return EventRegistration { registrations.remove(registration) }
    }

    fun snapshot(event: Event): List<Registration> = registrations
        .filter { registration -> registration.type.java.isInstance(event) }

    override fun close() {
        closed.set(true)
        registrations.clear()
    }

    data class Registration(
        val type: KClass<out Event>,
        val priority: EventPriority,
        val ignoreCancelled: Boolean,
        val handler: (Event) -> Unit,
    )
}
