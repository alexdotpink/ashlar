package pink.alex.ashlar.config.format

import org.tomlj.Toml
import org.tomlj.TomlArray
import org.tomlj.TomlParseResult
import org.tomlj.TomlTable
import org.tomlj.TomlVersion
import pink.alex.ashlar.config.ConfigDocument
import pink.alex.ashlar.config.ConfigFormat
import pink.alex.ashlar.config.ConfigKeyPath
import pink.alex.ashlar.config.ConfigLimits
import pink.alex.ashlar.config.ConfigParse
import pink.alex.ashlar.config.ConfigProblem
import pink.alex.ashlar.config.ConfigProblemCategory
import pink.alex.ashlar.config.ConfigSource
import pink.alex.ashlar.config.ConfigSourceLocation
import pink.alex.ashlar.config.ConfigValue
import java.nio.charset.StandardCharsets
import java.time.temporal.TemporalAccessor

/** TOML 1.0 with lossless source edits around TOML's validated semantic model. */
internal object TomlConfigFormat : ConfigFormat {
    override val id: String = "toml"
    override val extensions: Set<String> = setOf("toml")
    override val preservesComments: Boolean = true

    override fun parse(source: ConfigSource, limits: ConfigLimits): ConfigParse {
        val bytes = source.text.toByteArray(StandardCharsets.UTF_8).size.toLong()
        if (bytes > limits.maximumBytes) {
            return rejected(source.path, ConfigProblemCategory.RESOURCE_LIMIT, "document exceeds ${limits.maximumBytes} UTF-8 bytes")
        }
        val parsed = Toml.parse(source.text, TomlVersion.V1_0_0)
        if (parsed.hasErrors()) {
            return ConfigParse.Rejected(parsed.errors().map { error ->
                ConfigProblem(
                    path = source.path,
                    category = if (error.message.orEmpty().contains("previously defined", ignoreCase = true)) {
                        ConfigProblemCategory.DUPLICATE_KEY
                    } else {
                        ConfigProblemCategory.SYNTAX
                    },
                    message = error.message ?: "invalid TOML",
                    location = ConfigSourceLocation(error.position().line(), error.position().column()),
                )
            })
        }
        return try {
            val value = TomlValueCodec(limits).root(parsed)
            val index = TomlSourceIndex.scan(source.text)
            ConfigParse.Accepted(LosslessTomlDocument(value, source.text, index))
        } catch (failure: TomlFormatFailure) {
            ConfigParse.Rejected(
                listOf(
                    ConfigProblem(
                        path = source.path,
                        key = failure.path,
                        category = failure.category,
                        message = failure.message,
                        location = parsed.inputPositionOf(failure.path.segments)?.let {
                            ConfigSourceLocation(it.line(), it.column())
                        },
                    ),
                ),
            )
        }
    }

    override fun create(
        value: ConfigValue.ObjectValue,
        comments: Map<ConfigKeyPath, String>,
    ): ConfigDocument {
        require(!value.containsNull()) { "TOML has no null value; nullable configuration properties are not supported" }
        val source = renderTomlDocument(value, comments)
        return LosslessTomlDocument(value, source, TomlSourceIndex.scan(source))
    }

    override fun write(document: ConfigDocument): String {
        require(document is LosslessTomlDocument) { "format '$id' cannot write a '${document.formatId}' document" }
        return document.source
    }

    private fun rejected(path: String, category: ConfigProblemCategory, message: String): ConfigParse.Rejected =
        ConfigParse.Rejected(listOf(ConfigProblem(path, category = category, message = message)))
}

private data class LosslessTomlDocument(
    override val value: ConfigValue.ObjectValue,
    val source: String,
    val index: TomlSourceIndex,
) : ConfigDocument {
    override val formatId: String = "toml"

    override fun location(key: ConfigKeyPath): ConfigSourceLocation? = index.assignments[key]?.location

    override fun patch(
        value: ConfigValue.ObjectValue,
        newComments: Map<ConfigKeyPath, String>,
    ): ConfigDocument {
        if (value == this.value) return this
        require(!value.containsNull()) { "TOML has no null value; nullable configuration properties are not supported" }
        val patcher = TomlSourcePatcher(source, index, this.value, newComments)
        val patched = patcher.patch(value)
        val parsed = TomlConfigFormat.parse(
            ConfigSource("<patched>", patched),
            ConfigLimits(
                maximumBytes = maxOf(1_048_576, patched.toByteArray(StandardCharsets.UTF_8).size.toLong()),
                maximumDepth = 256,
                maximumScalarCharacters = maxOf(262_144, patched.length),
            ),
        )
        check(parsed is ConfigParse.Accepted) { "format produced invalid TOML output" }
        require(parsed.document.value == value) {
            "TOML patch could not represent the requested semantic value without rewriting operator-owned structure"
        }
        return parsed.document
    }
}

private class TomlValueCodec(private val limits: ConfigLimits) {
    fun root(table: TomlTable): ConfigValue.ObjectValue = objectValue(table, 1, ConfigKeyPath(emptyList()))

    private fun objectValue(table: TomlTable, depth: Int, path: ConfigKeyPath): ConfigValue.ObjectValue {
        ensureDepth(depth, path)
        val entries = linkedMapOf<String, ConfigValue>()
        table.keySet().forEach { key ->
            ensureScalar(key, path)
            entries[key] = value(checkNotNull(table.get(listOf(key))), depth + 1, ConfigKeyPath(path.segments + key))
        }
        return ConfigValue.ObjectValue(entries)
    }

    private fun value(value: Any, depth: Int, path: ConfigKeyPath): ConfigValue = when (value) {
        is String -> ConfigValue.StringValue(value.also { ensureScalar(it, path) })
        is Long -> ConfigValue.IntegerValue(value)
        is Double -> {
            if (!value.isFinite()) throw TomlFormatFailure(path, ConfigProblemCategory.UNSUPPORTED_FEATURE, "non-finite numbers are not supported")
            ConfigValue.DecimalValue(value.toString())
        }
        is Boolean -> ConfigValue.BooleanValue(value)
        is TemporalAccessor -> ConfigValue.StringValue(value.toString().also { ensureScalar(it, path) })
        is TomlArray -> {
            ensureDepth(depth, path)
            ConfigValue.ArrayValue((0 until value.size()).map { index ->
                value(value.get(index), depth + 1, ConfigKeyPath(path.segments + index.toString()))
            })
        }
        is TomlTable -> objectValue(value, depth, path)
        else -> throw TomlFormatFailure(path, ConfigProblemCategory.UNSUPPORTED_FEATURE, "unsupported TOML value")
    }

    private fun ensureDepth(depth: Int, path: ConfigKeyPath) {
        if (depth > limits.maximumDepth) {
            throw TomlFormatFailure(path, ConfigProblemCategory.RESOURCE_LIMIT, "nesting exceeds ${limits.maximumDepth}")
        }
    }

    private fun ensureScalar(value: String, path: ConfigKeyPath) {
        if (value.codePointCount(0, value.length) > limits.maximumScalarCharacters) {
            throw TomlFormatFailure(
                path,
                ConfigProblemCategory.RESOURCE_LIMIT,
                "scalar exceeds ${limits.maximumScalarCharacters} characters",
            )
        }
    }
}

private class TomlSourcePatcher(
    private val source: String,
    private val index: TomlSourceIndex,
    private val originalValue: ConfigValue.ObjectValue,
    private val newComments: Map<ConfigKeyPath, String>,
) {
    fun patch(value: ConfigValue.ObjectValue): String {
        index.arrayTables.forEach { path ->
            require(valueAt(originalValue, path) == valueAt(value, path)) {
                "Changing a TOML array of tables is unsupported because its comments cannot be retained safely"
            }
        }
        val leaves = flattenToml(value)
        val edits = mutableListOf<TomlEdit>()
        index.assignments.forEach { (path, assignment) ->
            val replacement = leaves[path]
            if (replacement == null) {
                val comments = tomlComments(source.substring(assignment.keyStart, assignment.valueEnd))
                edits += TomlEdit(
                    assignment.keyStart,
                    assignment.valueEnd,
                    comments.joinToString("\n", postfix = if (comments.isEmpty()) "" else "\n"),
                )
            } else {
                val rendered = renderTomlValue(replacement)
                val current = source.substring(assignment.valueStart, assignment.valueEnd)
                if (rendered != current && replacement != valueAt(originalValue, path)) {
                    val comments = tomlComments(current)
                    val suffix = comments.joinToString(separator = "\n", prefix = if (comments.isEmpty()) "" else "\n")
                    edits += TomlEdit(assignment.valueStart, assignment.valueEnd, rendered + suffix)
                }
            }
        }

        index.tableHeaders.forEach { (path, header) ->
            if (path !in index.arrayTables && valueAt(value, path) !is ConfigValue.ObjectValue) {
                val comments = tomlComments(source.substring(header.first, header.last + 1))
                val replacement = comments.joinToString("\n", postfix = if (comments.isEmpty()) "" else "\n")
                edits += TomlEdit(header.first, header.last + 1, replacement)
            }
        }

        val additions = leaves.filterKeys { it !in index.assignments }
        additions.entries.groupBy { it.key.segments.dropLast(1) }.forEach { (parentSegments, entries) ->
            val parent = ConfigKeyPath(parentSegments)
            val insertion = index.tableEnds[parent] ?: source.length
            val lineBreak = if ("\r\n" in source) "\r\n" else "\n"
            val body = buildString {
                if (insertion > 0 && source[insertion - 1] !in "\r\n") append(lineBreak)
                if (parentSegments.isNotEmpty() && parent !in index.tableEnds) {
                    append(lineBreak)
                    append('[').append(parentSegments.joinToString(".") { quoteTomlKey(it) }).append(']').append(lineBreak)
                }
                entries.forEach { (path, child) ->
                    newComments[path]?.lineSequence()?.forEach { append("# ").append(it).append(lineBreak) }
                    append(quoteTomlKey(path.segments.last())).append(" = ").append(renderTomlValue(child)).append(lineBreak)
                }
            }
            edits += TomlEdit(insertion, insertion, body)
        }
        return applyTomlEdits(source, edits)
    }
}

private data class TomlSourceIndex(
    val assignments: Map<ConfigKeyPath, TomlAssignment>,
    val tableEnds: Map<ConfigKeyPath, Int>,
    val tableHeaders: Map<ConfigKeyPath, IntRange>,
    val arrayTables: Set<ConfigKeyPath>,
) {
    companion object {
        fun scan(source: String): TomlSourceIndex {
            val assignments = linkedMapOf<ConfigKeyPath, TomlAssignment>()
            val tableStarts = linkedMapOf<ConfigKeyPath, Int>()
            val tableEnds = linkedMapOf<ConfigKeyPath, Int>()
            val tableHeaders = linkedMapOf<ConfigKeyPath, IntRange>()
            val arrayTables = linkedSetOf<ConfigKeyPath>()
            var currentTable = ConfigKeyPath(emptyList())
            tableStarts[currentTable] = 0
            var offset = 0
            while (offset < source.length) {
                val lineStart = offset
                val lineEnd = source.indexOf('\n', offset).let { if (it == -1) source.length else it }
                var cursor = lineStart
                while (cursor < lineEnd && source[cursor].isWhitespace()) cursor++
                if (cursor == lineEnd || source[cursor] == '#') {
                    offset = if (lineEnd < source.length) lineEnd + 1 else source.length
                    continue
                }
                if (source[cursor] == '[') {
                    val arrayTable = cursor + 1 < lineEnd && source[cursor + 1] == '['
                    val closing = if (arrayTable) source.indexOf("]]", cursor + 2) else source.indexOf(']', cursor + 1)
                    if (closing in (cursor + 1)..lineEnd) {
                        val raw = source.substring(cursor + if (arrayTable) 2 else 1, closing)
                        val segments = runCatching { Toml.parseDottedKey(raw) }.getOrElse { emptyList() }
                        tableEnds[currentTable] = lineStart
                        currentTable = ConfigKeyPath(segments)
                        tableStarts[currentTable] = if (lineEnd < source.length) lineEnd + 1 else lineEnd
                        tableHeaders[currentTable] = lineStart until if (lineEnd < source.length) lineEnd + 1 else lineEnd
                        if (arrayTable) arrayTables += currentTable
                    }
                    offset = if (lineEnd < source.length) lineEnd + 1 else source.length
                    continue
                }

                val equals = findTomlEquals(source, cursor, lineEnd)
                if (equals == -1) {
                    offset = if (lineEnd < source.length) lineEnd + 1 else source.length
                    continue
                }
                val rawKey = source.substring(cursor, equals).trim()
                val key = runCatching { Toml.parseDottedKey(rawKey) }.getOrElse { emptyList() }
                var valueStart = equals + 1
                while (valueStart < source.length && source[valueStart] in " \t") valueStart++
                val valueEnd = scanTomlValueEnd(source, valueStart)
                val path = ConfigKeyPath(currentTable.segments + key)
                val location = sourceLocation(source, cursor)
                assignments[path] = TomlAssignment(cursor, valueStart, valueEnd, location)
                offset = source.indexOf('\n', valueEnd).let { if (it == -1) source.length else it + 1 }
            }
            tableEnds[currentTable] = source.length
            tableStarts.keys.forEach { table -> tableEnds.putIfAbsent(table, source.length) }
            return TomlSourceIndex(assignments, tableEnds, tableHeaders, arrayTables)
        }
    }
}

private data class TomlAssignment(
    val keyStart: Int,
    val valueStart: Int,
    val valueEnd: Int,
    val location: ConfigSourceLocation,
)

private data class TomlEdit(val start: Int, val end: Int, val text: String)

private fun findTomlEquals(source: String, start: Int, end: Int): Int {
    var quote: Char? = null
    var escaped = false
    for (index in start until end) {
        val character = source[index]
        if (quote != null) {
            if (quote == '"' && character == '\\' && !escaped) escaped = true
            else {
                if (character == quote && !escaped) quote = null
                escaped = false
            }
        } else when (character) {
            '"', '\'' -> quote = character
            '=' -> return index
        }
    }
    return -1
}

private fun scanTomlValueEnd(source: String, start: Int): Int {
    var offset = start
    var quote: Char? = null
    var triple = false
    var escaped = false
    var square = 0
    var curly = 0
    while (offset < source.length) {
        val character = source[offset]
        if (quote != null) {
            if (triple && source.startsWith("$quote$quote$quote", offset)) {
                offset += 3
                quote = null
                triple = false
                continue
            }
            if (!triple && character == quote && !escaped) quote = null
            if (quote == '"' && character == '\\' && !escaped) escaped = true else escaped = false
            offset++
            continue
        }
        when (character) {
            '"', '\'' -> {
                quote = character
                triple = offset + 2 < source.length && source[offset + 1] == character && source[offset + 2] == character
                offset += if (triple) 3 else 1
            }
            '[' -> { square++; offset++ }
            ']' -> { square--; offset++ }
            '{' -> { curly++; offset++ }
            '}' -> { curly--; offset++ }
            '#' -> if (square == 0 && curly == 0) return trimTomlEnd(source, start, offset) else {
                val newline = source.indexOf('\n', offset)
                offset = if (newline == -1) source.length else newline + 1
            }
            '\n', '\r' -> if (square == 0 && curly == 0) return trimTomlEnd(source, start, offset) else offset++
            else -> offset++
        }
    }
    return trimTomlEnd(source, start, offset)
}

private fun trimTomlEnd(source: String, start: Int, end: Int): Int {
    var result = end
    while (result > start && source[result - 1] in " \t") result--
    return result
}

private fun applyTomlEdits(source: String, edits: List<TomlEdit>): String {
    val result = StringBuilder(source)
    edits.sortedWith(compareByDescending<TomlEdit> { it.start }.thenByDescending { it.end }).forEach { edit ->
        result.replace(edit.start, edit.end, edit.text)
    }
    return result.toString()
}

private fun flattenToml(root: ConfigValue.ObjectValue): Map<ConfigKeyPath, ConfigValue> = buildMap {
    fun visit(value: ConfigValue.ObjectValue, path: ConfigKeyPath) {
        value.entries.forEach { (key, child) ->
            val childPath = ConfigKeyPath(path.segments + key)
            if (child is ConfigValue.ObjectValue) visit(child, childPath) else put(childPath, child)
        }
    }
    visit(root, ConfigKeyPath(emptyList()))
}

private fun valueAt(root: ConfigValue.ObjectValue, path: ConfigKeyPath): ConfigValue? {
    var current: ConfigValue = root
    path.segments.forEach { segment ->
        current = (current as? ConfigValue.ObjectValue)?.entries?.get(segment) ?: return null
    }
    return current
}

private fun tomlComments(fragment: String): List<String> = buildList {
    var offset = 0
    var quote: Char? = null
    var triple = false
    var escaped = false
    while (offset < fragment.length) {
        val character = fragment[offset]
        if (quote != null) {
            if (triple && fragment.startsWith("$quote$quote$quote", offset)) {
                offset += 3
                quote = null
                triple = false
                escaped = false
                continue
            }
            if (!triple && character == quote && !escaped) quote = null
            if (quote == '"' && character == '\\' && !escaped) escaped = true else escaped = false
            offset++
            continue
        }
        when (character) {
            '"', '\'' -> {
                quote = character
                triple = offset + 2 < fragment.length &&
                    fragment[offset + 1] == character && fragment[offset + 2] == character
                offset += if (triple) 3 else 1
            }
            '#' -> {
                val end = fragment.indexOfAny(charArrayOf('\r', '\n'), offset).let {
                    if (it == -1) fragment.length else it
                }
                add(fragment.substring(offset, end))
                offset = end
            }
            else -> offset++
        }
    }
}

private fun renderTomlDocument(
    root: ConfigValue.ObjectValue,
    comments: Map<ConfigKeyPath, String>,
): String = buildString {
    val lineBreak = "\n"
    comments[ConfigKeyPath(emptyList())]?.lineSequence()?.forEach { append("# ").append(it).append(lineBreak) }
    fun renderTable(value: ConfigValue.ObjectValue, path: ConfigKeyPath, header: Boolean) {
        if (header) {
            if (isNotEmpty() && !endsWith("\n\n")) append(lineBreak)
            append('[').append(path.segments.joinToString(".") { quoteTomlKey(it) }).append(']').append(lineBreak)
        }
        value.entries.filterValues { it !is ConfigValue.ObjectValue }.forEach { (key, child) ->
            val childPath = ConfigKeyPath(path.segments + key)
            comments[childPath]?.lineSequence()?.forEach { append("# ").append(it).append(lineBreak) }
            append(quoteTomlKey(key)).append(" = ").append(renderTomlValue(child)).append(lineBreak)
        }
        value.entries.filterValues { it is ConfigValue.ObjectValue }.forEach { (key, child) ->
            renderTable(child as ConfigValue.ObjectValue, ConfigKeyPath(path.segments + key), true)
        }
    }
    renderTable(root, ConfigKeyPath(emptyList()), false)
}

private fun renderTomlValue(value: ConfigValue): String = when (value) {
    ConfigValue.NullValue -> throw IllegalArgumentException("TOML has no null value")
    is ConfigValue.BooleanValue -> value.value.toString()
    is ConfigValue.StringValue -> buildString {
        append('"')
        value.value.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\t' -> append("\\t")
                '\n' -> append("\\n")
                '\u000C' -> append("\\f")
                '\r' -> append("\\r")
                else -> if (character.code < 0x20) append("\\u%04X".format(character.code)) else append(character)
            }
        }
        append('"')
    }
    is ConfigValue.IntegerValue -> value.value.toString()
    is ConfigValue.DecimalValue -> value.value
    is ConfigValue.ArrayValue -> value.values.joinToString(prefix = "[", postfix = "]") { renderTomlValue(it) }
    is ConfigValue.ObjectValue -> value.entries.entries.joinToString(prefix = "{ ", postfix = " }") { (key, child) ->
        "${quoteTomlKey(key)} = ${renderTomlValue(child)}"
    }
}

private fun quoteTomlKey(key: String): String = if (BARE_TOML_KEY.matches(key)) key else renderTomlValue(ConfigValue.StringValue(key))

private fun ConfigValue.containsNull(): Boolean = when (this) {
    ConfigValue.NullValue -> true
    is ConfigValue.ArrayValue -> values.any(ConfigValue::containsNull)
    is ConfigValue.ObjectValue -> entries.values.any(ConfigValue::containsNull)
    else -> false
}

private fun sourceLocation(source: String, offset: Int): ConfigSourceLocation {
    val line = source.take(offset).count { it == '\n' } + 1
    val lineStart = source.lastIndexOf('\n', offset - 1).let { if (it == -1) 0 else it + 1 }
    return ConfigSourceLocation(line, offset - lineStart + 1)
}

private class TomlFormatFailure(
    val path: ConfigKeyPath,
    val category: ConfigProblemCategory,
    override val message: String,
) : RuntimeException(message)

private val BARE_TOML_KEY = Regex("[A-Za-z0-9_-]+")
