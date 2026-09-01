package pink.alex.ashlar.config

/** Result of an explicit or watched reload. */
public sealed interface ConfigReload<out T : Any> {
    /** A complete value passed parsing, migration, decoding, and validation. */
    public data class Accepted<T : Any>(
        val value: T,
        val changed: Boolean,
        val warnings: List<ConfigProblem> = emptyList(),
    ) : ConfigReload<T>

    /** The document was available but could not produce a valid value. */
    public data class Rejected<T : Any>(
        val current: T,
        val problems: List<ConfigProblem>,
    ) : ConfigReload<T>

    /** A recoverable I/O or permission problem prevented an attempt. */
    public data class Unavailable<T : Any>(
        val current: T,
        val problem: ConfigOperationProblem,
    ) : ConfigReload<T>
}

/** Result of an explicit stale-safe update. */
public sealed interface ConfigWrite<out T : Any> {
    /** The new value was validated, persisted, and published. */
    public data class Accepted<T : Any>(
        val value: T,
        val warnings: List<ConfigProblem> = emptyList(),
    ) : ConfigWrite<T>

    /** The transform returned an equal value, so no write or backup was created. */
    public data class Unchanged<T : Any>(val value: T) : ConfigWrite<T>

    /** The transformed value failed validation and was not written. */
    public data class Rejected<T : Any>(
        val current: T,
        val problems: List<ConfigProblem>,
    ) : ConfigWrite<T>

    /** The source changed outside Ashlar after the last accepted revision. */
    public data class SourceChanged<T : Any>(
        val current: T,
        val acceptedRevision: ConfigSourceRevision,
    ) : ConfigWrite<T>

    /** A recoverable I/O or permission problem prevented the write. */
    public data class Unavailable<T : Any>(
        val current: T,
        val problem: ConfigOperationProblem,
    ) : ConfigWrite<T>
}

/** Result of explicitly restoring one retained predecessor. */
public sealed interface ConfigRestore<out T : Any> {
    /** The backup was validated, installed, and published. */
    public data class Accepted<T : Any>(
        val value: T,
        val warnings: List<ConfigProblem> = emptyList(),
    ) : ConfigRestore<T>

    /** The backup exists but cannot produce a valid current value. */
    public data class Rejected<T : Any>(
        val current: T,
        val problems: List<ConfigProblem>,
    ) : ConfigRestore<T>

    /** The requested backup no longer exists. */
    public data class NotFound<T : Any>(
        val current: T,
        val id: ConfigBackupId,
    ) : ConfigRestore<T>

    /** A recoverable I/O or permission problem prevented the restore. */
    public data class Unavailable<T : Any>(
        val current: T,
        val problem: ConfigOperationProblem,
    ) : ConfigRestore<T>
}

/** Complete independent results from [Configurations.reloadAll]. */
public data class ConfigReloadReport(
    val documents: List<ConfigDocumentReload>,
) {
    /** Number of documents that accepted their reload attempt. */
    public val accepted: Int get() = documents.count { it.status == ConfigOperationStatus.ACCEPTED }

    /** Number of documents that retained their previous value. */
    public val retained: Int get() = documents.size - accepted
}

/** Value-free result for one document in a bulk reload. */
public data class ConfigDocumentReload(
    val path: String,
    val status: ConfigOperationStatus,
    val changed: Boolean = false,
    val problems: List<ConfigProblem> = emptyList(),
    val operationProblem: ConfigOperationProblem? = null,
)

/** High-level result state shared by bulk operations and inspection. */
public enum class ConfigOperationStatus { ACCEPTED, REJECTED, UNAVAILABLE }
