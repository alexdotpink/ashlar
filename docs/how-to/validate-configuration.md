# Validate configuration values

Use `@ConfigValidation` for rules that depend on a complete decoded value. Keep single-field parsing rules in the field type or its serializer.

Add a top-level extension on `ConfigValidationScope<T>`:

```kotlin
@ConfigValidation
fun ConfigValidationScope<WaypointSettings>.validateWaypointSettings() {
    requireValue(
        current.maximumWaypoints in 1..100,
        WaypointSettings::maximumWaypoints,
    ) {
        "must be from 1 through 100"
    }

    requireValue(
        current.searchPageSize <= current.maximumWaypoints,
        WaypointSettings::searchPageSize,
    ) {
        "cannot exceed maximum-waypoints"
    }

    warnIf(
        current.maximumWaypoints > 80,
        WaypointSettings::maximumWaypoints,
    ) {
        "may create long waypoint listings"
    }
}
```

`requireValue` adds an error when its condition is false. Any error rejects startup, reload, update, or restore. `warnIf` adds a warning when its condition is true. Warnings appear in accepted outcomes, events, and inspection metadata.

Pass a property reference for every problem. Ashlar uses it to report the stable configuration key path. The generated linkage calls the function directly.

A validation function must be public or internal, top-level, non-suspending, parameterless, non-generic, and return `Unit`. Do not read files, resolve dependencies, access Paper, mutate state, or change `current` inside it.

Handle all expected results when an operation can reject:

```kotlin
when (val result = settings.reload()) {
    is ConfigReload.Accepted -> logger.info("Settings accepted")
    is ConfigReload.Rejected -> result.problems.forEach { problem ->
        logger.warning("${problem.key}: ${problem.message}")
    }
    is ConfigReload.Unavailable -> logger.warning(result.problem.message)
}
```

See [configuration diagnostics](../reference/configuration.md#diagnostics) for every category and field.
