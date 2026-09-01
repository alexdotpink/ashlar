# Update a configuration without overwriting operator edits

Call `update` with a non-suspending transform of the current immutable value:

```kotlin
when (val result = settings.update { current ->
    current.copy(maximumWaypoints = 20)
}) {
    is ConfigWrite.Accepted -> logger.info("Saved ${result.value.maximumWaypoints}")
    is ConfigWrite.Unchanged -> Unit
    is ConfigWrite.Rejected -> report(result.problems)
    is ConfigWrite.SourceChanged -> logger.warning(
        "The settings file changed outside the plug-in. Reload it before retrying.",
    )
    is ConfigWrite.Unavailable -> logger.warning(result.problem.message)
}
```

Ashlar runs the transform once. Keep it deterministic and free of I/O, suspension, Paper access, and side effects.

An accepted update validates the transformed value, checks that the active file still has the accepted source revision, creates a predecessor backup, writes through a temporary sibling, replaces the active document, and then publishes the new value.

`Unchanged` means the transform returned an equal value. Ashlar creates no write, backup, event, or new source revision.

`Rejected` means validation found errors. The active source and value remain unchanged.

`SourceChanged` means an editor or another process changed the source after the last accepted load. Ashlar does not merge or overwrite that edit. Call `reload()`, resolve any rejection, inspect the new `current`, and then issue a new update based on it.

Do not add a second direct file writer beside the handle. Writes outside Ashlar are operator edits and trigger the same stale-source protection.

See [back up and restore configuration](restore-configuration.md) for retained predecessors.
