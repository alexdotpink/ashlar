package dev.placeholder.framework.events.internal

import dev.placeholder.framework.ComponentContext
import dev.placeholder.framework.ComponentPhase
import dev.placeholder.framework.FrameworkComponent
import dev.placeholder.framework.PluginComponent
import dev.placeholder.framework.di.DependencyGraph
import dev.placeholder.framework.di.Inject
import dev.placeholder.framework.events.ExcludeEventContributions
import dev.placeholder.framework.events.ServerEventFailure
import dev.placeholder.framework.events.ServerEventFailureReporter
import dev.placeholder.framework.events.codegen.EventSetContribution
import org.bukkit.event.Event
import org.bukkit.event.EventException
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.plugin.EventExecutor

/** Owns every generated server event registration in one plug-in. */
@FrameworkComponent(name = "events", phase = ComponentPhase.FRAMEWORK)
@Inject
public class EventRuntimeComponent(
    private val graph: DependencyGraph,
) : PluginComponent() {
    override fun ComponentContext.start() {
        graph.bindDefault(
            ServerEventFailureReporter::class,
            ServerEventFailureReporter { failure ->
                logger.error(
                    "[${failure.eventSet.qualifiedName}] Event handler '${failure.handler}' failed for " +
                        failure.eventType.qualifiedName,
                    failure.cause,
                )
            },
        )
        val reporter = graph.get(ServerEventFailureReporter::class)
        val excluded = plugin.javaClass.getAnnotation(ExcludeEventContributions::class.java)
            ?.types
            ?.toSet()
            .orEmpty()

        graph.contributions(EventSetContribution::class)
            .filterNot { contribution -> contribution.targetType in excluded }
            .forEach { contribution -> register(contribution, reporter) }
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
                    ServerEventHandlerInvoker(reporter).invoke(contribution, target, index, event)
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
