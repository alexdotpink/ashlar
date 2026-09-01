package pink.alex.ashlar.config

/** A bounded parser and lossless writer for one family of file extensions. */
public interface ConfigFormat {
    /** Stable identifier shown in inspection output. */
    public val id: String

    /** Lowercase extensions without a leading dot. */
    public val extensions: Set<String>

    /** Whether framework writes retain every source comment token. */
    public val preservesComments: Boolean

    /** Parses one bounded source into a lossless document or typed diagnostics. */
    public fun parse(source: ConfigSource, limits: ConfigLimits = ConfigLimits()): ConfigParse

    /** Creates a complete new document in this format with generated KDoc comments. */
    public fun create(
        value: ConfigValue.ObjectValue,
        comments: Map<ConfigKeyPath, String> = emptyMap(),
    ): ConfigDocument

    /** Encodes a document previously created by this format. */
    public fun write(document: ConfigDocument): String
}

/** UTF-8 source supplied to a [ConfigFormat]. */
public data class ConfigSource(val path: String, val text: String)

/** Common mandatory parser resource limits. */
public data class ConfigLimits(
    val maximumBytes: Long = 1_048_576,
    val maximumDepth: Int = 64,
    val maximumScalarCharacters: Int = 262_144,
    val maximumAliases: Int = 50,
) {
    init {
        require(maximumBytes > 0) { "maximumBytes must be positive" }
        require(maximumDepth > 0) { "maximumDepth must be positive" }
        require(maximumScalarCharacters > 0) { "maximumScalarCharacters must be positive" }
        require(maximumAliases >= 0) { "maximumAliases cannot be negative" }
    }
}

/** Result of parsing one source document. */
public sealed interface ConfigParse {
    public data class Accepted(
        val document: ConfigDocument,
        val warnings: List<ConfigProblem> = emptyList(),
    ) : ConfigParse

    public data class Rejected(val problems: List<ConfigProblem>) : ConfigParse
}

/** Lossless format-owned document plus its format-neutral value projection. */
public interface ConfigDocument {
    public val formatId: String
    public val value: ConfigValue.ObjectValue

    /** Returns the start of one key when the format retained source spans. */
    public fun location(key: ConfigKeyPath): ConfigSourceLocation? = null

    /** Returns a document with semantic changes applied while retaining source trivia. */
    public fun patch(
        value: ConfigValue.ObjectValue,
        newComments: Map<ConfigKeyPath, String> = emptyMap(),
    ): ConfigDocument
}

/** Format-neutral immutable value used by formats, migrations, and patching. */
public sealed interface ConfigValue {
    public data object NullValue : ConfigValue
    public data class BooleanValue(val value: Boolean) : ConfigValue
    public data class StringValue(val value: String) : ConfigValue
    public data class IntegerValue(val value: Long) : ConfigValue
    public data class DecimalValue(val value: String) : ConfigValue
    public data class ArrayValue(val values: List<ConfigValue>) : ConfigValue
    public data class ObjectValue(val entries: Map<String, ConfigValue>) : ConfigValue
}
