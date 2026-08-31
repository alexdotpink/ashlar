package dev.placeholder.framework.commands.testing

import dev.placeholder.framework.commands.CommandInvocation
import dev.placeholder.framework.commands.CommandResult
import dev.placeholder.framework.commands.codec.CommandArgumentCodec
import dev.placeholder.framework.commands.codegen.CommandRouteDefinition
import dev.placeholder.framework.commands.codegen.CommandSegmentDefinition
import dev.placeholder.framework.commands.codegen.CommandSetContribution
import dev.placeholder.framework.commands.codegen.commandResult
import dev.placeholder.framework.commands.internal.BuiltinCodecs
import dev.placeholder.framework.commands.internal.ScannedCommandArguments
import dev.placeholder.framework.commands.parsing.CommandLineTokenizer
import dev.placeholder.framework.di.DependencyGraph
import kotlin.reflect.KClass

/** Server-free executor for a generated command plan and its direct binding. */
public class CommandTestHarness(
    private val contribution: CommandSetContribution,
    private val target: Any,
    private val dependencies: DependencyGraph,
) {
    /** Parses and invokes one complete command line through generated metadata and codecs. */
    public suspend fun execute(
        command: String,
        invocation: CommandInvocation,
    ): CommandResult {
        val tokens = CommandLineTokenizer.tokenize(command.removePrefix("/"))
            .map { token -> token.value }
        require(tokens.isNotEmpty()) { "A command line cannot be empty" }
        val roots = listOf(contribution.definition.name) + contribution.definition.aliases
        require(tokens.first() in roots) { "Expected one of ${roots.joinToString()}" }
        val input = tokens.drop(1)
        val matches = contribution.definition.routes.mapIndexedNotNull { index, route ->
            match(route, input)?.copy(index = index)
        }
        require(matches.size == 1) {
            if (matches.isEmpty()) "No command route matches '$command'"
            else "Multiple command routes match '$command'"
        }
        val match = matches.single()
        dependencies.invocation(invocation, invocation.sender, invocation.executor).use { scope ->
            val prefix = match.rawArguments.mapIndexed { index, raw ->
                codec(match.route.parameters[index]).resolve(raw, invocation, scope)
            }
            val resolved = match.scannedIndex?.let { firstIndex ->
                prefix + ScannedCommandArguments(
                    match.index,
                    match.route,
                    contribution,
                    resolve = { type, qualifier, raw -> codec(type, qualifier).resolve(raw, invocation, scope) },
                    encode = { type, qualifier, value -> encode(codec(type, qualifier), value) },
                ).scan(match.scannedTail.orEmpty(), firstIndex).arguments
            } ?: prefix
            return commandResult(contribution.invoke(target, match.index, resolved, scope))
        }
    }

    private fun match(
        route: CommandRouteDefinition,
        input: List<String>,
    ): Match? {
        val arguments = mutableListOf<String>()
        var scannedIndex: Int? = null
        var scannedTail: String? = null
        var cursor = 0
        route.segments.forEachIndexed { segmentIndex, segment ->
            when (segment) {
                is CommandSegmentDefinition.Literal -> {
                    if (input.getOrNull(cursor) !in segment.names) return null
                    cursor++
                }
                is CommandSegmentDefinition.Argument -> {
                    val parameter = route.parameters[segment.parameterIndex]
                    if (cursor == input.size && parameter.optional) return@forEachIndexed
                    if (cursor >= input.size) return null
                    if (parameter.greedy) {
                        arguments += input.drop(cursor).joinToString(" ")
                        cursor = input.size
                    } else {
                        arguments += input[cursor++]
                    }
                    check(segmentIndex == route.segments.lastIndex || !parameter.greedy)
                }
                is CommandSegmentDefinition.ScannedArguments -> {
                    scannedIndex = segment.firstParameterIndex
                    scannedTail = input.drop(cursor).joinToString(" ")
                    cursor = input.size
                }
            }
        }
        return arguments.takeIf { cursor == input.size }?.let {
            Match(-1, route, it, scannedIndex, scannedTail)
        }
    }

    private fun codec(parameter: dev.placeholder.framework.commands.codegen.CommandParameterDefinition):
        CommandArgumentCodec<*> {
        val custom = dependencies.contributions(CommandArgumentCodec::class)
            .singleOrNull { candidate -> candidate.type == parameter.type }
        return custom ?: BuiltinCodecs.find(parameter.type)
            ?: error("No command argument codec for ${parameter.type.qualifiedName}")
    }

    private fun codec(
        type: KClass<*>,
        qualifier: KClass<out Annotation>? = null,
    ): CommandArgumentCodec<*> {
        val custom = dependencies.contributions(CommandArgumentCodec::class)
            .singleOrNull { candidate -> candidate.type == type && candidate.qualifier == qualifier }
        return custom ?: BuiltinCodecs.find(type)
            ?: error("No command argument codec for ${type.qualifiedName}")
    }

    private fun encode(codec: CommandArgumentCodec<*>, value: Any): String {
        @Suppress("UNCHECKED_CAST")
        return (codec as CommandArgumentCodec<Any>).encode(value)
    }

    private data class Match(
        val index: Int,
        val route: CommandRouteDefinition,
        val rawArguments: List<String>,
        val scannedIndex: Int?,
        val scannedTail: String?,
    )
}
