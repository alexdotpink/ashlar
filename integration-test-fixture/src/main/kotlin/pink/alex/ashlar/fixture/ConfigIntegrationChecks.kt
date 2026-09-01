package pink.alex.ashlar.fixture

import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import pink.alex.ashlar.config.Config
import pink.alex.ashlar.config.ConfigEvent
import pink.alex.ashlar.config.ConfigEventOrigin
import pink.alex.ashlar.config.ConfigHandle
import pink.alex.ashlar.config.ConfigOperationStatus
import pink.alex.ashlar.config.ConfigReload
import pink.alex.ashlar.config.ConfigReloadMode
import pink.alex.ashlar.config.ConfigValidation
import pink.alex.ashlar.config.ConfigValidationScope
import pink.alex.ashlar.config.ConfigWatcherStatus
import pink.alex.ashlar.config.ConfigWrite
import pink.alex.ashlar.config.Configurations
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** Integration settings whose handle must exist before automatic components are constructed. */
@Serializable
@Config(path = "fixture-config.jsonc", reload = ConfigReloadMode.WATCH, backups = 2)
public data class FixtureConfig(
    /** Enables the fixture's deterministic configuration exercise. */
    val enabled: Boolean = true,
    /** Small bounded value changed by the integration exercise. */
    val limit: Int = 4,
)

@ConfigValidation
internal fun ConfigValidationScope<FixtureConfig>.validateFixtureConfig() {
    requireValue(current.limit in 1..10, FixtureConfig::limit) {
        "limit must be between 1 and 10"
    }
}

internal suspend fun exerciseConfiguration(
    handle: ConfigHandle<FixtureConfig>,
    configurations: Configurations,
    dataDirectory: Path,
    results: ProbeResults,
) {
    val path = dataDirectory.resolve("fixture-config.jsonc")
    check(Files.isRegularFile(path)) { "The missing configuration was not created during startup" }
    val created = Files.readString(path)
    check(created.contains("\"_ashlar-schema\": 1")) { "The created source has no schema marker: $created" }
    check(created.contains("Integration settings whose handle must exist")) {
        "The created JSONC source has no generated root comment: $created"
    }
    check(created.contains("Enables the fixture's deterministic configuration exercise")) {
        "The created JSONC source has no generated property comment: $created"
    }
    check(handle.current == FixtureConfig()) { "The default configuration was not injected" }
    results.record("config:created-defaults")

    val written = handle.update { current -> current.copy(limit = 8) }
    check(written is ConfigWrite.Accepted && written.value.limit == 8) {
        "The explicit update was not accepted: $written"
    }
    check(handle.backups().size == 1) { "The explicit update did not retain its predecessor" }
    results.record("config:updated")

    replaceAtomically(path, "{ invalid jsonc }")
    val rejected = handle.reload()
    check(rejected is ConfigReload.Rejected && handle.current.limit == 8) {
        "An invalid reload did not retain the accepted value: $rejected"
    }
    results.record("config:invalid-retained")

    replaceAtomically(
        path,
        """
        // operator-only comment; semantic values are unchanged
        {
          "_ashlar-schema": 1,
          "enabled": true,
          "limit": 8
        }
        """.trimIndent() + "\n",
    )
    val commentOnly = handle.reload()
    check(commentOnly is ConfigReload.Accepted && !commentOnly.changed && commentOnly.value.limit == 8) {
        "A comment-only reload was not accepted as unchanged: $commentOnly"
    }
    val lastEvent = handle.events.replayAcceptedManualEvent()
    check(!lastEvent.changed) { "The comment-only accepted event was reported as a value change" }
    results.record("config:comment-only")

    val inspection = configurations.inspect().single { candidate -> candidate.path == "fixture-config.jsonc" }
    check(inspection.status == ConfigOperationStatus.ACCEPTED)
    check(inspection.watcherStatus == ConfigWatcherStatus.WATCHING)
    check(!inspection.toString().contains("operator-only")) { "Inspection leaked source contents" }
    results.record("config:watching")
}

private suspend fun kotlinx.coroutines.flow.Flow<ConfigEvent<FixtureConfig>>.replayAcceptedManualEvent():
    ConfigEvent.Accepted<FixtureConfig> = first { event ->
        event is ConfigEvent.Accepted<*> && event.origin == ConfigEventOrigin.MANUAL_RELOAD
    } as ConfigEvent.Accepted<FixtureConfig>

private fun replaceAtomically(path: Path, source: String) {
    val temporary = path.resolveSibling("${path.fileName}.fixture.tmp")
    Files.writeString(temporary, source)
    Files.move(
        temporary,
        path,
        StandardCopyOption.ATOMIC_MOVE,
        StandardCopyOption.REPLACE_EXISTING,
    )
}
