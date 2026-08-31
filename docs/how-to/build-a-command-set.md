# Build a command set

## Declare the root and routes

```kotlin
@Commands(
    name = "waypoint",
    aliases = ["wp"],
    permission = "waypoints.use",
)
class WaypointCommands(
    private val store: WaypointStore,
) {
    /** Lists public waypoints. */
    fun list(page: Int = 1): Component = renderPage(store.snapshot(), page)

    /** Searches names and descriptions. */
    fun search(query: GreedyText, options: SearchOptions): Component =
        renderSearch(store.search(query, options))

    @Group(permission = "waypoints.manage")
    inner class Manage {
        /** Deletes a waypoint after confirmation. */
        @Confirm
        fun delete(waypoint: Waypoint): String {
            store.delete(waypoint)
            return "Deleted ${waypoint.name}."
        }
    }
}
```

## Add named options

```kotlin
@Options
data class SearchOptions(
    @Option(short = 'l') val limit: Int = 20,
    @Option(short = 'v') val verbose: Boolean = false,
    @Option(name = "tag") @Repeated val tags: List<String> = emptyList(),
)
```

The runtime accepts `--limit 5`, `--limit=5`, `-l 5`, `--verbose`, `--no-verbose`, and repeated `--tag` values. A standalone `--` ends option parsing.

## Add KDoc

KSP copies the first KDoc sentence, `@param` entries, and every `@example` into help metadata. Enable strict documentation in the build to make missing summaries compile errors.

## Use the generated routes class

KSP generates `WaypointCommandsRoutes`. Inject it into menus or services:

```kotlin
class WaypointMenu @Inject constructor(
    private val routes: WaypointCommandsRoutes,
) {
    fun listButton(page: Int): Component = Component.text("Next")
        .clickEvent(routes.list(page).runLink())
}
```

The command set is complete when the handler types describe the grammar, help renders each route, and route links compile without raw command strings.
