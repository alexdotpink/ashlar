# Typed routes, fragments, and command graphs

## Generated routes

For `WaypointCommands`, KSP generates an injectable `WaypointCommandsRoutes`. Each handler method returns an immutable `CommandRoute` with a canonical command string and semantic identity:

```kotlin
val route = routes.list(order = WaypointOrder.NAME, page = 2)
val command = route.command
val click = route.runLink()
val suggestion = route.suggestLink()
```

Routes always use canonical literals, never aliases. Codec `encode` functions supply argument values. Values are quoted and escaped when required. Control characters are rejected.

For a `@Sensitive` parameter, pass `sensitive(value)`. The command string still contains the encoded value so Minecraft can execute it, but the semantic identity stores a SHA-256 digest. Do not log `CommandRoute.command` when it may contain secrets.

`@Restricted` is reserved for restricted route visibility and is not consumed by the current command compiler.

## Dispatching

`CommandDispatcher.invoke(route, sender)` executes a generated route as an existing framework `CommandSender`. `dispatch` provides the corresponding asynchronous submission path. This preserves parsing, policies, observers, and response handling; it is not a direct handler call.

## Fragments

`@CommandFragment(root = OwnerCommands::class)` contributes routes to another command root. The root must have exactly one non-fragment owner. All fragments are generated independently, then merged before registration. Alias collisions and ambiguous merged syntax fail startup.

Use `@ExcludeCommandContributions(types = [Fragment::class])` on the plug-in entrypoint to remove a library fragment or command set from one plug-in.

## Graph configuration

A function marked `@ConfigureCommandGraph` receives the injected `CommandGraph` during startup:

```kotlin
@ConfigureCommandGraph
fun configure(graph: CommandGraph) {
    graph.redirect(routes.go(), routes.teleport())
    graph.fork(routes.refreshAll()) {
        listOf(routes.refreshMap(), routes.refreshMenus())
    }
    graph.external(routes.version(), "version")
    graph.external(routes.map(), "map", optional = true)
}
```

`redirect` targets one typed route. `fork` computes supervised typed targets. `external` points to vanilla or third-party syntax that cannot have a generated Kotlin type. A missing required external command fails startup; a missing optional command removes the edge with a warning. A route can have one graph edge.

The graph freezes before Paper registration. Mutating it afterward fails.

## Aliases and migrations

`@Commands.aliases` and `@Command.aliases` are required aliases: collisions fail startup. Root `optionalAliases` are omitted when already occupied.

`@CommandRenamed(from, untilVersion)` retains a temporary old literal while the command set's `schemaVersion` is below `untilVersion`. Raise the schema version to expire the migration spelling.

## Client refresh

`CommandRefresh.refresh(PlayerRef)` and its iterable overload ask Paper to update the command tree only for affected players, using entity-safe access.

`CommandRequirement` defines `id`, synchronous `isAllowed`, and `subscribe(refresh)`. It is a public extension contract reserved for observable dynamic requirements; the current command compiler does not attach it to generated branches. Use permissions and policies for current command behavior.
