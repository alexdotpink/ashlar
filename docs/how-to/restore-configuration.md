# Restore a configuration backup

List the retained valid predecessors:

```kotlin
val backups: List<ConfigBackup> = settings.backups()
```

The list contains metadata only. Pick an identifier and request an explicit restore:

```kotlin
val backup = backups.firstOrNull() ?: return

when (val result = settings.restore(backup.id)) {
    is ConfigRestore.Accepted -> logger.info("Restored settings")
    is ConfigRestore.Rejected -> report(result.problems)
    is ConfigRestore.NotFound -> logger.warning("That backup no longer exists")
    is ConfigRestore.Unavailable -> logger.warning(result.problem.message)
}
```

Ashlar parses the backup, runs supported migrations, validates the resulting current type, backs up the current active document, installs the restored source, and then publishes it. A rejected or unavailable restore changes neither the active source nor `current`.

Backups are created before accepted updates, persisted migrations, and restores. `backups = 5` is the declaration default. Set `backups = 0` only when the plug-in deliberately needs no retained predecessors:

```kotlin
@Config(path = "generated.json", backups = 0)
```

Ashlar never selects a backup automatically when the active source is invalid. Keep restore behind an administrator operation with an explicit target and report the typed result.

Backup identifiers are opaque. Do not construct paths from `ConfigBackupId.value` or read the backup directory directly.
