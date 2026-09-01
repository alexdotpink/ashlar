# Migrate a configuration schema

Use a schema migration when a release cannot decode an old document as the current type. Adding a property with a default does not need a migration.

Keep one serializable type for each historical shape needed by the chain:

```kotlin
@Serializable
data class WaypointSettingsV1(
    val teleportDelayTicks: Long,
)

@Serializable
data class WaypointSettingsV2(
    val teleportDelay: Duration,
)

@Config(
    path = "waypoints.yml",
    schemaVersion = 3,
    unversionedSchema = 1,
)
@Serializable
data class WaypointSettings(
    val teleportDelay: Duration = 3.seconds,
    val maximumWaypoints: Int = 10,
)
```

Declare one pure top-level extension for each adjacent step:

```kotlin
@ConfigMigration(root = WaypointSettings::class, from = 1)
fun WaypointSettingsV1.toV2(): WaypointSettingsV2 =
    WaypointSettingsV2(teleportDelayTicks.ticks)

@ConfigMigration(root = WaypointSettings::class, from = 2)
fun WaypointSettingsV2.toV3(): WaypointSettings =
    WaypointSettings(
        teleportDelay = teleportDelay,
        maximumWaypoints = 10,
    )
```

KSP rejects a missing, duplicate, disconnected, or out-of-range step. Each source and target must have `@Serializable`. A migration must be public or internal, top-level, non-suspending, parameterless, and non-generic.

Set `unversionedSchema` only when existing files without `_ashlar-schema` have a known old shape. A schema 1 declaration accepts an absent marker without this setting. A declaration at schema 2 or later rejects an unmarked file unless `unversionedSchema` names the schema to adopt.

On load, Ashlar decodes each historical type and calls each migration in order. It validates the final value, backs up the old valid document, writes the migrated document, and only then publishes the value. If any step rejects or the backup or write fails, the old source stays active and no migrated value is published.

Do not delete a historical type or migration while installations at that schema still need to upgrade. A future `_ashlar-schema` value always rejects. Ashlar does not downgrade it.

See [schema rules](../reference/configuration.md#schema-marker-and-adoption) for the marker contract.
