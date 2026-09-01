package pink.alex.ashlar.config

/** One attempted change to a configuration document. */
public sealed interface ConfigEvent<out T : Any> {
    public val origin: ConfigEventOrigin

    /** A complete source was accepted; [changed] says whether the typed value changed. */
    public data class Accepted<T : Any>(
        override val origin: ConfigEventOrigin,
        val value: T,
        val revision: ConfigSourceRevision,
        val changed: Boolean,
        val changedPaths: List<ConfigKeyPath> = emptyList(),
        val warnings: List<ConfigProblem> = emptyList(),
    ) : ConfigEvent<T>

    /** An available source was rejected without changing the accepted value. */
    public data class Rejected<T : Any>(
        override val origin: ConfigEventOrigin,
        val current: T,
        val revision: ConfigSourceRevision?,
        val problems: List<ConfigProblem>,
    ) : ConfigEvent<T>

    /** A recoverable operational problem prevented an attempt. */
    public data class Unavailable<T : Any>(
        override val origin: ConfigEventOrigin,
        val current: T,
        val problem: ConfigOperationProblem,
    ) : ConfigEvent<T>
}

/** Cause of a configuration operation or event. */
public enum class ConfigEventOrigin {
    INITIAL_LOAD,
    MANUAL_RELOAD,
    WATCHED_RELOAD,
    UPDATE,
    MIGRATION,
    RESTORE,
}

/** Opaque identity of exact source content used for stale-write protection. */
@JvmInline
public value class ConfigSourceRevision(public val value: String)
