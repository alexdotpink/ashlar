# Build a stateful chest menu

This menu pages through waypoints, opens a detail screen, and returns the selected waypoint as a typed result.

## Keep the renderer ordinary Kotlin

```kotlin
sealed interface WaypointRoute {
    data object List : WaypointRoute
    data class Details(val waypoint: Waypoint) : WaypointRoute
}

context(MenuScope)
fun WaypointPicker(
    waypoints: List<Waypoint>,
    icon: (Waypoint) -> ItemSpec,
) {
    navigator<WaypointRoute>(WaypointRoute.List) {
        screen<WaypointRoute, WaypointRoute.List> {
            chest("Choose a waypoint", rows = 4) {
                val page = paged(waypoints, Waypoint::id, pageSize = 27)
                page.items(rows(0..2)) { waypoint, index ->
                    slot(index) {
                        item = icon(waypoint)
                        onPrimary { navigator.push(WaypointRoute.Details(waypoint)) }
                    }
                }
                page.previous(27, previousIcon)
                page.next(35, nextIcon)
                closeControl(31, closeIcon)
            }
        }

        screen<WaypointRoute, WaypointRoute.Details>(nativeClose = NativeClose.BACK) { route ->
            chest(route.waypoint.name, rows = 3) {
                staticItem(13, icon(route.waypoint))
                backControl(18, backIcon, navigator)
                slot(26) {
                    item = chooseIcon
                    onPrimary { finish(route.waypoint) }
                }
            }
        }
    }
}
```

The domain ID keys repeated components. Page state survives list rerenders. Covering the list releases its active inventory declarations; backing out restores the retained page.

## Open it from a suspending handler

```kotlin
suspend fun chooseWaypoint(player: PlayerRef): Waypoint? =
    when (val result = menus.choose<Waypoint>(player) {
        WaypointPicker(store.snapshot(), ::waypointIcon)
    }) {
        is MenuChoice.Selected -> result.value
        is MenuChoice.Closed -> null
    }
```

The command or component coroutine owns the session. Cancellation closes it. No mutable session handle needs cleanup.

## Add live data

Replace the snapshot argument with a Flow when the list changes while open:

```kotlin
context(MenuScope)
fun LiveWaypointPicker(store: WaypointStore) {
    val waypoints by collectAsState(store.snapshots, emptyList())
    WaypointPicker(waypoints, ::waypointIcon)
}
```

Render remains synchronous. The framework starts and stops collection with the keyed component.

## Verify the behavior

Test route changes, retained page state, typed selection, native-close back behavior, and cleanup through `menuTest`. Run the sample or integration fixture on both Paper and Folia before changing native host behavior.
