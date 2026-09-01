package pink.alex.ashlar.commands.parsing

/** The raw value shape accepted by a named option. */
public enum class NamedOptionKind {
    VALUE,
    BOOLEAN,
}

/** Runtime metadata for one generated named option. */
public data class NamedOptionDefinition(
    public val name: String,
    public val shortName: Char? = null,
    public val kind: NamedOptionKind = NamedOptionKind.VALUE,
    public val repeated: Boolean = false,
) {
    init {
        require(name.isNotBlank()) { "Option names cannot be blank" }
        require(name.none(Char::isWhitespace) && !name.startsWith('-') && '=' !in name) {
            "Invalid option name '$name'"
        }
        require(shortName == null || (!shortName.isWhitespace() && shortName != '-')) {
            "Invalid short option '$shortName'"
        }
    }
}

/** Positionals and decoded raw option values from one command line. */
public class ParsedCommandLine internal constructor(
    public val positionals: List<String>,
    optionValues: Map<String, List<String>>,
) {
    public val options: Map<String, List<String>> = optionValues.mapValues { (_, values) -> values.toList() }

    public fun value(name: String): String? = options[name]?.singleOrNull()

    public fun values(name: String): List<String> = options[name].orEmpty()

    public fun boolean(name: String): Boolean? = value(name)?.toBooleanStrictOrNull()

    /** The decoded positional remainder used by greedy terminal arguments. */
    public fun remainder(startIndex: Int = 0): String = positionals.drop(startIndex).joinToString(" ")
}

/** Extracts known Unix-style options while leaving positional order intact. */
public class NamedOptionScanner(definitions: Iterable<NamedOptionDefinition>) {
    private val byName: Map<String, NamedOptionDefinition>
    private val byShortName: Map<Char, NamedOptionDefinition>

    init {
        val options = definitions.toList()
        byName = uniqueIndex(options, NamedOptionDefinition::name, "long")
        byShortName = uniqueIndex(options.filter { it.shortName != null }, { it.shortName!! }, "short")
    }

    public fun scan(input: String): ParsedCommandLine = scan(CommandLineTokenizer.tokenize(input))

    public fun scan(tokens: List<CommandToken>): ParsedCommandLine {
        val positionals = mutableListOf<String>()
        val values = linkedMapOf<String, MutableList<String>>()
        var optionsEnabled = true
        var cursor = 0

        while (cursor < tokens.size) {
            val token = tokens[cursor]
            if (!optionsEnabled || token.wasQuotedOrEscaped) {
                positionals += token.value
                cursor++
                continue
            }

            when {
                token.value == "--" -> {
                    optionsEnabled = false
                    cursor++
                }
                token.value.startsWith("--") -> {
                    val consumed = scanLong(token, tokens.getOrNull(cursor + 1), values)
                    cursor += if (consumed) 2 else 1
                }
                token.value.startsWith('-') && token.value.length > 1 && !token.value[1].isDigit() -> {
                    val consumed = scanShort(token, tokens.getOrNull(cursor + 1), values)
                    cursor += if (consumed) 2 else 1
                }
                else -> {
                    positionals += token.value
                    cursor++
                }
            }
        }

        return ParsedCommandLine(positionals.toList(), values)
    }

    private fun scanLong(
        token: CommandToken,
        next: CommandToken?,
        values: MutableMap<String, MutableList<String>>,
    ): Boolean {
        val body = token.value.removePrefix("--")
        val name = body.substringBefore('=')
        val attached = body.substringAfter('=', missingDelimiterValue = "").takeIf { '=' in body }
        val direct = byName[name]
        val negated = direct == null && name.startsWith("no-")
        val definition = direct ?: name.removePrefix("no-").takeIf { negated }?.let(byName::get)
            ?: syntax("unknown option '--$name'", token)

        if (negated && definition.kind != NamedOptionKind.BOOLEAN) {
            syntax("option '--${definition.name}' cannot be negated", token)
        }

        return when (definition.kind) {
            NamedOptionKind.BOOLEAN -> {
                if (negated && attached != null) syntax("negated option cannot have a value", token)
                val value = when {
                    negated -> "false"
                    attached == null -> "true"
                    else -> attached.toBooleanStrictOrNull()
                        ?.toString()
                        ?: syntax("option '--${definition.name}' expects true or false", token)
                }
                add(definition, value, values, token)
                false
            }
            NamedOptionKind.VALUE -> {
                val value = attached ?: requiredValue(definition, next, token)
                add(definition, value, values, token)
                attached == null
            }
        }
    }

    private fun scanShort(
        token: CommandToken,
        next: CommandToken?,
        values: MutableMap<String, MutableList<String>>,
    ): Boolean {
        if (token.value.length != 2) {
            syntax("bundled short options are not supported", token)
        }
        val shortName = token.value[1]
        val definition = byShortName[shortName] ?: syntax("unknown option '-$shortName'", token)
        val value = when (definition.kind) {
            NamedOptionKind.BOOLEAN -> "true"
            NamedOptionKind.VALUE -> requiredValue(definition, next, token)
        }
        add(definition, value, values, token)
        return definition.kind == NamedOptionKind.VALUE
    }

    private fun requiredValue(
        definition: NamedOptionDefinition,
        next: CommandToken?,
        optionToken: CommandToken,
    ): String {
        if (next == null || (!next.wasQuotedOrEscaped && next.value.looksLikeOption())) {
            syntax("option '--${definition.name}' requires a value", optionToken)
        }
        return next.value
    }

    private fun String.looksLikeOption(): Boolean =
        startsWith("--") || (length > 1 && startsWith('-') && !this[1].isDigit())

    private fun add(
        definition: NamedOptionDefinition,
        value: String,
        values: MutableMap<String, MutableList<String>>,
        token: CommandToken,
    ) {
        val existing = values[definition.name]
        if (existing != null && !definition.repeated) {
            syntax("option '--${definition.name}' cannot be repeated", token)
        }
        values.getOrPut(definition.name, ::mutableListOf) += value
    }

    private fun syntax(reason: String, token: CommandToken): Nothing =
        throw CommandLineSyntaxException(reason, token.startIndex)

    private companion object {
        private fun <K> uniqueIndex(
            definitions: List<NamedOptionDefinition>,
            key: (NamedOptionDefinition) -> K,
            label: String,
        ): Map<K, NamedOptionDefinition> {
            val result = linkedMapOf<K, NamedOptionDefinition>()
            definitions.forEach { definition ->
                val optionKey = key(definition)
                require(result.putIfAbsent(optionKey, definition) == null) {
                    "Duplicate $label option '$optionKey'"
                }
            }
            return result
        }
    }
}
