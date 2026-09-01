package pink.alex.ashlar.events.internal

import pink.alex.ashlar.ComponentContext
import pink.alex.ashlar.ComponentPhase
import pink.alex.ashlar.AshlarComponent
import pink.alex.ashlar.PluginComponent
import pink.alex.ashlar.di.DependencyGraph
import pink.alex.ashlar.di.Inject
import pink.alex.ashlar.events.ExcludeEventContributions
import pink.alex.ashlar.events.ApplicationEvents
import pink.alex.ashlar.events.LifecycleEventRegistry
import pink.alex.ashlar.events.ServerEventFailure
import pink.alex.ashlar.events.ServerEventFailureReporter
import pink.alex.ashlar.events.codegen.EventSetContribution
import pink.alex.ashlar.events.codegen.ServerEventHandlerKind
import pink.alex.ashlar.events.serverEventFailureReporter
import kotlinx.coroutines.CoroutineStart
import org.bukkit.event.Event
import org.bukkit.event.EventException
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.plugin.EventExecutor

/** Owns every generated server event registration in one plug-in. */
@AshlarComponent(name = "events", phase = ComponentPhase.FRAMEWORK)
@Inject
public class EventRuntimeComponent(
    private val graph: DependencyGraph,
    private val applicationEvents: ApplicationEvents,
    private val lifecycleEvents: LifecycleEventRegistry,
) : PluginComponent() {
    override fun ComponentContext.start() {
        own(applicationEvents)
        val reporter = graph.serverEventFailureReporter(plugin)
        val excluded = plugin.javaClass.getAnnotation(ExcludeEventContributions::class.java)
            ?.types
            ?.toSet()
            .orEmpty()

        val contributions = graph.contributions(EventSetContribution::class)
            .filterNot { contribution -> contribution.targetType in excluded }
        contributions.forEach { contribution ->
            contribution.configureLifecycle(graph.get(contribution.targetType), lifecycleEvents)
        }
        contributions.forEach { contribution -> register(contribution, reporter) }
    }

    private fun ComponentContext.register(
        contribution: EventSetContribution,
        reporter: ServerEventFailureReporter,
    ) {
        val target = dependencies.get(contribution.targetType)
        val listener = object : Listener {}
        contribution.definition.handlers.forEachIndexed { index, definition ->
            @Suppress("UNCHECKED_CAST")
            server.pluginManager.registerEvent(
                definition.eventType.java as Class<Event>,
                listener,
                definition.priority,
                EventExecutor { _, event ->
                    when (definition.kind) {
                        ServerEventHandlerKind.SYNCHRONOUS ->
                            ServerEventHandlerInvoker(reporter).invoke(contribution, target, index, event)
                        ServerEventHandlerKind.OBSERVER -> task(
                            name = "${contribution.targetType.simpleName}.${definition.name}",
                            start = CoroutineStart.UNDISPATCHED,
                        ) {
                            contribution.observe(target, index, event)
                        }
                    }
                },
                plugin,
                definition.ignoreCancelled,
            )
        }
        own(AutoCloseable { HandlerList.unregisterAll(listener) })
    }
}

internal class ServerEventHandlerInvoker(
    private val reporter: ServerEventFailureReporter,
) {
    fun invoke(
        contribution: EventSetContribution,
        target: Any,
        index: Int,
        event: Event,
    ) {
        runCatching { contribution.invoke(target, index, event) }
            .onFailure { failure ->
                val definition = contribution.definition.handlers[index]
                reporter.report(
                    ServerEventFailure(
                        eventSet = contribution.targetType,
                        handler = definition.name,
                        eventType = definition.eventType,
                        cause = (failure as? EventException)?.cause ?: failure,
                    ),
                )
            }
    }
}
