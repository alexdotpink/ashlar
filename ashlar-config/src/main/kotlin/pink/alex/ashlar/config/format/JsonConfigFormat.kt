package pink.alex.ashlar.config.format

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

/** Strict RFC 8259 JSON. */
internal object JsonConfigFormat : ConfigFormat {
    override val id: String = "json"
    override val extensions: Set<String> = setOf("json")
    override val preservesComments: Boolean = false

    override fun parse(source: ConfigSource, limits: ConfigLimits): ConfigParse =
        JsonParser.parse(source, limits, comments = false, formatId = id)

    override fun create(
        value: ConfigValue.ObjectValue,
        comments: Map<ConfigKeyPath, String>,
    ): ConfigDocument = createJsonDocument(id, value, emptyMap(), commentsAllowed = false)

    override fun write(document: ConfigDocument): String = requireJsonDocument(document, id).source
}

/** JSON with line and block comments. Comments remain byte-for-byte intact around semantic edits. */
internal object JsoncConfigFormat : ConfigFormat {
    override val id: String = "jsonc"
    override val extensions: Set<String> = setOf("jsonc")
    override val preservesComments: Boolean = true

    override fun parse(source: ConfigSource, limits: ConfigLimits): ConfigParse =
        JsonParser.parse(source, limits, comments = true, formatId = id)

    override fun create(
        value: ConfigValue.ObjectValue,
        comments: Map<ConfigKeyPath, String>,
    ): ConfigDocument = createJsonDocument(id, value, comments, commentsAllowed = true)

    override fun write(document: ConfigDocument): String = requireJsonDocument(document, id).source
}

private fun createJsonDocument(
    formatId: String,
    value: ConfigValue.ObjectValue,
    comments: Map<ConfigKeyPath, String>,
    commentsAllowed: Boolean,
): ConfigDocument {
    val rendered = renderFreshJson(value, comments, commentsAllowed, ConfigKeyPath(emptyList()), 0) + "\n"
    val parsed = JsonParser.parse(ConfigSource("<created>", rendered), ConfigLimits(), commentsAllowed, formatId)
    check(parsed is ConfigParse.Accepted) { "format produced invalid $formatId output" }
    return parsed.document
}

private fun requireJsonDocument(document: ConfigDocument, formatId: String): LosslessJsonDocument {
    require(document is LosslessJsonDocument && document.formatId == formatId) {
        "format '$formatId' cannot write a '${document.formatId}' document"
    }
    return document
}

private data class LosslessJsonDocument(
    override val formatId: String,
    override val value: ConfigValue.ObjectValue,
    val source: String,
    val root: JsonObjectNode,
    val comments: Boolean,
) : ConfigDocument {
    override fun location(key: ConfigKeyPath): ConfigSourceLocation? {
        var node: JsonNode = root
        key.segments.forEachIndexed { index, segment ->
            node = when (node) {
                is JsonObjectNode -> node.members.firstOrNull { it.key == segment }?.let {
                    if (index == key.segments.lastIndex) {
                        return jsonLocation(source, it.keyStart)
                    }
                    it.valueNode
                } ?: return null
                is JsonArrayNode -> node.elements.getOrNull(segment.toIntOrNull() ?: return null) ?: return null
                is JsonScalarNode -> return null
            }
        }
        return null
    }

    override fun patch(
        value: ConfigValue.ObjectValue,
        newComments: Map<ConfigKeyPath, String>,
    ): ConfigDocument {
        if (value == this.value) return this
        val patched = JsonPatcher(source, comments, newComments).patch(root, value)
        val reparsed = JsonParser.parse(
            ConfigSource("<patched>", patched),
            ConfigLimits(
                maximumBytes = maxOf(1_048_576, patched.toByteArray(StandardCharsets.UTF_8).size.toLong()),
                maximumDepth = 256,
                maximumScalarCharacters = maxOf(262_144, patched.length),
                maximumAliases = 0,
            ),
            comments,
            formatId,
        )
        check(reparsed is ConfigParse.Accepted) { "format produced invalid $formatId output" }
        return reparsed.document
    }
}

private sealed interface JsonNode {
    val start: Int
    val end: Int
    val value: ConfigValue
}

private data class JsonObjectNode(
    override val start: Int,
    override val end: Int,
    val openEnd: Int,
    val closeStart: Int,
    val members: List<JsonMember>,
    override val value: ConfigValue.ObjectValue,
) : JsonNode

private data class JsonArrayNode(
    override val start: Int,
    override val end: Int,
    val elements: List<JsonNode>,
    override val value: ConfigValue.ArrayValue,
) : JsonNode

private data class JsonScalarNode(
    override val start: Int,
    override val end: Int,
    override val value: ConfigValue,
) : JsonNode

private data class JsonMember(
    val key: String,
    val keyStart: Int,
    val valueNode: JsonNode,
    val commaAfter: Int?,
)

private class JsonPatcher(
    private val source: String,
    private val commentsAllowed: Boolean,
    private val newComments: Map<ConfigKeyPath, String>,
) {
    fun patch(root: JsonObjectNode, value: ConfigValue.ObjectValue): String =
        source.substring(0, root.start) +
            patchNode(root, value, ConfigKeyPath(emptyList())) +
            source.substring(root.end)

    private fun patchNode(node: JsonNode, replacement: ConfigValue, path: ConfigKeyPath): String {
        if (node.value == replacement) return source.substring(node.start, node.end)
        return when {
            node is JsonObjectNode && replacement is ConfigValue.ObjectValue -> patchObject(node, replacement, path)
            node is JsonArrayNode && replacement is ConfigValue.ArrayValue &&
                node.elements.size == replacement.values.size -> patchArray(node, replacement, path)
            else -> retainedValueComments(node) + renderJson(replacement)
        }
    }

    private fun patchObject(
        node: JsonObjectNode,
        replacement: ConfigValue.ObjectValue,
        path: ConfigKeyPath,
    ): String {
        val edits = mutableListOf<TextEdit>()
        val replacementKeys = replacement.entries.keys
        val kept = node.members.filter { it.key in replacementKeys }

        node.members.forEachIndexed { index, member ->
            val next = replacement.entries[member.key]
            if (next != null) {
                val childPath = ConfigKeyPath(path.segments + member.key)
                val rendered = patchNode(member.valueNode, next, childPath)
                if (rendered != source.substring(member.valueNode.start, member.valueNode.end)) {
                    edits += TextEdit(member.valueNode.start, member.valueNode.end, rendered)
                }
            } else {
                edits += removalEdits(node.members, index)
            }
        }

        val additions = replacement.entries.filterKeys { key -> node.members.none { it.key == key } }
        if (additions.isNotEmpty()) {
            val insertion = insertion(node, kept, additions, path)
            edits += TextEdit(insertion.first, insertion.first, insertion.second)
        }

        return applyEdits(node.start, node.end, edits)
    }

    private fun patchArray(
        node: JsonArrayNode,
        replacement: ConfigValue.ArrayValue,
        path: ConfigKeyPath,
    ): String {
        val edits = node.elements.mapIndexedNotNull { index, child ->
            val rendered = patchNode(child, replacement.values[index], ConfigKeyPath(path.segments + index.toString()))
            rendered.takeIf { it != source.substring(child.start, child.end) }
                ?.let { TextEdit(child.start, child.end, it) }
        }
        return applyEdits(node.start, node.end, edits)
    }

    private fun removalEdits(members: List<JsonMember>, index: Int): List<TextEdit> {
        val member = members[index]
        return when {
            member.commaAfter != null -> listOf(
                TextEdit(
                    member.keyStart,
                    member.commaAfter + 1,
                    retainedComments(member.keyStart, member.commaAfter + 1),
                ),
            )
            index > 0 && members[index - 1].commaAfter != null -> listOf(
                TextEdit(members[index - 1].commaAfter!!, members[index - 1].commaAfter!! + 1, ""),
                TextEdit(
                    member.keyStart,
                    member.valueNode.end,
                    retainedComments(member.keyStart, member.valueNode.end),
                ),
            )
            else -> listOf(
                TextEdit(member.keyStart, member.valueNode.end, retainedComments(member.keyStart, member.valueNode.end)),
            )
        }
    }

    private fun insertion(
        node: JsonObjectNode,
        kept: List<JsonMember>,
        additions: Map<String, ConfigValue>,
        path: ConfigKeyPath,
    ): Pair<Int, String> {
        val multiline = source.substring(node.openEnd, node.closeStart).contains('\n')
        val baseIndent = lineIndent(node.start)
        val childIndent = if (node.members.isNotEmpty()) {
            lineIndent(node.members.first().keyStart)
        } else {
            "$baseIndent  "
        }
        val insertionAt = kept.lastOrNull()?.valueNode?.end ?: node.openEnd
        val prefix = if (kept.isNotEmpty()) "," else ""
        val separator = if (multiline || node.members.isEmpty()) "\n" else " "
        val rendered = additions.entries.joinToString(if (multiline || node.members.isEmpty()) ",\n" else ", ") { (key, value) ->
            val comment = if (commentsAllowed) renderComment(newComments[ConfigKeyPath(path.segments + key)], childIndent) else ""
            "$comment$childIndent${quoteJson(key)}: ${renderJson(value, childIndent.length / 2 + 1)}"
        }
        return insertionAt to (prefix + separator + rendered)
    }

    private fun renderComment(comment: String?, indent: String): String = comment
        ?.lineSequence()
        ?.joinToString(separator = "\n", postfix = "\n") { "$indent// $it" }
        .orEmpty()

    private fun retainedComments(start: Int, end: Int): String {
        if (!commentsAllowed) return ""
        val matches = jsonComments(source.substring(start, end))
        return if (matches.isEmpty()) "" else matches.joinToString("\n", postfix = "\n")
    }

    private fun retainedValueComments(node: JsonNode): String {
        if (!commentsAllowed) return ""
        val comments = jsonComments(source.substring(node.start, node.end))
        return comments.joinToString(separator = "\n", postfix = if (comments.isEmpty()) "" else "\n")
    }

    private fun lineIndent(position: Int): String {
        val lineStart = source.lastIndexOf('\n', position - 1).let { if (it == -1) 0 else it + 1 }
        return source.substring(lineStart, position).takeWhile { it == ' ' || it == '\t' }
    }

    private fun applyEdits(start: Int, end: Int, edits: List<TextEdit>): String {
        if (edits.isEmpty()) return source.substring(start, end)
        val result = StringBuilder(source.substring(start, end))
        edits.sortedByDescending(TextEdit::start).forEach { edit ->
            result.replace(edit.start - start, edit.end - start, edit.text)
        }
        return result.toString()
    }

    private data class TextEdit(val start: Int, val end: Int, val text: String)
}

private object JsonParser {
    fun parse(
        source: ConfigSource,
        limits: ConfigLimits,
        comments: Boolean,
        formatId: String,
    ): ConfigParse {
        val bytes = source.text.toByteArray(StandardCharsets.UTF_8).size.toLong()
        if (bytes > limits.maximumBytes) {
            return rejected(source, ConfigProblemCategory.RESOURCE_LIMIT, "document exceeds ${limits.maximumBytes} UTF-8 bytes")
        }
        return try {
            val parser = Parser(source, limits, comments)
            val root = parser.parseDocument()
            ConfigParse.Accepted(LosslessJsonDocument(formatId, root.value, source.text, root, comments))
        } catch (failure: LexFailure) {
            ConfigParse.Rejected(
                listOf(
                    ConfigProblem(
                        path = source.path,
                        category = failure.category,
                        message = failure.message,
                        location = ConfigSourceLocation(failure.line, failure.column),
                    ),
                ),
            )
        } catch (failure: JsonFailure) {
            ConfigParse.Rejected(
                listOf(
                    ConfigProblem(
                        path = source.path,
                        key = failure.key,
                        category = failure.category,
                        message = failure.message,
                        location = ConfigSourceLocation(failure.line, failure.column),
                    ),
                ),
            )
        }
    }

    private fun rejected(source: ConfigSource, category: ConfigProblemCategory, message: String) =
        ConfigParse.Rejected(listOf(ConfigProblem(source.path, category = category, message = message)))

    private class Parser(
        private val source: ConfigSource,
        private val limits: ConfigLimits,
        private val comments: Boolean,
    ) {
        private val lexer = JsonLexer(source.text, comments)
        private var token: JsonToken = lexer.next()

        fun parseDocument(): JsonObjectNode {
            if (token.kind != TokenKind.LEFT_BRACE) fail("configuration root must be an object")
            val root = parseObject(1, ConfigKeyPath(emptyList()))
            if (token.kind != TokenKind.EOF) fail("unexpected content after root object")
            return root
        }

        private fun parseValue(depth: Int, path: ConfigKeyPath): JsonNode {
            return when (token.kind) {
                TokenKind.LEFT_BRACE -> {
                    if (depth > limits.maximumDepth) fail("nesting exceeds ${limits.maximumDepth}", ConfigProblemCategory.RESOURCE_LIMIT, path)
                    parseObject(depth, path)
                }
                TokenKind.LEFT_BRACKET -> {
                    if (depth > limits.maximumDepth) fail("nesting exceeds ${limits.maximumDepth}", ConfigProblemCategory.RESOURCE_LIMIT, path)
                    parseArray(depth, path)
                }
                TokenKind.STRING -> scalar(ConfigValue.StringValue(token.decoded!!))
                TokenKind.TRUE -> scalar(ConfigValue.BooleanValue(true))
                TokenKind.FALSE -> scalar(ConfigValue.BooleanValue(false))
                TokenKind.NULL -> scalar(ConfigValue.NullValue)
                TokenKind.NUMBER -> {
                    val number = token.lexeme
                    val value = if ('.' in number || 'e' in number.lowercase()) {
                        ConfigValue.DecimalValue(number)
                    } else {
                        number.toLongOrNull()?.let(ConfigValue::IntegerValue) ?: ConfigValue.DecimalValue(number)
                    }
                    scalar(value)
                }
                else -> fail("expected a JSON value", path = path)
            }
        }

        private fun scalar(value: ConfigValue): JsonScalarNode {
            val current = token
            val characters = when (value) {
                is ConfigValue.StringValue -> value.value.codePointCount(0, value.value.length)
                else -> current.lexeme.length
            }
            if (characters > limits.maximumScalarCharacters) {
                fail("scalar exceeds ${limits.maximumScalarCharacters} characters", ConfigProblemCategory.RESOURCE_LIMIT)
            }
            advance()
            return JsonScalarNode(current.start, current.end, value)
        }

        private fun parseObject(depth: Int, path: ConfigKeyPath): JsonObjectNode {
            val open = expect(TokenKind.LEFT_BRACE)
            val entries = linkedMapOf<String, ConfigValue>()
            val members = mutableListOf<JsonMember>()
            if (token.kind != TokenKind.RIGHT_BRACE) {
                while (true) {
                    val keyToken = expect(TokenKind.STRING, "expected a quoted object key")
                    val key = keyToken.decoded!!
                    if (key.codePointCount(0, key.length) > limits.maximumScalarCharacters) {
                        failAt(keyToken, "scalar exceeds ${limits.maximumScalarCharacters} characters", ConfigProblemCategory.RESOURCE_LIMIT)
                    }
                    if (key in entries) failAt(keyToken, "duplicate key '$key'", ConfigProblemCategory.DUPLICATE_KEY, ConfigKeyPath(path.segments + key))
                    expect(TokenKind.COLON, "expected ':' after object key")
                    val valueNode = parseValue(depth + 1, ConfigKeyPath(path.segments + key))
                    entries[key] = valueNode.value
                    var comma: Int? = null
                    if (token.kind == TokenKind.COMMA) {
                        comma = token.start
                        advance()
                        if (token.kind == TokenKind.RIGHT_BRACE) fail("trailing commas are not supported")
                    } else if (token.kind != TokenKind.RIGHT_BRACE) {
                        fail("expected ',' or '}' after object member")
                    }
                    members += JsonMember(key, keyToken.start, valueNode, comma)
                    if (comma == null) break
                }
            }
            val close = expect(TokenKind.RIGHT_BRACE)
            return JsonObjectNode(open.start, close.end, open.end, close.start, members, ConfigValue.ObjectValue(entries))
        }

        private fun parseArray(depth: Int, path: ConfigKeyPath): JsonArrayNode {
            val open = expect(TokenKind.LEFT_BRACKET)
            val values = mutableListOf<JsonValueNode>()
            if (token.kind != TokenKind.RIGHT_BRACKET) {
                var index = 0
                while (true) {
                    values += JsonValueNode(parseValue(depth + 1, ConfigKeyPath(path.segments + index.toString())))
                    index++
                    if (token.kind == TokenKind.COMMA) {
                        advance()
                        if (token.kind == TokenKind.RIGHT_BRACKET) fail("trailing commas are not supported")
                    } else if (token.kind != TokenKind.RIGHT_BRACKET) {
                        fail("expected ',' or ']' after array element")
                    } else break
                }
            }
            val close = expect(TokenKind.RIGHT_BRACKET)
            val nodes = values.map(JsonValueNode::node)
            return JsonArrayNode(open.start, close.end, nodes, ConfigValue.ArrayValue(nodes.map(JsonNode::value)))
        }

        private fun expect(kind: TokenKind, message: String = "expected $kind"): JsonToken {
            if (token.kind != kind) fail(message)
            return token.also { advance() }
        }

        private fun advance() {
            token = try {
                lexer.next()
            } catch (failure: LexFailure) {
                throw JsonFailure(failure.message, failure.line, failure.column, failure.category)
            }
        }

        private fun fail(
            message: String,
            category: ConfigProblemCategory = ConfigProblemCategory.SYNTAX,
            path: ConfigKeyPath = ConfigKeyPath(emptyList()),
        ): Nothing = throw JsonFailure(message, token.line, token.column, category, path)

        private fun failAt(
            token: JsonToken,
            message: String,
            category: ConfigProblemCategory,
            path: ConfigKeyPath = ConfigKeyPath(emptyList()),
        ): Nothing = throw JsonFailure(message, token.line, token.column, category, path)

        private data class JsonValueNode(val node: JsonNode)
    }
}

private class JsonLexer(
    private val source: String,
    private val comments: Boolean,
) {
    private var offset = 0
    private var line = 1
    private var column = 1

    fun next(): JsonToken {
        skipTrivia()
        if (offset == source.length) return token(TokenKind.EOF, offset, offset, line, column)
        val start = offset
        val startLine = line
        val startColumn = column
        return when (val character = source[offset]) {
            '{' -> simple(TokenKind.LEFT_BRACE)
            '}' -> simple(TokenKind.RIGHT_BRACE)
            '[' -> simple(TokenKind.LEFT_BRACKET)
            ']' -> simple(TokenKind.RIGHT_BRACKET)
            ':' -> simple(TokenKind.COLON)
            ',' -> simple(TokenKind.COMMA)
            '"' -> string(start, startLine, startColumn)
            '-', in '0'..'9' -> number(start, startLine, startColumn)
            't' -> keyword("true", TokenKind.TRUE, start, startLine, startColumn)
            'f' -> keyword("false", TokenKind.FALSE, start, startLine, startColumn)
            'n' -> keyword("null", TokenKind.NULL, start, startLine, startColumn)
            else -> throw LexFailure("unexpected character '$character'", startLine, startColumn)
        }
    }

    private fun skipTrivia() {
        while (offset < source.length) {
            when {
                source[offset].isWhitespace() -> consume()
                source.startsWith("//", offset) -> {
                    if (!comments) throw LexFailure("comments are not allowed in strict JSON", line, column)
                    while (offset < source.length && source[offset] != '\n' && source[offset] != '\r') consume()
                }
                source.startsWith("/*", offset) -> {
                    if (!comments) throw LexFailure("comments are not allowed in strict JSON", line, column)
                    val commentLine = line
                    val commentColumn = column
                    consume(); consume()
                    while (offset < source.length && !source.startsWith("*/", offset)) consume()
                    if (offset == source.length) throw LexFailure("unterminated block comment", commentLine, commentColumn)
                    consume(); consume()
                }
                else -> return
            }
        }
    }

    private fun string(start: Int, startLine: Int, startColumn: Int): JsonToken {
        consume()
        val decoded = StringBuilder()
        while (offset < source.length) {
            val char = source[offset]
            when {
                char == '"' -> {
                    consume()
                    return token(TokenKind.STRING, start, offset, startLine, startColumn, decoded.toString())
                }
                char == '\\' -> {
                    consume()
                    if (offset == source.length) throw LexFailure("unterminated escape", line, column)
                    when (val escaped = source[offset]) {
                        '"', '\\', '/' -> { decoded.append(escaped); consume() }
                        'b' -> { decoded.append('\b'); consume() }
                        'f' -> { decoded.append('\u000C'); consume() }
                        'n' -> { decoded.append('\n'); consume() }
                        'r' -> { decoded.append('\r'); consume() }
                        't' -> { decoded.append('\t'); consume() }
                        'u' -> decoded.append(readUnicode())
                        else -> throw LexFailure("invalid escape '\\$escaped'", line, column)
                    }
                }
                char.code < 0x20 -> throw LexFailure("control character in string", line, column)
                else -> { decoded.append(char); consume() }
            }
        }
        throw LexFailure("unterminated string", startLine, startColumn)
    }

    private fun readUnicode(): Char {
        consume()
        if (offset + 4 > source.length) throw LexFailure("incomplete unicode escape", line, column)
        val digits = source.substring(offset, offset + 4)
        val code = digits.toIntOrNull(16) ?: throw LexFailure("invalid unicode escape", line, column)
        repeat(4) { consume() }
        return code.toChar()
    }

    private fun number(start: Int, startLine: Int, startColumn: Int): JsonToken {
        if (source[offset] == '-') consume()
        if (offset == source.length) throw LexFailure("incomplete number", startLine, startColumn)
        if (source[offset] == '0') {
            consume()
            if (offset < source.length && source[offset].isDigit()) throw LexFailure("leading zero in number", line, column)
        } else {
            if (source[offset] !in '1'..'9') throw LexFailure("invalid number", line, column)
            while (offset < source.length && source[offset].isDigit()) consume()
        }
        if (offset < source.length && source[offset] == '.') {
            consume()
            if (offset == source.length || !source[offset].isDigit()) throw LexFailure("fraction requires a digit", line, column)
            while (offset < source.length && source[offset].isDigit()) consume()
        }
        if (offset < source.length && source[offset].lowercaseChar() == 'e') {
            consume()
            if (offset < source.length && source[offset] in "+-") consume()
            if (offset == source.length || !source[offset].isDigit()) throw LexFailure("exponent requires a digit", line, column)
            while (offset < source.length && source[offset].isDigit()) consume()
        }
        return token(TokenKind.NUMBER, start, offset, startLine, startColumn)
    }

    private fun keyword(word: String, kind: TokenKind, start: Int, startLine: Int, startColumn: Int): JsonToken {
        if (!source.startsWith(word, offset)) throw LexFailure("unexpected token", startLine, startColumn)
        repeat(word.length) { consume() }
        return token(kind, start, offset, startLine, startColumn)
    }

    private fun simple(kind: TokenKind): JsonToken {
        val start = offset
        val startLine = line
        val startColumn = column
        consume()
        return token(kind, start, offset, startLine, startColumn)
    }

    private fun consume() {
        val character = source[offset++]
        if (character == '\n') {
            line++
            column = 1
        } else {
            column++
        }
    }

    private fun token(
        kind: TokenKind,
        start: Int,
        end: Int,
        line: Int,
        column: Int,
        decoded: String? = null,
    ) = JsonToken(kind, start, end, line, column, source.substring(start, end), decoded)
}

private enum class TokenKind { LEFT_BRACE, RIGHT_BRACE, LEFT_BRACKET, RIGHT_BRACKET, COLON, COMMA, STRING, NUMBER, TRUE, FALSE, NULL, EOF }

private data class JsonToken(
    val kind: TokenKind,
    val start: Int,
    val end: Int,
    val line: Int,
    val column: Int,
    val lexeme: String,
    val decoded: String? = null,
)

private class LexFailure(
    override val message: String,
    val line: Int,
    val column: Int,
    val category: ConfigProblemCategory = ConfigProblemCategory.SYNTAX,
) : RuntimeException(message)

private class JsonFailure(
    override val message: String,
    val line: Int,
    val column: Int,
    val category: ConfigProblemCategory,
    val key: ConfigKeyPath = ConfigKeyPath(emptyList()),
) : RuntimeException(message)

private fun renderJson(value: ConfigValue, depth: Int = 0): String = when (value) {
    ConfigValue.NullValue -> "null"
    is ConfigValue.BooleanValue -> value.value.toString()
    is ConfigValue.StringValue -> quoteJson(value.value)
    is ConfigValue.IntegerValue -> value.value.toString()
    is ConfigValue.DecimalValue -> value.value
    is ConfigValue.ArrayValue -> value.values.joinToString(prefix = "[", postfix = "]") { renderJson(it, depth + 1) }
    is ConfigValue.ObjectValue -> {
        if (value.entries.isEmpty()) "{}" else {
            val indent = "  ".repeat(depth + 1)
            val closing = "  ".repeat(depth)
            value.entries.entries.joinToString(prefix = "{\n", postfix = "\n$closing}", separator = ",\n") { (key, child) ->
                "$indent${quoteJson(key)}: ${renderJson(child, depth + 1)}"
            }
        }
    }
}

private fun quoteJson(value: String): String = buildString {
    append('"')
    value.forEach { character ->
        when (character) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\b' -> append("\\b")
            '\u000C' -> append("\\f")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (character.code < 0x20) append("\\u%04x".format(character.code)) else append(character)
        }
    }
    append('"')
}

private fun renderFreshJson(
    value: ConfigValue,
    comments: Map<ConfigKeyPath, String>,
    commentsAllowed: Boolean,
    path: ConfigKeyPath,
    depth: Int,
): String {
    val rendered = when (value) {
        ConfigValue.NullValue -> "null"
        is ConfigValue.BooleanValue -> value.value.toString()
        is ConfigValue.StringValue -> quoteJson(value.value)
        is ConfigValue.IntegerValue -> value.value.toString()
        is ConfigValue.DecimalValue -> value.value
        is ConfigValue.ArrayValue -> {
            if (value.values.isEmpty()) "[]" else {
                val indent = "  ".repeat(depth + 1)
                val closing = "  ".repeat(depth)
                value.values.mapIndexed { index, child ->
                    "$indent${renderFreshJson(child, comments, commentsAllowed, ConfigKeyPath(path.segments + index.toString()), depth + 1)}"
                }.joinToString(prefix = "[\n", postfix = "\n$closing]", separator = ",\n")
            }
        }
        is ConfigValue.ObjectValue -> {
            if (value.entries.isEmpty()) "{}" else {
                val indent = "  ".repeat(depth + 1)
                val closing = "  ".repeat(depth)
                value.entries.entries.joinToString(prefix = "{\n", postfix = "\n$closing}", separator = ",\n") { (key, child) ->
                    val childPath = ConfigKeyPath(path.segments + key)
                    val comment = if (commentsAllowed) comments[childPath]?.lineSequence()?.joinToString("\n", postfix = "\n") {
                        "$indent// $it"
                    }.orEmpty() else ""
                    "$comment$indent${quoteJson(key)}: ${renderFreshJson(child, comments, commentsAllowed, childPath, depth + 1)}"
                }
            }
        }
    }
    val rootComment = if (depth == 0 && commentsAllowed) comments[ConfigKeyPath(emptyList())]
        ?.lineSequence()
        ?.joinToString("\n", postfix = "\n") { "// $it" }
        .orEmpty() else ""
    return rootComment + rendered
}

private fun jsonLocation(source: String, offset: Int): ConfigSourceLocation {
    val line = source.take(offset).count { it == '\n' } + 1
    val lineStart = source.lastIndexOf('\n', offset - 1).let { if (it == -1) 0 else it + 1 }
    return ConfigSourceLocation(line, offset - lineStart + 1)
}

private fun jsonComments(fragment: String): List<String> = buildList {
    var offset = 0
    var inString = false
    var escaped = false
    while (offset < fragment.length) {
        val character = fragment[offset]
        if (inString) {
            when {
                escaped -> escaped = false
                character == '\\' -> escaped = true
                character == '"' -> inString = false
            }
            offset++
            continue
        }
        when {
            character == '"' -> { inString = true; offset++ }
            fragment.startsWith("//", offset) -> {
                val end = fragment.indexOfAny(charArrayOf('\r', '\n'), offset).let {
                    if (it == -1) fragment.length else it
                }
                add(fragment.substring(offset, end))
                offset = end
            }
            fragment.startsWith("/*", offset) -> {
                val end = fragment.indexOf("*/", offset + 2).let {
                    if (it == -1) fragment.length else it + 2
                }
                add(fragment.substring(offset, end))
                offset = end
            }
            else -> offset++
        }
    }
}
