# Compose a large command root

## Split routes into fragments

Keep one root owner:

```kotlin
@Commands(name = "waypoint")
class WaypointCommands
```

Add independently compiled fragments:

```kotlin
@CommandFragment(WaypointCommands::class)
class WaypointAdminCommands {
    @Group(permission = "waypoints.admin")
    inner class Admin {
        /** Reloads waypoint data. */
        fun reload(): String = "Reloaded."
    }
}
```

The runtime merges all fragment routes atomically. It requires exactly one owner and rejects ambiguous merged syntax.

## Add graph edges

Inject the owner's generated routes into a marked startup function:

```kotlin
@ConfigureCommandGraph
fun configure(graph: CommandGraph) {
    graph.redirect(routes.go(), routes.teleport())
    graph.fork(routes.refreshAll()) {
        listOf(routes.refreshMap(), routes.refreshMenus())
    }
    graph.external(routes.version(), "version")
    graph.external(routes.optionalMap(), "map", optional = true)
}
```

Required missing external commands fail startup. Optional missing commands log a warning and remove the edge. The graph freezes before Paper command registration.

Use `CommandDispatcher` when application code must execute a generated route as an existing `CommandSender`.

## Exclude a library contribution

Annotate the plug-in entrypoint with `@ExcludeCommandContributions(TheFragment::class)` for a command contribution or `@ExcludeContributions(TheComponent::class)` for a generated root component.
