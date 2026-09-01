package pink.alex.ashlar.config

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** A startup-ready capability for one accepted, immutable configuration value. */
public interface ConfigHandle<T : Any> {
    /** The latest accepted value. Rejected operations never change it. */
    public val current: T

    /** Accepted distinct values, beginning with [current]. */
    public val values: StateFlow<T>

    /** Accepted and rejected attempts, including comment-only accepted reloads. */
    public val events: Flow<ConfigEvent<T>>

    /** Re-reads, migrates, decodes, and validates the active document. */
    public suspend fun reload(): ConfigReload<T>

    /** Validates and atomically persists one transformation of [current]. */
    public suspend fun update(transform: (T) -> T): ConfigWrite<T>

    /** Lists bounded valid predecessor documents without exposing their contents. */
    public suspend fun backups(): List<ConfigBackup>

    /** Validates and atomically installs one retained predecessor document. */
    public suspend fun restore(id: ConfigBackupId): ConfigRestore<T>
}

/** Read-only operations spanning all configuration handles owned by one plug-in. */
public interface Configurations {
    /** Attempts every reload independently; one rejection does not roll back another acceptance. */
    public suspend fun reloadAll(): ConfigReloadReport

    /** Returns value-free operational snapshots for every known document. */
    public fun inspect(): List<ConfigInspection>
}
