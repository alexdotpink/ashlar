package dev.placeholder.framework.commands.route

import dev.placeholder.framework.commands.CommandSender
import dev.placeholder.framework.di.Inject
import dev.placeholder.framework.di.PluginScoped
import dev.placeholder.framework.execution.withGlobal
import org.bukkit.command.CommandSender as BukkitCommandSender
import org.bukkit.plugin.Plugin
import dev.placeholder.framework.commands.graph.CommandGraph
import dev.placeholder.framework.commands.graph.CommandGraphEdge
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.async

/** Explicitly submits generated typed routes through the same registered command runtime. */
@Inject
@PluginScoped
public class CommandDispatcher(
    private val plugin: Plugin,
    private val graph: CommandGraph,
) {
    /** Invokes a typed route through the registered command pipeline or fails if Paper rejects it. */
    public suspend fun invoke(
        route: CommandRoute,
        sender: CommandSender,
    ) {
        check(dispatch(route, sender)) { "Typed command route '${route.identity}' was rejected" }
    }

    /** Dispatches a generated route as [sender] and returns Paper's acceptance result. */
    public suspend fun dispatch(
        route: CommandRoute,
        sender: CommandSender,
    ): Boolean {
        val bukkit = sender.audience as? BukkitCommandSender
            ?: error("This command sender is no longer backed by a Paper CommandSender")
        return when (val edge = graph.edge(route)) {
            null -> dispatchPaper(bukkit, route.command.removePrefix("/"))
            is CommandGraphEdge.Redirect -> dispatch(edge.target, sender)
            is CommandGraphEdge.Fork -> supervisorScope {
                edge.targets().map { target -> async { dispatch(target, sender) } }
                    .map { result -> result.await() }
                    .all { accepted -> accepted }
            }
            is CommandGraphEdge.External -> dispatchPaper(bukkit, edge.command).also { accepted ->
                check(accepted || edge.optional) { "Required external command '${edge.command}' is unavailable" }
            }
        }
    }

    private suspend fun dispatchPaper(sender: BukkitCommandSender, command: String): Boolean =
        plugin.withGlobal { plugin.server.dispatchCommand(sender, command) }
}
