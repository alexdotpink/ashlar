package pink.alex.ashlar.config

/** Stable path to a key in the format-neutral configuration value tree. */
public data class ConfigKeyPath(val segments: List<String>) {
    public constructor(vararg segments: String) : this(segments.toList())

    override fun toString(): String = segments.joinToString(".")
}

/** One value-free, source-located parse, migration, decode, or validation diagnostic. */
public data class ConfigProblem(
    val path: String,
    val key: ConfigKeyPath = ConfigKeyPath(emptyList()),
    val category: ConfigProblemCategory,
    val severity: ConfigProblemSeverity = ConfigProblemSeverity.ERROR,
    val message: String,
    val location: ConfigSourceLocation? = null,
    val expected: String? = null,
    val nearestKnownKey: ConfigKeyPath? = null,
)

/** One-based source position in a configuration document. */
public data class ConfigSourceLocation(val line: Int, val column: Int) {
    init {
        require(line >= 1) { "line must be at least 1" }
        require(column >= 1) { "column must be at least 1" }
    }
}

/** Stable categories suitable for tooling without inspecting message prose. */
public enum class ConfigProblemCategory {
    SYNTAX,
    DUPLICATE_KEY,
    UNKNOWN_KEY,
    UNSUPPORTED_SCHEMA,
    DECODING,
    MIGRATION,
    VALIDATION,
    RESOURCE_LIMIT,
    UNSUPPORTED_FEATURE,
}

/** Whether a diagnostic prevents publication. */
public enum class ConfigProblemSeverity { ERROR, WARNING }

/** A redacted recoverable filesystem or permission problem. */
public data class ConfigOperationProblem(
    val path: String,
    val category: ConfigOperationProblemCategory,
    val message: String,
)

/** Stable categories for operational problems outside document contents. */
public enum class ConfigOperationProblemCategory {
    NOT_FOUND,
    PERMISSION_DENIED,
    READ_FAILED,
    WRITE_FAILED,
    BACKUP_FAILED,
    ATOMIC_REPLACE_FAILED,
    WATCH_FAILED,
}
