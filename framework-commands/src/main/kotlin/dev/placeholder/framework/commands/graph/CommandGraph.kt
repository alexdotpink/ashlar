package dev.placeholder.framework.commands.graph

import dev.placeholder.framework.commands.route.CommandRoute
import dev.placeholder.framework.commands.route.CommandRouteIdentity
import dev.placeholder.framework.di.Inject
import dev.placeholder.framework.di.PluginScoped
import dev.placeholder.framework.commands.reference.PlayerRef
import org.bukkit.plugin.Plugin

/** Typed startup-only graph operations for capabilities that are not ordinary handlers. */
@Inject
@PluginScoped
public class CommandGraph {
    private val edges: MutableMap<CommandRouteIdentity, CommandGraphEdge> = linkedMapOf()
    private var frozen: Boolean = false

    public fun redirect(
        from: CommandRoute,
        to: CommandRoute,
    ): Unit = add(from, CommandGraphEdge.Redirect(to))

    public fun fork(
        from: CommandRoute,
        targets: suspend () -> List<CommandRoute>,
    ): Unit = add(from, CommandGraphEdge.Fork(targets))

    public fun external(
        from: CommandRoute,
        command: String,
        optional: Boolean = false,
    ) {
        require(command.isNotBlank()) { "An external command cannot be blank" }
        add(from, CommandGraphEdge.External(command.removePrefix("/"), optional))
    }

    internal fun freeze() {
        frozen = true
    }

    internal fun validateExternal(
        available: (String) -> Boolean,
        optionalMissing: (String) -> Unit,
    ) {
        val iterator = edges.iterator()
        while (iterator.hasNext()) {
            val (_, edge) = iterator.next()
            if (edge !is CommandGraphEdge.External) continue
            val root = edge.command.substringBefore(' ')
            if (available(root)) continue
            if (edge.optional) {
                optionalMissing(edge.command)
                iterator.remove()
            } else {
                error("Required external command '${edge.command}' is unavailable")
            }
        }
    }

    internal fun edge(route: CommandRoute): CommandGraphEdge? = edges[route.identity]

    internal fun edge(identity: CommandRouteIdentity): CommandGraphEdge? = edges[identity]

    private fun add(route: CommandRoute, edge: CommandGraphEdge) {
        check(!frozen) { "The command graph is frozen after command registration" }
        check(edges.putIfAbsent(route.identity, edge) == null) {
            "Command route ${route.identity} already has a graph edge"
        }
    }
}

internal sealed interface CommandGraphEdge {
    data class Redirect(val target: CommandRoute) : CommandGraphEdge
    data class Fork(val targets: suspend () -> List<CommandRoute>) : CommandGraphEdge
    data class External(val command: String, val optional: Boolean) : CommandGraphEdge
}

/** Observable dynamic route requirement with targeted client refresh support. */
public interface CommandRequirement {
    public val id: String

    public fun isAllowed(): Boolean

    public fun subscribe(refresh: () -> Unit): AutoCloseable
}

/** Refreshes the client command tree only for explicitly affected players. */
@Inject
@PluginScoped
public class CommandRefresh(
    private val plugin: Plugin,
) {
    public suspend fun refresh(player: PlayerRef) {
        refresh(listOf(player))
    }

    public suspend fun refresh(players: Iterable<PlayerRef>) {
        players.forEach { player ->
            player.access(plugin) { resolved -> resolved.updateCommands() }
        }
    }
}
