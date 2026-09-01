package pink.alex.ashlar.commands.internal

import pink.alex.ashlar.ComponentContext
import pink.alex.ashlar.ComponentPhase
import pink.alex.ashlar.AshlarComponent
import pink.alex.ashlar.PluginComponent
import pink.alex.ashlar.commands.codegen.CommandSetBinding
import pink.alex.ashlar.commands.codegen.CommandSetContribution
import pink.alex.ashlar.commands.codegen.CommandSegmentDefinition
import pink.alex.ashlar.commands.ExcludeCommandContributions
import pink.alex.ashlar.commands.codec.CommandArgumentCodec
import pink.alex.ashlar.commands.graph.CommandGraph
import pink.alex.ashlar.di.DependencyGraph
import pink.alex.ashlar.di.Inject
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents
import org.bukkit.event.EventHandler
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerQuitEvent

/** One late-starting runtime for every generated command contribution in the plug-in. */
@AshlarComponent(name = "commands", phase = ComponentPhase.FRAMEWORK)
@Inject
public class CommandRuntimeComponent(
    private val graph: DependencyGraph,
) : PluginComponent() {
    override fun ComponentContext.start() {
        val excluded = plugin.javaClass.getAnnotation(ExcludeCommandContributions::class.java)
            ?.types
            ?.toSet()
            .orEmpty()
        val contributions = graph.contributions(CommandSetContribution::class)
            .filterNot { contribution -> contribution.targetType in excluded }
        if (contributions.isEmpty()) return
        val commandGraph = graph.get(CommandGraph::class)
        contributions.forEach { contribution ->
            contribution.configureGraph(graph.get(contribution.targetType), graph, commandGraph)
        }
        commandGraph.validateExternal(
            available = { command -> server.commandMap.getCommand(command) != null },
            optionalMissing = { command -> logger.warn("Optional external command '{}' is unavailable", command) },
        )
        commandGraph.freeze()
        val roots = groupRoots(contributions, graph)
        roots.values.forEach { members ->
            val owner = members.single { member -> !member.definition.fragment }
            (listOf(owner.definition.name) + owner.definition.aliases).forEach { name ->
                check(server.commandMap.getCommand(name) == null) {
                    "Required command name '$name' is already registered"
                }
            }
        }
        val retirement = CommandRetirement()
        val listener = object : Listener {
            @EventHandler
            fun onQuit(event: PlayerQuitEvent) {
                retirement.retire(event.player.uniqueId)
            }
        }
        server.pluginManager.registerEvents(listener, plugin)
        own(AutoCloseable { HandlerList.unregisterAll(listener) })
        plugin.lifecycleManager.registerEventHandler(LifecycleEvents.COMMANDS) { event ->
            val adapter = PaperCommandAdapter(this, graph, retirement)
            roots.forEach { (_, rootContributions) ->
                val owner = rootContributions.single { contribution -> !contribution.definition.fragment }
                event.registrar().register(
                    adapter.compile(
                        rootContributions.map { contribution ->
                            BoundCommandSet(graph.get(contribution.targetType), contribution)
                        },
                    ),
                    owner.definition.aliases + owner.definition.optionalAliases.filter { alias ->
                        server.commandMap.getCommand(alias) == null
                    },
                )
            }
        }
    }
}

/** Compatibility component for explicitly constructed command sets. */
internal class CommandSetComponent<T : Any>(
    private val target: T,
    private val binding: CommandSetBinding<T>,
) : PluginComponent() {
    override fun ComponentContext.start() {
        val graph = dependencies as DependencyGraph
        plugin.lifecycleManager.registerEventHandler(LifecycleEvents.COMMANDS) { event ->
            event.registrar().register(
                PaperCommandAdapter(this, graph, CommandRetirement()).compile(target, binding),
                binding.definition.aliases,
            )
        }
    }
}

private fun groupRoots(
    contributions: List<CommandSetContribution>,
    graph: DependencyGraph,
): Map<String, List<CommandSetContribution>> {
    val grouped = contributions.groupBy { contribution -> contribution.definition.name }
    val aliases = mutableMapOf<String, String>()
    grouped.forEach { (name, members) ->
        check(members.count { member -> !member.definition.fragment } == 1) {
            "Command root '$name' must have exactly one non-fragment @Commands owner"
        }
        val owner = members.single { member -> !member.definition.fragment }
        members.flatMap { member -> member.definition.routes }
            .groupBy { route ->
                route.segments.joinToString("/") { segment ->
                    when (segment) {
                        is CommandSegmentDefinition.Literal -> segment.names.first()
                        is CommandSegmentDefinition.Argument ->
                            route.parameters[segment.parameterIndex].let { parameter ->
                                val native = PaperNativeArguments.argumentType(parameter)
                                val codec = graph.contributions(CommandArgumentCodec::class)
                                    .singleOrNull { candidate ->
                                        candidate.type == parameter.type && candidate.qualifier == parameter.qualifier
                                    }
                                    ?: BuiltinCodecs.find(parameter.type)
                                "<${native?.javaClass?.name ?: codec?.syntax ?: "unknown"}>"
                            }
                        is CommandSegmentDefinition.ScannedArguments -> "<scanned>"
                    }
                }
            }
            .filterValues { routes -> routes.size > 1 }
            .keys
            .forEach { signature -> error("Ambiguous command route '$name $signature'") }
        (listOf(name) + owner.definition.aliases).forEach { alias ->
            val previous = aliases.putIfAbsent(alias, name)
            check(previous == null || previous == name) {
                "Command name '$alias' belongs to both '$previous' and '$name'"
            }
        }
    }
    return grouped
}
