package pink.alex.ashlar.config

/** Redacted operational metadata for one configuration document. */
public data class ConfigInspection(
    val path: String,
    val format: String,
    val schemaVersion: Int,
    val sourceRevision: ConfigSourceRevision?,
    val reloadMode: ConfigReloadMode,
    val watcherStatus: ConfigWatcherStatus,
    val status: ConfigOperationStatus,
    val warningCount: Int,
    val problems: List<ConfigProblem> = emptyList(),
    val backups: List<ConfigBackup> = emptyList(),
)

/** Lifecycle state of the optional watcher for one document. */
public enum class ConfigWatcherStatus {
    DISABLED,
    STARTING,
    WATCHING,
    RECOVERING,
    STOPPED,
}
