# Watch a configuration document

Opt one declaration into file watching:

```kotlin
@Config(
    path = "waypoints.jsonc",
    reload = ConfigReloadMode.WATCH,
)
@Serializable
data class WaypointSettings(
    val maximumWaypoints: Int = 10,
)
```

Read the latest accepted value synchronously:

```kotlin
val limit = settings.current.maximumWaypoints
```

Collect `values` when work depends on distinct typed values:

```kotlin
launch {
    settings.values.collectLatest { value ->
        rebuildIndex(value.maximumWaypoints)
    }
}
```

Collect `events` when the code must also observe rejections, warnings, source revisions, or comment-only edits:

```kotlin
launch {
    settings.events.collect { event ->
        when (event) {
            is ConfigEvent.Accepted -> logger.info(
                "Accepted ${event.origin}, changed=${event.changed}",
            )
            is ConfigEvent.Rejected -> logger.warning(
                event.problems.joinToString { it.message },
            )
            is ConfigEvent.Unavailable -> logger.warning(event.problem.message)
        }
    }
}
```

Own collector jobs through a `PluginComponent` so plug-in shutdown cancels them. Configuration flows do not grant Paper ownership. Enter `withGlobal`, `withRegion`, or `withEntity` before touching server-owned state.

The watcher coalesces editor file events and accepts only complete parsed, migrated, decoded, and validated documents. A rejected edit leaves `current` unchanged. A later valid edit recovers without restarting the watcher.

A comment-only edit advances the accepted source revision and emits `ConfigEvent.Accepted(changed = false)`. It does not emit another value through `values`.

Use `Configurations.inspect()` to check `watcherStatus`. Use `ConfigHandle.reload()` for an immediate attempt even when the declaration uses `WATCH`.
