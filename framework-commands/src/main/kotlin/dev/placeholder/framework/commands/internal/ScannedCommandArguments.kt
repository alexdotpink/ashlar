package dev.placeholder.framework.commands.internal

import dev.placeholder.framework.commands.OptionValue
import dev.placeholder.framework.commands.codec.CommandArgumentException
import dev.placeholder.framework.commands.codegen.CommandOptionDefinition
import dev.placeholder.framework.commands.codegen.CommandParameterDefinition
import dev.placeholder.framework.commands.codegen.CommandRouteDefinition
import dev.placeholder.framework.commands.codegen.CommandSetContribution
import dev.placeholder.framework.commands.codegen.MissingCommandArgument
import dev.placeholder.framework.commands.parsing.CommandLineSyntaxException
import dev.placeholder.framework.commands.parsing.NamedOptionDefinition
import dev.placeholder.framework.commands.parsing.NamedOptionKind
import dev.placeholder.framework.commands.parsing.NamedOptionScanner
import dev.placeholder.framework.commands.parsing.ParsedCommandLine
import kotlin.reflect.KClass

internal class ScannedCommandArguments(
    private val routeIndex: Int,
    private val route: CommandRouteDefinition,
    private val binding: CommandSetContribution,
    private val encode: (KClass<*>, KClass<out Annotation>?, Any) -> String = { _, _, value -> value.toString() },
    private val resolve: suspend (KClass<*>, KClass<out Annotation>?, String) -> Any,
) {
    private val scanner = NamedOptionScanner(
        route.parameters.flatMap { parameter ->
            parameter.option?.let(::listOf)
                ?: parameter.options?.members?.map { member -> member.option }
                ?: emptyList()
        }.map(CommandOptionDefinition::scannerDefinition),
    )

    suspend fun scan(raw: String, firstParameterIndex: Int): ScannedArgumentsResult {
        val parsed = try {
            scanner.scan(raw)
        } catch (failure: CommandLineSyntaxException) {
            throw CommandArgumentException(failure.reason)
        }
        val positionals = PositionalCursor(parsed.positionals)
        val resolved = route.parameters.drop(firstParameterIndex).mapIndexed { offset, parameter ->
            val parameterIndex = firstParameterIndex + offset
            when {
                parameter.option != null -> directOption(parameter.option, parsed)
                parameter.options != null -> optionsObject(parameterIndex, parameter, parsed)
                else -> positional(parameter, positionals)
            }
        }
        if (positionals.hasRemaining) {
            throw CommandArgumentException("too many positional arguments")
        }
        return ScannedArgumentsResult(
            arguments = resolved.map(ResolvedArgument::value),
            canonicalArguments = resolved.flatMap(ResolvedArgument::canonicalValues),
        )
    }

    private suspend fun directOption(
        option: CommandOptionDefinition,
        parsed: ParsedCommandLine,
    ): ResolvedArgument {
        val rawValues = parsed.values(option.name)
        if (rawValues.isEmpty()) {
            val value = when {
                option.presenceAware -> OptionValue.Absent
                option.nullable -> null
                else -> throw CommandArgumentException("missing required option '--${option.name}'")
            }
            return ResolvedArgument(value, listOf("option:${option.name}=<absent>"))
        }
        if (option.repeated) {
            val values = rawValues.map { raw -> resolve(option.type, option.qualifier, raw) }
            return ResolvedArgument(
                values,
                values.map { value -> "option:${option.name}=${encode(option.type, option.qualifier, value)}" },
            )
        }
        val value = resolve(option.type, option.qualifier, rawValues.single())
        return ResolvedArgument(
            if (option.presenceAware) OptionValue.Present(value) else value,
            listOf("option:${option.name}=${encode(option.type, option.qualifier, value)}"),
        )
    }

    private suspend fun optionsObject(
        parameterIndex: Int,
        parameter: CommandParameterDefinition,
        parsed: ParsedCommandLine,
    ): ResolvedArgument {
        val definition = requireNotNull(parameter.options)
        val values = binding.optionDefaults(routeIndex, parameterIndex).toMutableList()
        check(values.size == definition.members.size) {
            "Generated defaults for route $routeIndex parameter $parameterIndex do not match its option members"
        }
        definition.members.forEachIndexed { index, member ->
            val option = member.option
            val rawValues = parsed.values(option.name)
            if (rawValues.isEmpty()) return@forEachIndexed
            values[index] = when {
                option.repeated -> rawValues.map { raw -> resolve(option.type, option.qualifier, raw) }
                option.presenceAware -> OptionValue.Present(resolve(option.type, option.qualifier, rawValues.single()))
                else -> resolve(option.type, option.qualifier, rawValues.single())
            }
        }
        return ResolvedArgument(
            binding.constructOptions(routeIndex, parameterIndex, values),
            definition.members.flatMapIndexed { index, member ->
                canonicalOption(member.option, values[index])
            },
        )
    }

    private suspend fun positional(
        parameter: CommandParameterDefinition,
        positionals: PositionalCursor,
    ): ResolvedArgument = when {
        parameter.greedy -> positionals.remainder()
            .takeIf(String::isNotEmpty)
            ?.let { raw -> resolve(parameter.type, parameter.qualifier, raw) }
            ?.let { value -> resolvedPositional(parameter, value) }
            ?: missingResolved(parameter)
        parameter.repeated -> positionals.remaining().map { raw -> resolve(parameter.type, parameter.qualifier, raw) }
            .takeIf(List<*>::isNotEmpty)
            ?.let { values ->
                ResolvedArgument(
                    values,
                    values.map { value ->
                        "argument:${parameter.name}=${encode(parameter.type, parameter.qualifier, value)}"
                    },
                )
            }
            ?: missingResolved(parameter)
        else -> positionals.next()
            ?.let { raw -> resolve(parameter.type, parameter.qualifier, raw) }
            ?.let { value -> resolvedPositional(parameter, value) }
            ?: missingResolved(parameter)
    }

    private fun resolvedPositional(parameter: CommandParameterDefinition, value: Any): ResolvedArgument =
        ResolvedArgument(
            value,
            listOf("argument:${parameter.name}=${encode(parameter.type, parameter.qualifier, value)}"),
        )

    private fun missingResolved(parameter: CommandParameterDefinition): ResolvedArgument =
        ResolvedArgument(missing(parameter), listOf("argument:${parameter.name}=<absent>"))

    private fun canonicalOption(option: CommandOptionDefinition, value: Any?): List<String> = when (value) {
        null, MissingCommandArgument, OptionValue.Absent -> listOf("option:${option.name}=<absent>")
        is OptionValue.Present<*> -> canonicalOption(option, value.value)
        is Iterable<*> -> value.map { item ->
            "option:${option.name}=${item?.let { encode(option.type, option.qualifier, it) } ?: "<null>"}"
        }
        else -> listOf("option:${option.name}=${encode(option.type, option.qualifier, value)}")
    }

    private fun missing(parameter: CommandParameterDefinition): Any? = when {
        parameter.optional -> MissingCommandArgument
        parameter.nullable -> null
        else -> throw CommandArgumentException("missing required argument '${parameter.name}'")
    }
}

internal data class ScannedArgumentsResult(
    val arguments: List<Any?>,
    val canonicalArguments: List<String>,
) : List<Any?> by arguments

private data class ResolvedArgument(
    val value: Any?,
    val canonicalValues: List<String>,
)

private fun CommandOptionDefinition.scannerDefinition(): NamedOptionDefinition = NamedOptionDefinition(
    name = name,
    shortName = shortName,
    kind = if (type == Boolean::class) NamedOptionKind.BOOLEAN else NamedOptionKind.VALUE,
    repeated = repeated,
)

private class PositionalCursor(private val values: List<String>) {
    private var index: Int = 0

    val hasRemaining: Boolean
        get() = index < values.size

    fun next(): String? = values.getOrNull(index)?.also { index++ }

    fun remainder(): String = remaining().joinToString(" ")

    fun remaining(): List<String> = values.subList(index, values.size).also { index = values.size }
}
