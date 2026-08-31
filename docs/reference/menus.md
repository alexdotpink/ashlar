# Menus reference

Enable menus through the managed build:

```kotlin
frameworkPlugin {
    menus()
}
```

This enables `framework-menus` and `framework-items`. The module uses plain Kotlin. It has no annotations, reflection, processor, or generated menu classes.

## Sessions

Inject `PlayerMenus`. `open` suspends until the logical session ends:

```kotlin
when (val result = menus.open(player) { WaypointMenu(store) }) {
    is MenuOpen.Closed -> logClose(result.reason)
    MenuOpen.Rejected -> tellPlayer("You already have a menu open.")
}
```

One independent session may own a player. `MenuOpenConflict.REPLACE` is the default and closes the previous session with `MenuClose.Replaced`. `REJECT` leaves it open. Cancelling the caller closes native presentation and rethrows the cancellation. `PlayerMenus.close(player)` atomically closes an active session. `inspect(player)` returns a redacted immutable diagnostic view.

`MenuClose` distinguishes player close, external inventory replacement, explicit close, replacement, disconnect, death, kick, caller cancellation, plug-in shutdown, and an unexpected root failure.

Use `choose<T>` when selection is the result:

```kotlin
val choice: MenuChoice<Waypoint> = menus.choose(player) {
    chest("Choose a waypoint", rows = 3) {
        slot(13) {
            item = waypointIcon
            onPrimary { finish(waypoint) }
        }
    }
}
```

`MenuChoice.Selected` carries `T`. Closing before selection returns `MenuChoice.Closed`. Ordinary `open` never has a hidden generic result.

## Synchronous render

The render lambda describes one immutable host tree. It must finish synchronously and have no side effects:

```kotlin
context(MenuScope)
fun WaypointMenu(waypoints: List<Waypoint>) {
    chest(Component.text("Waypoints"), rows = 4) {
        flow(
            region = region(0..2, 0..8),
            items = waypoints,
            key = Waypoint::id,
        ) { waypoint, index ->
            slot(index) {
                item = waypoint.icon
                onPrimary { teleport(waypoint) }
            }
        }
    }
}
```

Exactly one `chest` must appear in a successful render. Chest rows range from one through six. Slots use a zero-based index or zero-based row and column. `row`, `rows`, and `region` create ordered `SlotRegion` values. `flow` preserves region order and keys every repeated child by domain identity. Duplicate keys and overflow fail validation. Set `RegionOverflow.CLIP` only when truncation is intentional.

`component(key)` gives descendants stable identity. State, effects, actions, navigation, and failures belong to that identity. Keys must be stable and unique among siblings. A physical slot has one owner. Slot collisions and out-of-host indexes throw `MenuValidationException` before reconciliation.

`ItemSpec` has structural equality. A keyed `paper` mutation uses its explicit mutation key for equality, so change that key when the rendered output changes.

## State and external flows

State identity comes from the delegated property name inside its keyed component:

```kotlin
component("waypoint-list") {
    var page by state(0)
    val waypoints by collectAsState(store.snapshots, emptyList())
}
```

Reordering calls does not move state. Reusing a delegated name in one component fails. Synchronous changes made by one action enter the conflated render queue and produce the newest state rather than exposing half-built trees.

`collectAsState(flow, initial)` owns collection for the component lifetime. It keeps render synchronous. Removing the component cancels collection. Flow failures reach the nearest error boundary.

## Actions and gestures

An action slot displays an optional `ItemSpec` and owns handlers:

```kotlin
slot(row = 1, column = 4) {
    item = saveIcon
    onPrimary { interaction -> save(interaction.player) }
    onSecondary { feedback(MenuFeedback(helpText, MenuFeedbackSeverity.INFO, interaction.slot)) }
    on(MenuGestureKind.NUMBER_KEY) { interaction -> usePreset(interaction) }
    onGesture { interaction -> audit(interaction.gesture) }
}
```

Specific handlers win over `onGesture`. Plug-in code receives immutable `MenuInteraction`, never a mutable Paper inventory event. It contains the player, committed revision, host and player slots, player-inventory snapshots, concrete `MenuGesture`, clicked item, and cursor snapshot.

`MenuGesture` covers primary, secondary, middle, shifted clicks, number keys, offhand swap, drops, double-click, drag, creative input, and outside clicks. `MenuDispatch` reports accepted, stale, empty, unsupported, already-running, transaction-rejected, or closed input.

Suspending actions are keyed by component, slot, and gesture handler:

| Concurrency | Behavior |
| --- | --- |
| `SINGLE_FLIGHT` | Reject another invocation of the same action while it runs |
| `RESTART_LATEST` | Cancel the previous invocation and start the new one |
| `PARALLEL` | Run independent invocations concurrently |

The default is `SINGLE_FLIGHT`. Actions can emit typed feedback, close the session, or finish a typed choice through `MenuActionScope`. Unexpected failures reach the nearest error boundary.

## Effects

Effects start only after their render commits:

```kotlin
effect(account.id) {
    val registration = observer.register(account.id)
    onDispose(registration::close)
}

launchedEffect(account.id) {
    refreshUntilCancelled(account.id)
}
```

An unchanged component, effect kind, and key keeps existing work. A changed key, removed component, replaced screen, or closed session disposes the synchronous effect and cancels the launched effect. `effect` cleanup runs exactly once. Use `collectAsState` for a Flow whose newest value drives render.

## Focused input

`MenuActionScope.withFocusedInput` temporarily hides native presentation while a suspending input operation runs, then remounts the newest committed render. The logical menu session, state, navigation, and effect ownership remain intact. Closing or cancelling the session cancels focused input through ordinary coroutine ownership.

`menus.input.focusedChatInput` connects this lifecycle to `PlayerInput`. Install the input module and pass its explicit capability:

```kotlin
onPrimary { interaction ->
    val query = focusedChatInput(
        input = playerInput,
        player = interaction.player,
        prompt = Component.text("Search for what?"),
    ) {
        text.trim().takeIf(String::isNotEmpty)?.let(::accept) ?: retry("Enter a search term.")
    }
    search = query
}
```

`promptControl` wraps an arbitrary typed chat parser and accepted callback. `searchControl` supplies non-blank trimmed text behavior. Neither reserves a chat keyword or creates another input registry.

## Error boundaries

`errorBoundary` contains descendant render, action, effect, and collected-Flow failures:

```kotlin
errorBoundary(
    fallback = { failure, retry ->
        chest("Menu failed", rows = 1) {
            slot(4) {
                item = retryIcon
                onPrimary {
                    repairState()
                    retry.retry()
                }
            }
        }
    },
) {
    RiskyMenu()
}
```

The fallback gets `MenuFailure` with the keyed component path and original cause. A render failure keeps the last committed native tree until a fallback commits. A root failure without a boundary closes with `MenuClose.Failed`.

## Navigation

Use an ordinary sealed route model:

```kotlin
sealed interface Route {
    data object Home : Route
    data class Details(val id: String) : Route
}

navigator<Route>(Route.Home) {
    screen<Route, Route.Home> {
        HomeScreen(onOpen = navigator::push)
    }
    screen<Route, Route.Details>(nativeClose = NativeClose.BACK) { route ->
        DetailsScreen(route.id, navigator)
    }
}
```

`push` covers the current route, `replace` removes it, `back` reveals the previous route, and `close` ends the session. Covered screens retain route values and local state cells but release active effects, Flow collection, and inventory declarations. Revealing one renders current external data and restarts its work.

Native close ends the whole session by default. `NativeClose.BACK` converts a native close on that screen into navigator back when history exists. At the root it still ends the session.

## Menu locals

`MenuLocal<T>` carries presentation values through ordinary Kotlin composition:

```kotlin
val MenuTheme = menuLocal("theme") { DefaultTheme }

provide(MenuTheme, darkTheme) {
    val theme = MenuTheme.current()
    ThemedControls(theme)
}
```

Locals belong in render context: theme, locale-specific presentation, spacing, or standard-component defaults. Services and mutable domain state remain explicit function or constructor dependencies. Inspection records local names and redacted values.

## Reconciliation and diagnostics

Every successful render produces a `MenuRenderSnapshot` with a monotonically increasing revision. `MenuReconciliation.Remount` changes host kind or capacity. `Update` reports title and slot changes for the same host. Action dispatch validates the interaction revision, so a stale client gesture cannot invoke a new handler tree.

`MenuInspection` exposes the current semantic snapshot, a bounded `MenuTrace`, pending action identities, and active effect identities. Trace events cover committed renders, action start and completion, transaction commit and rejection, feedback, and close. Values and item payloads are summarized or omitted.

The public `MenuNativeHost`, `MenuNativeCallbacks`, `MenuNativeHostFactory`, and native transaction methods are adapter seams. Plug-ins normally inject `PlayerMenus`; tests use `framework-menus-test`.

## Standard components

`framework-menus.standard` ships components built through the same public declarations as plug-in code:

- `contentState` selects loading, empty, failed, or ready content.
- `paged` owns a clamped page. `scrolling` owns a clamped fixed-size window that moves one item at a time. Both supply keyed items and controls.
- `toggle`, `numberStepper`, and `confirmation` wire caller-authored items to actions.
- `selection`, `tab`, `tabs`, `staticItem`, `closeControl`, and `backControl` cover common controls.
- `loading`, `emptyContent`, and `failedContent` provide ordinary state slots; the failed variant may retry.
- `filler` and `border` claim every supplied slot. Pass only unclaimed regions.

These functions do not bypass collision checks, state rules, or action concurrency.

## Shipped hosts and limits

The semantic module has a typed host catalogue for indexed containers, anvil, merchant, furnace-family, brewing, crafting, crafter, enchantment, grindstone, smithing, loom, cartography, stonecutter, beacon, and lectern. See [Menu hosts](menu-hosts.md).

The shipped native adapter supports chest hosts on Paper and Folia. Other host declarations are currently server-free semantic models; their Paper/Folia adapters remain unfinished. There is no raw generic inventory escape hatch. Connected-client conservation, focused-input remount, and chest remount behavior still need full native-client proof; server fixtures and the semantic test module cover the current adapter and engine contracts.
