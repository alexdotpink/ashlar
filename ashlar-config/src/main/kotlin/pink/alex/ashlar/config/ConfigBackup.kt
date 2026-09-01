package pink.alex.ashlar.config

import java.time.Instant

/** Opaque identifier of one retained valid predecessor document. */
@JvmInline
public value class ConfigBackupId(public val value: String)

/** Value-free metadata for a retained valid predecessor document. */
public data class ConfigBackup(
    val id: ConfigBackupId,
    val createdAt: Instant,
    val schemaVersion: Int,
    val sourceRevision: ConfigSourceRevision,
)
