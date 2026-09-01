package pink.alex.ashlar.commands.internal

import com.mojang.brigadier.Command
import com.mojang.brigadier.arguments.ArgumentType
import com.mojang.brigadier.arguments.BoolArgumentType
import com.mojang.brigadier.arguments.DoubleArgumentType
import com.mojang.brigadier.arguments.FloatArgumentType
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.LongArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.ArgumentBuilder
import com.mojang.brigadier.tree.LiteralCommandNode
import pink.alex.ashlar.ComponentContext
import pink.alex.ashlar.commands.codec.CommandSyntax
import pink.alex.ashlar.commands.codegen.CommandRouteDefinition
import pink.alex.ashlar.commands.codegen.CommandSegmentDefinition
import pink.alex.ashlar.commands.codegen.CommandSetDefinition
import pink.alex.ashlar.commands.codegen.CommandSetContribution
import pink.alex.ashlar.commands.help.DefaultCommandHelpRenderer
import pink.alex.ashlar.commands.help.CommandHelpRenderer
import pink.alex.ashlar.di.DependencyGraph
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands as PaperCommands

/** Sole Paper/Brigadier adapter; generated bindings and the invocation engine remain platform-free. */
internal class PaperCommandAdapter(
    private val runtime: ComponentContext,
    private val graph: DependencyGraph,
    retirement: CommandRetirement,
) {
    private val runner = CommandInvocationRunner(runtime, graph, retirement)
    private val helpRenderer: CommandHelpRenderer =
        graph.contributions(CommandHelpRenderer::class).singleOrNull() ?: DefaultCommandHelpRenderer

    fun compile(
        target: Any,
        binding: CommandSetContribution,
    ): LiteralCommandNode<CommandSourceStack> = compile(listOf(BoundCommandSet(target, binding)))

    fun compile(sets: List<BoundCommandSet>): LiteralCommandNode<CommandSourceStack> {
        val owner = sets.single { set -> !set.binding.definition.fragment }
        val routes = sets.flatMap { set -> set.binding.definition.routes }
        val ownerDefinition = owner.binding.definition
        val definition = CommandSetDefinition(
            name = ownerDefinition.name,
            aliases = ownerDefinition.aliases,
            optionalAliases = ownerDefinition.optionalAliases,
            permission = ownerDefinition.permission,
            routes = routes,
            helpName = ownerDefinition.helpName,
            fragment = false,
        )
        val root = PaperCommands.literal(definition.name)
        definition.permission?.let { permission ->
            root.requires { source -> source.sender.hasPermission(permission) }
        }
        root.executes { context -> showHelp(definition, context.source, 1) }
        definition.helpName?.let { helpName ->
            check(routes.none { route ->
                route.segments.firstOrNull().let { segment ->
                    segment is CommandSegmentDefinition.Literal && helpName in segment.names
                }
            }) { "Automatic help literal '$helpName' collides with a command route" }
            val help = PaperCommands.literal(helpName)
                .executes { context -> showHelp(definition, context.source, 1) }
            help.then(
                PaperCommands.argument("page", IntegerArgumentType.integer(1))
                    .executes { context ->
                        showHelp(definition, context.source, IntegerArgumentType.getInteger(context, "page"))
                    },
            )
            root.then(help)
        }
        sets.forEach { set ->
            set.binding.definition.routes.forEachIndexed { index, route ->
                appendSegments(set.target, set.binding, index, route, 0, root)
            }
        }
        return root.build()
    }

    private fun appendSegments(
        target: Any,
        binding: CommandSetContribution,
        routeIndex: Int,
        route: CommandRouteDefinition,
        segmentIndex: Int,
        parent: ArgumentBuilder<CommandSourceStack, *>,
    ) {
        if (segmentIndex == route.segments.size) {
            parent.executes { context -> runner.accept(target, binding, routeIndex, route, context) }
            return
        }
        when (val segment = route.segments[segmentIndex]) {
            is CommandSegmentDefinition.Literal -> segment.names.forEach { name ->
                val literal = PaperCommands.literal(name)
                if (segment.permissions.isNotEmpty()) {
                    literal.requires { source -> segment.permissions.all(source.sender::hasPermission) }
                }
                appendSegments(target, binding, routeIndex, route, segmentIndex + 1, literal)
                parent.then(literal)
            }
            is CommandSegmentDefinition.Argument -> appendArgument(
                target,
                binding,
                routeIndex,
                route,
                segmentIndex,
                segment,
                parent,
            )
            is CommandSegmentDefinition.ScannedArguments -> appendScannedArguments(
                target,
                binding,
                routeIndex,
                route,
                segment,
                parent,
            )
        }
    }

    private fun appendScannedArguments(
        target: Any,
        binding: CommandSetContribution,
        routeIndex: Int,
        route: CommandRouteDefinition,
        segment: CommandSegmentDefinition.ScannedArguments,
        parent: ArgumentBuilder<CommandSourceStack, *>,
    ) {
        val argumentName = "__ashlar_arguments_$routeIndex"
        parent.executes { context ->
            runner.acceptScanned(target, binding, routeIndex, route, context, segment.firstParameterIndex, "")
        }
        val tail = PaperCommands.argument(argumentName, StringArgumentType.greedyString())
            .executes { context ->
                runner.acceptScanned(
                    target,
                    binding,
                    routeIndex,
                    route,
                    context,
                    segment.firstParameterIndex,
                    context.getArgument(argumentName, String::class.java),
                )
            }
        parent.then(tail)
    }

    private fun appendArgument(
        target: Any,
        binding: CommandSetContribution,
        routeIndex: Int,
        route: CommandRouteDefinition,
        segmentIndex: Int,
        segment: CommandSegmentDefinition.Argument,
        parent: ArgumentBuilder<CommandSourceStack, *>,
    ) {
        val parameter = route.parameters[segment.parameterIndex]
        if (parameter.repeated) {
            check(segmentIndex == route.segments.lastIndex) {
                "Repeated argument '${parameter.name}' must be terminal"
            }
            appendScannedArguments(
                target,
                binding,
                routeIndex,
                route,
                CommandSegmentDefinition.ScannedArguments(segment.parameterIndex),
                parent,
            )
            return
        }
        if (parameter.optional) {
            check(route.segments.drop(segmentIndex).all { remaining ->
                remaining is CommandSegmentDefinition.Argument &&
                    route.parameters[remaining.parameterIndex].optional
            }) { "Optional argument '${parameter.name}' must be terminal" }
            parent.executes { context ->
                runner.accept(target, binding, routeIndex, route, context, segment.parameterIndex)
            }
        }
        val nativeType = PaperNativeArguments.argumentType(parameter)
        val syntax = nativeType
            ?: argumentType(runner.codec(parameter).syntax, parameter.greedy || parameter.repeated)
        val argument = PaperCommands.argument(parameter.name, syntax)
        if (nativeType == null || runner.hasSuggestionProvider(parameter)) {
            argument.suggests { context, builder ->
                runner.suggest("${binding.definition.name} ${route.name}", parameter, context, builder)
            }
        }
        appendSegments(target, binding, routeIndex, route, segmentIndex + 1, argument)
        parent.then(argument)
    }

    private fun showHelp(
        definition: pink.alex.ashlar.commands.codegen.CommandSetDefinition,
        source: CommandSourceStack,
        page: Int,
    ): Int {
        val invocation = source.snapshot("${definition.name} help")
        runtime.task("command:${definition.name}:help") {
            invocation.sender.audience.sendMessage(helpRenderer.render(definition, invocation.sender, page))
        }
        return Command.SINGLE_SUCCESS
    }
}

internal data class BoundCommandSet(
    val target: Any,
    val binding: CommandSetContribution,
)

private fun argumentType(syntax: CommandSyntax, greedy: Boolean): ArgumentType<*> = if (greedy) {
    StringArgumentType.greedyString()
} else {
    when (syntax) {
        CommandSyntax.WORD -> StringArgumentType.word()
        CommandSyntax.STRING -> StringArgumentType.string()
        CommandSyntax.GREEDY_STRING -> StringArgumentType.greedyString()
        CommandSyntax.INTEGER -> IntegerArgumentType.integer()
        CommandSyntax.LONG -> LongArgumentType.longArg()
        CommandSyntax.FLOAT -> FloatArgumentType.floatArg()
        CommandSyntax.DOUBLE -> DoubleArgumentType.doubleArg()
        CommandSyntax.BOOLEAN -> BoolArgumentType.bool()
    }
}
