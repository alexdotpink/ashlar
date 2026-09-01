package pink.alex.ashlar.sample

import kotlinx.serialization.Serializable
import pink.alex.ashlar.commands.Commands
import pink.alex.ashlar.config.Config
import pink.alex.ashlar.config.ConfigBackupId
import pink.alex.ashlar.config.ConfigDurationSerializer
import pink.alex.ashlar.config.ConfigHandle
import pink.alex.ashlar.config.ConfigReload
import pink.alex.ashlar.config.ConfigReloadMode
import pink.alex.ashlar.config.ConfigRestore
import pink.alex.ashlar.config.ConfigValidation
import pink.alex.ashlar.config.ConfigValidationScope
import pink.alex.ashlar.config.ConfigWrite
import pink.alex.ashlar.config.Configurations
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Live settings for the sample's configuration showcase.
 *
 * Edit `plugins/AshlarSample/config.jsonc` while the server is running to try watched reloads.
 */
@Serializable
@Config(path = "config.jsonc", reload = ConfigReloadMode.WATCH)
internal data class ShowcaseConfig(
    /** Message shown by `/config value`. */
    val message: String = "Ashlar configuration is live",
    /** Delay used by a plug-in feature which periodically refreshes its state. */
    @Serializable(with = ConfigDurationSerializer::class)
    val refreshInterval: Duration = 5.seconds,
    /** Maximum number of results a sample feature may display. */
    val resultLimit: Int = 20,
)

/** Demonstrates aggregated rejecting errors and accepted operational warnings. */
@ConfigValidation
internal fun ConfigValidationScope<ShowcaseConfig>.validateShowcaseConfig() {
    requireValue(current.message.isNotBlank(), ShowcaseConfig::message) {
        "message must not be blank"
    }
    requireValue(current.refreshInterval in 1.seconds..60.seconds, ShowcaseConfig::refreshInterval) {
        "refresh-interval must be between 1s and 60s"
    }
    requireValue(current.resultLimit in 1..100, ShowcaseConfig::resultLimit) {
        "result-limit must be between 1 and 100"
    }
    warnIf(current.refreshInterval < 2.seconds, ShowcaseConfig::refreshInterval) {
        "intervals below 2s can produce unnecessary work"
    }
}

/** Playable commands covering the safe runtime configuration operations. */
@Commands(name = "config", aliases = ["cfg"])
internal class ConfigShowcaseCommands(
    private val config: ConfigHandle<ShowcaseConfig>,
    private val configurations: Configurations,
) {
    /** Shows value-free runtime metadata. No configuration values are included. */
    fun status(): String {
        val status = configurations.inspect().single { inspection -> inspection.path == "config.jsonc" }
        return buildString {
            append("config.jsonc: ${status.status.name.lowercase()}")
            append(", schema ${status.schemaVersion}")
            append(", watcher ${status.watcherStatus.name.lowercase()}")
            append(", ${status.warningCount} warning(s)")
            append(", ${status.backups.size} backup(s)")
        }
    }

    /** Shows one intentionally public setting so a player can observe accepted changes. */
    fun value(): String = config.current.message

    /** Re-reads the complete document and retains the current value if it is invalid. */
    suspend fun reload(): String = when (val result = config.reload()) {
        is ConfigReload.Accepted ->
            "Accepted config.jsonc (values changed: ${result.changed}, warnings: ${result.warnings.size})."
        is ConfigReload.Rejected ->
            "Rejected config.jsonc; retained the current value (${result.problems.size} problem(s))."
        is ConfigReload.Unavailable ->
            "Could not read config.jsonc (${result.problem.category.name.lowercase()})."
    }

    /** Updates the sample refresh interval with validation and stale-source protection. */
    suspend fun interval(seconds: Int): String = when (
        val result = config.update { current -> current.copy(refreshInterval = seconds.seconds) }
    ) {
        is ConfigWrite.Accepted ->
            "Saved refresh-interval=${result.value.refreshInterval} (${result.warnings.size} warning(s))."
        is ConfigWrite.Unchanged -> "refresh-interval is already ${result.value.refreshInterval}."
        is ConfigWrite.Rejected -> "Rejected update (${result.problems.size} problem(s))."
        is ConfigWrite.SourceChanged -> "The file changed on disk; reload it before updating."
        is ConfigWrite.Unavailable -> "Could not save (${result.problem.category.name.lowercase()})."
    }

    /** Lists opaque backup identifiers accepted by `/config restore`. */
    suspend fun backups(): String {
        val backups = config.backups()
        return if (backups.isEmpty()) {
            "No backups yet. Run /config interval <seconds> to create one."
        } else {
            backups.joinToString(prefix = "Backups: ", separator = ", ") { backup -> backup.id.value }
        }
    }

    /** Restores one validated predecessor by the opaque identifier from `/config backups`. */
    suspend fun restore(id: String): String = when (val result = config.restore(ConfigBackupId(id))) {
        is ConfigRestore.Accepted -> "Restored $id (${result.warnings.size} warning(s))."
        is ConfigRestore.Rejected -> "Backup $id is invalid (${result.problems.size} problem(s))."
        is ConfigRestore.NotFound -> "Backup $id does not exist."
        is ConfigRestore.SourceChanged -> "The active file changed on disk; reload it before restoring."
        is ConfigRestore.Unavailable -> "Could not restore $id (${result.problem.category.name.lowercase()})."
    }
}
