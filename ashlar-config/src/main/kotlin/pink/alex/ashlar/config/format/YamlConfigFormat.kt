package pink.alex.ashlar.config.format

import org.snakeyaml.engine.v2.api.DumpSettings
import org.snakeyaml.engine.v2.api.LoadSettings
import org.snakeyaml.engine.v2.api.lowlevel.Compose
import org.snakeyaml.engine.v2.api.lowlevel.Parse
import org.snakeyaml.engine.v2.api.lowlevel.Present
import org.snakeyaml.engine.v2.api.lowlevel.Serialize
import org.snakeyaml.engine.v2.comments.CommentLine
import org.snakeyaml.engine.v2.comments.CommentType
import org.snakeyaml.engine.v2.common.FlowStyle
import org.snakeyaml.engine.v2.common.ScalarStyle
import org.snakeyaml.engine.v2.exceptions.MarkedYamlEngineException
import org.snakeyaml.engine.v2.exceptions.YamlEngineException
import org.snakeyaml.engine.v2.events.AliasEvent
import org.snakeyaml.engine.v2.events.Event
import org.snakeyaml.engine.v2.events.NodeEvent
import org.snakeyaml.engine.v2.events.ScalarEvent
import org.snakeyaml.engine.v2.nodes.MappingNode
import org.snakeyaml.engine.v2.nodes.Node
import org.snakeyaml.engine.v2.nodes.NodeTuple
import org.snakeyaml.engine.v2.nodes.ScalarNode
import org.snakeyaml.engine.v2.nodes.SequenceNode
import org.snakeyaml.engine.v2.nodes.Tag
import org.snakeyaml.engine.v2.schema.CoreSchema
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
import java.util.Optional

/** Safe YAML 1.2 core-schema format with comment-aware node patching. */
internal object YamlConfigFormat : ConfigFormat {
    override val id: String = "yaml"
    override val extensions: Set<String> = setOf("yml", "yaml")
    override val preservesComments: Boolean = true

    override fun parse(source: ConfigSource, limits: ConfigLimits): ConfigParse {
        val bytes = source.text.toByteArray(StandardCharsets.UTF_8).size.toLong()
        if (bytes > limits.maximumBytes) {
            return rejected(source.path, ConfigProblemCategory.RESOURCE_LIMIT, "document exceeds ${limits.maximumBytes} UTF-8 bytes")
        }
        val settings = LoadSettings.builder()
            .setLabel(source.path)
            .setSchema(CoreSchema())
            .setAllowDuplicateKeys(false)
            .setAllowRecursiveKeys(false)
            .setAllowNonScalarKeys(false)
            .setMaxAliasesForCollections(limits.maximumAliases)
            .setCodePointLimit(limits.maximumBytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            .setParseComments(true)
            .setUseMarks(true)
            .setVersionFunction { version ->
                if (version.major != 1 || version.minor != 2) {
                    throw YamlEngineException("only the YAML 1.2 directive is supported")
                }
                version
            }
            .build()
        return try {
            preflight(source.text, settings, limits)
            val root = Compose(settings).composeString(source.text).orElse(null)
                ?: return rejected(source.path, ConfigProblemCategory.SYNTAX, "configuration root must be a mapping")
            if (root !is MappingNode) {
                return problem(source.path, root, ConfigProblemCategory.SYNTAX, "configuration root must be a mapping")
            }
            val value = YamlNodeCodec(limits).decodeRoot(root)
            ConfigParse.Accepted(LosslessYamlDocument(value, source.text, root, lineBreak(source.text)))
        } catch (failure: YamlPreflightFailure) {
            ConfigParse.Rejected(
                listOf(ConfigProblem(
                    path = source.path,
                    key = failure.path,
                    category = failure.category,
                    message = failure.message,
                    location = failure.location,
                )),
            )
        } catch (failure: YamlFormatFailure) {
            ConfigParse.Rejected(
                listOf(
                    ConfigProblem(
                        path = source.path,
                        key = failure.path,
                        category = failure.category,
                        message = failure.message,
                        location = failure.node.startMark.map { ConfigSourceLocation(it.line + 1, it.column + 1) }.orElse(null),
                    ),
                ),
            )
        } catch (failure: YamlEngineException) {
            val marked = failure as? MarkedYamlEngineException
            val mark = marked?.problemMark?.orElse(null)
            val duplicate = failure.message.orEmpty().contains("duplicate", ignoreCase = true)
            val resource = failure.message.orEmpty().let {
                it.contains("aliases", ignoreCase = true) || it.contains("code point", ignoreCase = true)
            }
            ConfigParse.Rejected(
                listOf(
                    ConfigProblem(
                        path = source.path,
                        category = when {
                            duplicate -> ConfigProblemCategory.DUPLICATE_KEY
                            resource -> ConfigProblemCategory.RESOURCE_LIMIT
                            else -> ConfigProblemCategory.SYNTAX
                        },
                        message = marked?.problem ?: "invalid YAML",
                        location = mark?.let { ConfigSourceLocation(it.line + 1, it.column + 1) },
                    ),
                ),
            )
        }
    }

    override fun create(
        value: ConfigValue.ObjectValue,
        comments: Map<ConfigKeyPath, String>,
    ): ConfigDocument {
        val root = YamlNodePatcher(comments).patchRoot(
            MappingNode(Tag.MAP, emptyList(), FlowStyle.BLOCK),
            value,
        )
        return LosslessYamlDocument(value, emitYaml(root, "\n"), root, "\n")
    }

    override fun write(document: ConfigDocument): String {
        require(document is LosslessYamlDocument) { "format '$id' cannot write a '${document.formatId}' document" }
        return document.source
    }

    private fun problem(path: String, node: Node, category: ConfigProblemCategory, message: String): ConfigParse.Rejected =
        ConfigParse.Rejected(
            listOf(
                ConfigProblem(
                    path = path,
                    category = category,
                    message = message,
                    location = node.startMark.map { ConfigSourceLocation(it.line + 1, it.column + 1) }.orElse(null),
                ),
            ),
        )

    private fun rejected(path: String, category: ConfigProblemCategory, message: String): ConfigParse.Rejected =
        ConfigParse.Rejected(listOf(ConfigProblem(path, category = category, message = message)))

    private fun preflight(
        source: String,
        settings: LoadSettings,
        limits: ConfigLimits,
    ) {
        var depth = 0
        var documents = 0
        var aliases = 0
        val openAnchors = ArrayDeque<String?>()
        Parse(settings).parseString(source).forEach { event ->
            when (event.eventId) {
                Event.ID.DocumentStart -> {
                    documents++
                    if (documents > 1) event.reject(
                        ConfigProblemCategory.SYNTAX,
                        "configuration source must contain exactly one YAML document",
                    )
                }
                Event.ID.MappingStart, Event.ID.SequenceStart -> {
                    depth++
                    openAnchors.addLast((event as NodeEvent).anchor.map { anchor -> anchor.value }.orElse(null))
                    if (depth > limits.maximumDepth) event.reject(
                        ConfigProblemCategory.RESOURCE_LIMIT,
                        "nesting exceeds ${limits.maximumDepth}",
                    )
                }
                Event.ID.MappingEnd, Event.ID.SequenceEnd -> {
                    depth--
                    openAnchors.removeLast()
                }
                Event.ID.Alias -> {
                    aliases++
                    val alias = (event as AliasEvent).alias.value
                    if (alias in openAnchors) event.reject(
                        ConfigProblemCategory.UNSUPPORTED_FEATURE,
                        "recursive YAML aliases are not supported",
                    )
                    if (aliases > limits.maximumAliases) event.reject(
                        ConfigProblemCategory.RESOURCE_LIMIT,
                        "aliases exceed ${limits.maximumAliases}",
                    )
                }
                Event.ID.Scalar -> {
                    val scalar = event as ScalarEvent
                    if (scalar.value.codePointCount(0, scalar.value.length) > limits.maximumScalarCharacters) {
                        event.reject(
                            ConfigProblemCategory.RESOURCE_LIMIT,
                            "scalar exceeds ${limits.maximumScalarCharacters} characters",
                        )
                    }
                }
                else -> Unit
            }
        }
        if (documents != 1) throw YamlPreflightFailure(
            path = ConfigKeyPath(),
            category = ConfigProblemCategory.SYNTAX,
            message = "configuration source must contain exactly one YAML document",
            location = null,
        )
    }

    private fun Event.reject(category: ConfigProblemCategory, message: String): Nothing =
        throw YamlPreflightFailure(
            path = ConfigKeyPath(),
            category = category,
            message = message,
            location = startMark.map { mark -> ConfigSourceLocation(mark.line + 1, mark.column + 1) }.orElse(null),
        )
}

private data class LosslessYamlDocument(
    override val value: ConfigValue.ObjectValue,
    val source: String,
    val root: MappingNode,
    val lineBreak: String,
) : ConfigDocument {
    override val formatId: String = "yaml"

    override fun location(key: ConfigKeyPath): ConfigSourceLocation? {
        var node: Node = root
        key.segments.forEachIndexed { index, segment ->
            node = when (node) {
                is MappingNode -> node.value.firstOrNull { (it.keyNode as? ScalarNode)?.value == segment }?.let { tuple ->
                    if (index == key.segments.lastIndex) {
                        return tuple.keyNode.startMark.map { ConfigSourceLocation(it.line + 1, it.column + 1) }.orElse(null)
                    }
                    tuple.valueNode
                } ?: return null
                is SequenceNode -> node.value.getOrNull(segment.toIntOrNull() ?: return null) ?: return null
                else -> return null
            }
        }
        return null
    }

    override fun patch(
        value: ConfigValue.ObjectValue,
        newComments: Map<ConfigKeyPath, String>,
    ): ConfigDocument {
        if (value == this.value) return this
        val patched = YamlNodePatcher(newComments).patchRoot(root, value)
        val emitted = emitYaml(patched, lineBreak)
        return LosslessYamlDocument(value, emitted, patched, lineBreak)
    }
}

private class YamlNodeCodec(private val limits: ConfigLimits) {
    fun decodeRoot(root: MappingNode): ConfigValue.ObjectValue = decode(root, 1, ConfigKeyPath(emptyList())) as ConfigValue.ObjectValue

    private fun decode(node: Node, depth: Int, path: ConfigKeyPath): ConfigValue {
        ensureSafeTag(node, path)
        return when (node) {
            is MappingNode -> {
                ensureDepth(node, depth, path)
                val entries = linkedMapOf<String, ConfigValue>()
                node.value.forEach { tuple ->
                    val keyNode = tuple.keyNode as? ScalarNode
                        ?: throw YamlFormatFailure(tuple.keyNode, path, ConfigProblemCategory.UNSUPPORTED_FEATURE, "mapping keys must be strings")
                    if (keyNode.tag != Tag.STR) {
                        throw YamlFormatFailure(keyNode, path, ConfigProblemCategory.UNSUPPORTED_FEATURE, "mapping keys must be strings")
                    }
                    ensureScalar(keyNode, path)
                    val key = keyNode.value
                    val childPath = ConfigKeyPath(path.segments + key)
                    if (key in entries) {
                        throw YamlFormatFailure(keyNode, childPath, ConfigProblemCategory.DUPLICATE_KEY, "duplicate key '$key'")
                    }
                    entries[key] = decode(tuple.valueNode, depth + 1, childPath)
                }
                ConfigValue.ObjectValue(entries)
            }
            is SequenceNode -> {
                ensureDepth(node, depth, path)
                ConfigValue.ArrayValue(node.value.mapIndexed { index, child ->
                    decode(child, depth + 1, ConfigKeyPath(path.segments + index.toString()))
                })
            }
            is ScalarNode -> decodeScalar(node, path)
            else -> throw YamlFormatFailure(node, path, ConfigProblemCategory.UNSUPPORTED_FEATURE, "unsupported YAML node")
        }
    }

    private fun decodeScalar(node: ScalarNode, path: ConfigKeyPath): ConfigValue {
        ensureScalar(node, path)
        return when (node.tag) {
            Tag.NULL -> ConfigValue.NullValue
            Tag.BOOL -> ConfigValue.BooleanValue(node.value.equals("true", ignoreCase = true))
            Tag.INT -> parseYamlInteger(node.value)?.let(ConfigValue::IntegerValue)
                ?: throw YamlFormatFailure(node, path, ConfigProblemCategory.UNSUPPORTED_FEATURE, "integer is outside the signed 64-bit range")
            Tag.FLOAT -> {
                if (node.value.lowercase() in NON_FINITE_FLOATS) {
                    throw YamlFormatFailure(node, path, ConfigProblemCategory.UNSUPPORTED_FEATURE, "non-finite numbers are not supported")
                }
                ConfigValue.DecimalValue(node.value.replace("_", ""))
            }
            Tag.STR -> ConfigValue.StringValue(node.value)
            else -> throw YamlFormatFailure(node, path, ConfigProblemCategory.UNSUPPORTED_FEATURE, "custom YAML tags are not supported")
        }
    }

    private fun ensureSafeTag(node: Node, path: ConfigKeyPath) {
        if (node.tag !in SAFE_TAGS) {
            throw YamlFormatFailure(node, path, ConfigProblemCategory.UNSUPPORTED_FEATURE, "custom YAML tags are not supported")
        }
    }

    private fun ensureDepth(node: Node, depth: Int, path: ConfigKeyPath) {
        if (depth > limits.maximumDepth) {
            throw YamlFormatFailure(node, path, ConfigProblemCategory.RESOURCE_LIMIT, "nesting exceeds ${limits.maximumDepth}")
        }
    }

    private fun ensureScalar(node: ScalarNode, path: ConfigKeyPath) {
        if (node.value.codePointCount(0, node.value.length) > limits.maximumScalarCharacters) {
            throw YamlFormatFailure(
                node,
                path,
                ConfigProblemCategory.RESOURCE_LIMIT,
                "scalar exceeds ${limits.maximumScalarCharacters} characters",
            )
        }
    }

    private companion object {
        val SAFE_TAGS = setOf(Tag.MAP, Tag.SEQ, Tag.NULL, Tag.BOOL, Tag.INT, Tag.FLOAT, Tag.STR)
        val NON_FINITE_FLOATS = setOf(".inf", "+.inf", "-.inf", ".nan")
    }
}

private class YamlNodePatcher(private val newComments: Map<ConfigKeyPath, String>) {
    fun patchRoot(root: MappingNode, value: ConfigValue.ObjectValue): MappingNode =
        patch(root, value, ConfigKeyPath(emptyList())) as MappingNode

    private fun patch(node: Node, replacement: ConfigValue, path: ConfigKeyPath): Node {
        val current = runCatching { YamlNodeCodec(UNBOUNDED_LIMITS).decodeForPatch(node) }.getOrNull()
        if (current == replacement) return node
        return when {
            node is MappingNode && replacement is ConfigValue.ObjectValue -> patchMapping(node, replacement, path)
            node is SequenceNode && replacement is ConfigValue.ArrayValue -> patchSequence(node, replacement, path)
            else -> nodeFor(replacement, path).also { patched ->
                copyComments(node, patched)
                val orphaned = descendantComments(node).map(::asStandalone)
                if (orphaned.isNotEmpty()) patched.endComments = patched.endComments.orEmpty() + orphaned
            }
        }
    }

    private fun patchMapping(node: MappingNode, replacement: ConfigValue.ObjectValue, path: ConfigKeyPath): MappingNode {
        val existing = node.value.associateBy { (it.keyNode as? ScalarNode)?.value }
        val removedComments = node.value
            .filter { (it.keyNode as? ScalarNode)?.value !in replacement.entries }
            .flatMap { collectComments(it.keyNode) + collectComments(it.valueNode) }
            .map(::asStandalone)
            .toMutableList()
        val tuples = replacement.entries.map { (key, value) ->
            val old = existing[key]
            if (old != null) {
                NodeTuple(old.keyNode, patch(old.valueNode, value, ConfigKeyPath(path.segments + key)))
            } else {
                val keyNode = ScalarNode(Tag.STR, key, ScalarStyle.PLAIN)
                val comment = newComments[ConfigKeyPath(path.segments + key)]
                if (comment != null) keyNode.blockComments = commentLines(comment)
                NodeTuple(keyNode, nodeFor(value, ConfigKeyPath(path.segments + key)))
            }
        }
        val patched = MappingNode(Tag.MAP, tuples, node.flowStyle)
        copyComments(node, patched)
        if (patched.blockComments.orEmpty().isEmpty()) {
            newComments[path]?.let { patched.blockComments = commentLines(it) }
        }
        if (removedComments.isNotEmpty()) patched.endComments = patched.endComments.orEmpty() + removedComments
        return patched
    }

    private fun patchSequence(node: SequenceNode, replacement: ConfigValue.ArrayValue, path: ConfigKeyPath): SequenceNode {
        val values = replacement.values.mapIndexed { index, value ->
            node.value.getOrNull(index)?.let { patch(it, value, ConfigKeyPath(path.segments + index.toString())) }
                ?: nodeFor(value, ConfigKeyPath(path.segments + index.toString()))
        }
        val patched = SequenceNode(Tag.SEQ, values, node.flowStyle)
        copyComments(node, patched)
        val removed = node.value.drop(values.size).flatMap(::collectComments).map(::asStandalone)
        if (removed.isNotEmpty()) patched.endComments = patched.endComments.orEmpty() + removed
        return patched
    }

    private fun nodeFor(value: ConfigValue, path: ConfigKeyPath): Node = when (value) {
        ConfigValue.NullValue -> ScalarNode(Tag.NULL, "null", ScalarStyle.PLAIN)
        is ConfigValue.BooleanValue -> ScalarNode(Tag.BOOL, value.value.toString(), ScalarStyle.PLAIN)
        is ConfigValue.StringValue -> ScalarNode(Tag.STR, value.value, ScalarStyle.DOUBLE_QUOTED)
        is ConfigValue.IntegerValue -> ScalarNode(Tag.INT, value.value.toString(), ScalarStyle.PLAIN)
        is ConfigValue.DecimalValue -> ScalarNode(Tag.FLOAT, value.value, ScalarStyle.PLAIN)
        is ConfigValue.ArrayValue -> SequenceNode(
            Tag.SEQ,
            value.values.mapIndexed { index, child -> nodeFor(child, ConfigKeyPath(path.segments + index.toString())) },
            FlowStyle.BLOCK,
        )
        is ConfigValue.ObjectValue -> MappingNode(
            Tag.MAP,
            value.entries.map { (key, child) ->
                val keyNode = ScalarNode(Tag.STR, key, ScalarStyle.PLAIN)
                newComments[ConfigKeyPath(path.segments + key)]?.let { keyNode.blockComments = commentLines(it) }
                NodeTuple(keyNode, nodeFor(child, ConfigKeyPath(path.segments + key)))
            },
            FlowStyle.BLOCK,
        )
    }

    private fun copyComments(from: Node, to: Node) {
        to.blockComments = from.blockComments.orEmpty()
        to.inLineComments = from.inLineComments.orEmpty()
        to.endComments = from.endComments.orEmpty()
    }

    private fun collectComments(node: Node): List<CommentLine> = buildList {
        addAll(node.blockComments.orEmpty())
        addAll(node.inLineComments.orEmpty())
        addAll(node.endComments.orEmpty())
        when (node) {
            is MappingNode -> node.value.forEach { addAll(collectComments(it.keyNode)); addAll(collectComments(it.valueNode)) }
            is SequenceNode -> node.value.forEach { addAll(collectComments(it)) }
        }
    }

    private fun descendantComments(node: Node): List<CommentLine> = buildList {
        when (node) {
            is MappingNode -> node.value.forEach { tuple ->
                addAll(collectComments(tuple.keyNode))
                addAll(collectComments(tuple.valueNode))
            }
            is SequenceNode -> node.value.forEach { child -> addAll(collectComments(child)) }
        }
    }

    private fun asStandalone(line: CommentLine): CommentLine =
        CommentLine(Optional.empty(), Optional.empty(), line.value, CommentType.BLOCK)

    private fun commentLines(comment: String): List<CommentLine> = comment.lineSequence().map {
        CommentLine(Optional.empty(), Optional.empty(), " $it", CommentType.BLOCK)
    }.toList()

    private fun YamlNodeCodec.decodeForPatch(node: Node): ConfigValue =
        when (node) {
            is MappingNode -> decodeRoot(node)
            else -> decodeNodeForPatch(node)
        }

    private fun decodeNodeForPatch(node: Node): ConfigValue = when (node) {
        is ScalarNode -> when (node.tag) {
            Tag.NULL -> ConfigValue.NullValue
            Tag.BOOL -> ConfigValue.BooleanValue(node.value.equals("true", true))
            Tag.INT -> ConfigValue.IntegerValue(checkNotNull(parseYamlInteger(node.value)))
            Tag.FLOAT -> ConfigValue.DecimalValue(node.value.replace("_", ""))
            else -> ConfigValue.StringValue(node.value)
        }
        is SequenceNode -> ConfigValue.ArrayValue(node.value.map(::decodeNodeForPatch))
        is MappingNode -> ConfigValue.ObjectValue(node.value.associate { tuple ->
            (tuple.keyNode as ScalarNode).value to decodeNodeForPatch(tuple.valueNode)
        })
        else -> error("unsupported node")
    }

    private companion object {
        val UNBOUNDED_LIMITS = ConfigLimits(
            maximumBytes = ConfigLimits.MAXIMUM_DOCUMENT_BYTES,
            maximumDepth = ConfigLimits.MAXIMUM_NESTING_DEPTH,
            maximumScalarCharacters = ConfigLimits.MAXIMUM_SCALAR_CHARACTERS,
            maximumAliases = ConfigLimits.MAXIMUM_ALIAS_COUNT,
        )
    }
}

private class YamlFormatFailure(
    val node: Node,
    val path: ConfigKeyPath,
    val category: ConfigProblemCategory,
    override val message: String,
) : RuntimeException(message)

private class YamlPreflightFailure(
    val path: ConfigKeyPath,
    val category: ConfigProblemCategory,
    override val message: String,
    val location: ConfigSourceLocation?,
) : RuntimeException(message)

private fun parseYamlInteger(raw: String): Long? {
    val value = raw.replace("_", "")
    val negative = value.startsWith('-')
    val positive = value.startsWith('+')
    val unsigned = if (negative || positive) value.substring(1) else value
    val (radix, digits) = when {
        unsigned.startsWith("0x", true) -> 16 to unsigned.substring(2)
        unsigned.startsWith("0o", true) -> 8 to unsigned.substring(2)
        unsigned.startsWith("0b", true) -> 2 to unsigned.substring(2)
        else -> 10 to unsigned
    }
    val parsed = digits.toLongOrNull(radix) ?: return null
    return if (negative) -parsed else parsed
}

private fun lineBreak(source: String): String = if ("\r\n" in source) "\r\n" else "\n"

private fun emitYaml(root: MappingNode, lineBreak: String): String {
    val settings = DumpSettings.builder()
        .setSchema(CoreSchema())
        .setDefaultFlowStyle(FlowStyle.BLOCK)
        .setDefaultScalarStyle(ScalarStyle.PLAIN)
        .setBestLineBreak(lineBreak)
        .setDumpComments(true)
        .setDereferenceAliases(false)
        .build()
    return Present(settings).emitToString(Serialize(settings).serializeOne(root).iterator())
}
