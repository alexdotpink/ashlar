# Menus module design

Status: core chest runtime and typed semantic host catalogue implemented; non-chest native adapters and native-client proof are pending

The menus module is a declarative, stateful framework for Minecraft inventory interfaces. Plug-in code describes the current screen from immutable state. The runtime owns keyed state, reconciliation, native input, item safety, actions, navigation, effects, storage transactions, lifecycle, diagnostics, and Paper/Folia adaptation.

It borrows React's useful properties: declarative output, stable component identity, scoped context, effects, boundaries, and testable reconciliation. It does so without positional hooks, a compiler plug-in, or a browser-shaped layout model.

## Goals

- Make ordinary action menus concise and reusable.
- Support every current player-openable inventory form through typed host semantics.
- Make editable and persistent storage safe across every native click, drag, swap, collect, close, and disconnect path.
- Retain typed local state and navigation without hidden mutable menu handles.
- Compose menus from ordinary context-aware Kotlin functions.
- Integrate coroutines and Flow without suspending render or leaking server ownership.
- Ship polished standard components for repeated menu patterns.
- Make plug-in behavior deterministic to test without a server.
- Make live sessions inspectable enough for agents to diagnose from a semantic export.

## Non-goals

- The module does not expose mutable Paper inventory events as its extension interface.
- It does not use annotations, KSP, reflection, generated bindings, or a Compose compiler plug-in.
- It does not emulate CSS, flexbox, constraints, or responsive browser layout over fixed Minecraft slots.
- It does not allow render-time side effects or suspending render functions.
- It does not hide Paper/Folia ownership behind a suspending "player thread" dispatcher.
- It does not let two declarations silently compete for one physical slot.
- It does not implement chat parsing; focused textual input delegates to the input module.
- It does not put custom-item behavior inside menu or item definitions.

## Public artifacts

Consumers choose from three artifacts:

```kotlin
frameworkPlugin {
    menus()
}

dependencies {
    testImplementation(framework.menusTest)
}
```

- `framework-items` contains the shared item model and is enabled transitively.
- `framework-menus` contains the production runtime, typed hosts, storage, recovery, standard components, and diagnostics.
- `framework-menus-test` contains the deterministic harness and assertions.

Internal engine and adapter projects may exist without becoming consumer choices. Command inspection and input prompts activate when those framework capabilities are installed.

## Opening a menu session

`PlayerMenus.open` suspends for one logical session:

```kotlin
val close: MenuClose = menus.open(player) {
    WaypointMenu(store)
}
```

The caller coroutine owns the session. Cancelling it ends the session and cleans up visible presentation, actions, effects, subscriptions, and navigation. `PlayerMenus.close(player)` atomically ends an active session without exposing a mutable handle.

One framework menu session may be active per player and embedded framework instance. Opening another defaults to replacing the old session with `MenuClose.Replaced`. A caller may explicitly choose rejection. Independent sessions are never placed on a hidden stack; intentional history belongs to the navigator.

`open` returns a typed reason covering player close, external inventory replacement, explicit close, replacement, disconnect, death, kick, caller cancellation, plug-in shutdown, and unhandled failure.

Selection is a separate operation so ordinary menus do not carry meaningless result types:

```kotlin
when (
    val result = menus.choose<WaypointId>(player) {
        WaypointPicker(store) { waypoint ->
            finish(waypoint.id)
        }
    }
) {
    is MenuChoice.Selected -> use(result.value)
    is MenuChoice.Closed -> handle(result.reason)
}
```

## Declarative composition

Reusable menu pieces are ordinary Kotlin functions with a named `MenuScope` context parameter:

```kotlin
context(menu: MenuScope)
fun WaypointMenu(store: WaypointStore) {
    chest(title = "Waypoints", rows = 6) {
        WaypointGrid(store)
        NavigationRow()
    }
}
```

Constructor and function parameters carry repositories and services. The framework needs no annotated menu class, generated wrapper, inheritance hierarchy, or registry of component types.

`component(key)` introduces stable identity only where local state, effects, or repeated children need it:

```kotlin
context(menu: MenuScope)
fun WaypointGrid(store: WaypointStore) =
    component(key = "waypoint-grid") {
        var page by state(1)
        var selected by state<WaypointId?>(null)
        // ...
    }
```

State identity is the keyed component path plus delegated property name. It is not the order in which `state` happens to execute. Adding a state declaration above another does not exchange their values. Duplicate component keys or duplicate state names within one identity fail the render with a component trace.

Repeated children always declare domain keys:

```kotlin
items(waypoints, key = Waypoint::id) { waypoint ->
    WaypointSlot(waypoint)
}
```

Reordering preserves the correct child's state. Missing or duplicate item keys fail rather than falling back to list position.

## Render and reconciliation

A render is synchronous and side-effect free. It produces an immutable semantic tree containing the concrete host, component identities, slots, items, actions, storage bindings, routes, locals, and effects.

The runtime validates a complete tree before committing it. Reconciliation then changes only the native host properties and slots required to match the new tree. Item comparison uses item specs and snapshots, not mutable stack identity.

State changes made during one synchronous action, effect callback, or external notification batch into one render:

```kotlin
onPrimary {
    page++
    selected = null
    error = null
}
```

Flow bursts conflate to the newest value while a render is pending. Reconciliation is serialized, so no player observes partially applied trees or overlapping diffs. A gesture includes the committed render revision; input against a stale revision is rejected or reinterpreted only by an explicit host rule, never dispatched to a newly occupied slot.

Each physical slot has one owner. Accidental collisions fail with the coordinate and both component paths. Intentional augmentation uses typed `SlotModifier` values passed through component APIs:

```kotlin
WaypointSlot(
    waypoint,
    modifier = favoriteBadge(waypoint.isFavorite) +
        pulseWhen(feedback.targets(waypoint.id)),
)
```

The system has no implicit z-index, last-writer-wins rule, or numeric slot priority.

## State from Flow

External reactive state uses lifecycle-owned collection:

```kotlin
val waypoints by collectAsState(
    store.publicWaypoints,
    initial = emptyList(),
)
```

Collection belongs to the keyed component and cancels when its key changes, it leaves the active screen, or the session ends. Renders do not suspend for database work and callers do not manually invalidate inventories.

An async state type and the standard `contentState` component express loading, empty, failed, and ready presentation without adding React-style Suspense to Kotlin control flow.

## Effects

Effects begin only after a render reconciles successfully:

```kotlin
effect(key = subscriptionKey) {
    val subscription = service.subscribe()
    onDispose(subscription::close)
}

launchedEffect(key = animationKey) {
    while (isActive) {
        delay(250.milliseconds)
        frame++
    }
}
```

Changing the key, removing the component, covering or removing its screen, closing the session, or disabling the plug-in disposes or cancels the old effect before a replacement starts. Covered navigation screens release their rendered inventory, Flow subscriptions, and effects while retaining route values and local state cells.

## Error boundaries

A keyed error boundary contains unexpected descendant render, action, and effect failures:

```kotlin
errorBoundary(
    fallback = { failure, retry ->
        MenuFailureScreen(failure, retry)
    },
) {
    WaypointMenu(store)
}
```

The last successfully committed native tree remains until the fallback reconciles. `retry` clears the captured failure and renders the subtree again. Failure in an unbounded subtree or in the fallback is reported through the framework failure pipeline and ends the session as `MenuClose.Failed`.

Expected domain rejection, invalid input, stale gestures, denied storage rules, and commit rejection are typed outcomes rather than boundary failures.

## Scoped presentation context

Kotlin context parameters establish compile-time render capability. Typed menu locals carry dynamic values through a subtree:

```kotlin
val WaypointTheme = menuLocal<MenuTheme>("waypoint-theme") {
    MenuTheme.default
}

provide(WaypointTheme, supporterTheme) {
    WaypointGrid(waypoints)
}
```

Framework locals cover viewer identity, locale, host capabilities, item presentation, feedback presentation, and navigator where present. Plug-in locals are appropriate for theme, messages, density, or other presentation policy. Repositories, databases, and domain services remain explicit dependencies; menu locals are not ambient DI. Values live in the immutable render tree, not thread-local storage.

## Layout

Chest-like hosts use exact slots, row/column coordinates, and bounded regions:

```kotlin
chest(title = "Waypoints", rows = 6) {
    slot(index = 0) { /* ... */ }
    slot(row = 0, column = 1) { /* ... */ }

    flow(
        region = rows(1..4),
        items = waypoints,
        key = Waypoint::id,
    ) { waypoint ->
        WaypointSlot(waypoint)
    }
}
```

A region is an explicit ordered set of slots. Flow places keyed children in that order and reports overflow unless the caller selects a clipping or pagination component. The module does not pretend a nine-column fixed grid is responsive layout.

## Concrete hosts

Every inventory form has a concrete host type exposing its real slots, properties, and protocol. The catalogue covers the current player-openable inventory screens in the pinned Paper line, implemented and contract-tested one host at a time. There is no generic `InventoryType` escape hatch that weakens typed semantics.

Examples include:

```kotlin
anvil(title = "Rename waypoint") {
    left(input)
    right(catalyst)
    renameText { draftName = it }
    result(item = renamedToken(draftName), cost = 3) {
        onTake { proposal -> repository.rename(proposal, draftName) }
    }
}
```

```kotlin
merchant(title = text("Waypoint broker")) {
    offers(offers, key = Offer::id) { offer ->
        trade(
            firstCost = offer.price,
            secondCost = offer.token,
            result = offer.reward,
            uses = offer.uses,
            maximumUses = offer.limit,
        ) {
            onTrade(shop::commit)
        }
    }
}
```

Processing hosts expose typed progress and slot roles. They do not secretly tick, consume, or produce items; declared state and effects own processing behavior.

Title changes reconcile in place where the native host supports them. A capacity or host-kind change transparently remounts the native inventory while retaining the logical session and valid keyed state. Remount waits for conflicting transactions. If the new tree cannot represent committed storage, it fails through the nearest error boundary instead of discarding or relocating items.

## Typed navigation

Navigation uses ordinary sealed route values:

```kotlin
sealed interface WaypointScreen {
    data object Browser : WaypointScreen
    data class Details(val id: WaypointId) : WaypointScreen
    data class Rename(val id: WaypointId) : WaypointScreen
}
```

```kotlin
navigator(initial = WaypointScreen.Browser) {
    screen<WaypointScreen.Browser> {
        WaypointBrowser()
    }

    screen<WaypointScreen.Details>(
        nativeClose = NativeClose.BACK,
    ) { route ->
        WaypointDetails(route.id)
    }
}
```

`push`, `replace`, and `back` are typed operations. Covered screens retain route values and local state but release native declarations and owned work. Returning rerenders from current external state, so pagination and selection survive without displaying stale data.

Player-initiated native close defaults to ending the complete session. A screen may opt into `NativeClose.BACK`; a genuine player close then pops one route, and the root still closes. Internal remount, focused-input suspension, another plug-in opening an inventory, disconnect, death, kick, and replacement are distinct transitions and never trigger Back accidentally.

## Focused input

The optional input integration temporarily suspends native menu presentation while retaining the logical session:

```kotlin
when (
    val answer = prompt.chat(
        message = text("What should I search for?"),
    ) {
        accept { raw ->
            raw.trim()
                .takeIf(String::isNotEmpty)
                ?.let(::accept)
                ?: retry(text("Enter at least one character."))
        }
    }
) {
    is InputResult.Accepted -> query = answer.value
    InputResult.Cancelled,
    InputResult.Expired,
    -> Unit
}
```

The input module owns parsing, consumption, retry, timeout, conflicts, pass-through, and disconnect. Visibility-dependent menu effects pause. Explicit persistent storage observation may continue. Completion rerenders and remounts from current state. Anvil and later focused menu inputs use the same result vocabulary while navigating to typed hosts.

## Gestures and actions

Native Paper events are projected into immutable `MenuInteraction` values before plug-in code runs. The interaction contains player reference, stable slot identity, committed render revision, clicked and cursor snapshots, modifiers, and a sealed gesture:

```kotlin
slot(13) {
    item = waypointIcon

    onPrimary { openDetails() }
    onSecondary { toggleFavorite() }

    onGesture { interaction ->
        when (val gesture = interaction.gesture) {
            is MenuGesture.NumberKey -> moveToHotbar(gesture.index)
            MenuGesture.DropOne -> dropOne()
            MenuGesture.DropStack -> dropStack()
            else -> pass()
        }
    }
}
```

Interactions target their action slot or storage transaction directly. There is no DOM-style capture or bubbling. Explicit observers and interceptors support telemetry and cross-cutting policy without accidental ancestor handlers.

A drag crossing several slots becomes one gesture and one storage proposal. Number-key swaps, offhand swaps, drop gestures, double-click collection, creative gestures, outside clicks, and every supported native input have explicit types or internal rejection rules.

Suspending actions have stable component and handler identity. They default to `SINGLE_FLIGHT`; callers may choose `RESTART_LATEST` or `PARALLEL`:

```kotlin
onPrimary(concurrency = SINGLE_FLIGHT) {
    purchase(offer.id)
}
```

The runtime never queues a click whose assumptions belong to an earlier render. State mutation and reconciliation remain serialized even when explicitly parallel actions complete concurrently.

Actions receive detached framework values and start as supervised plug-in coroutine work. Direct Paper access uses the kernel's explicit ownership blocks:

```kotlin
interaction.player.access(plugin) { livePlayer ->
    livePlayer.teleport(location)
}
```

The framework enters entity ownership internally for native reconciliation, cursor updates, host remount, feedback, and recovery.

## Feedback

Actions and transactions produce typed Adventure feedback:

```kotlin
feedback.reject(
    message = text("Only guild officers can remove that item."),
    target = thisSlot,
)
```

A feedback value carries severity and an optional target. The scoped presentation maps it to action bar, sound, and temporary slot emphasis. Themes may replace the mapping per menu or subtree. Routine feedback does not force chat, and the framework does not supply domain-facing English.

## Action and storage slots

An action slot presents a virtual item and triggers actions. It never stores the displayed item. A storage slot contains an item snapshot and participates in transactions. One host may mix both:

```kotlin
chest(title = "Vault", rows = 6) {
    storage(vault, slots = rows(0..4))
    row(5) {
        back(slot = 0)
        close(slot = 8)
    }
}
```

The runtime cancels native mutation for framework-owned hosts. It computes a complete before/after proposal covering every involved storage model, player inventory slot, and logical cursor, validates the proposal, and commits it atomically. Plug-in handlers never implement vanilla drag distribution, merging, swaps, shift transfer, stack limits, or cursor recovery.

## Storage rules and transfer routes

Storage declares insertion, extraction, and stack rules:

```kotlin
storage(vault, slots = rows(0..4)) {
    rules {
        slots(0..8) {
            accepts { item -> WaypointToken.read(item) is CustomItemRead.Found }
            maxStack = 16
        }

        slot(17) {
            canExtract = { account.canManageVault }
        }
    }
}
```

Ordered routes define automatic transfer destinations:

```kotlin
transfers {
    from(player.hotbar, player.main).to(vault)
    from(vault).to(player.hotbar, player.main)
}
```

The engine performs vanilla-correct merge-before-empty placement in declared destination order. Typed hosts may provide overridable vanilla defaults. A missing valid route rejects shift transfer instead of guessing.

## Storage ownership

Session-local and external storage implement one versioned interface:

```kotlin
val draft = rememberStorage(
    key = "draft-kit",
    initial = emptyItemSnapshots(27),
)
```

```kotlin
val guildVault = menuStorage(
    key = GuildVaultKey(guild.id),
    snapshots = repository.watch(guild.id),
) {
    durableCommit(
        commit = repository::commit,
        resolve = repository::resolve,
    )
}
```

Local storage belongs to keyed session state and commits synchronously. External storage has stable identity and revision independent of any viewer. Several sessions may attach to one storage: local navigation and selection stay per player, while accepted snapshots, revisions, locks, and commit ownership are shared. Rendering remains per viewer.

## Pessimistic transactions

Native mutation is cancelled while an external proposal awaits approval. The displayed storage and cursor retain the before-state. Acceptance atomically installs the authoritative result; rejection preserves the original state and presents typed feedback.

```kotlin
commit { proposal ->
    when (val result = repository.commit(proposal)) {
        is Stored -> commit(result.snapshot)
        is Rejected -> reject(result.message)
    }
}
```

Pending work locks only the storage identities and cursor resources involved in that proposal. Conflicting gestures reject immediately; unrelated action slots and independent storage continue. The runtime never queues a stale gesture.

One persistent storage automatically owns transactions between itself and framework-managed player inventory or cursor state. A proposal crossing multiple persistent models requires one transaction domain:

```kotlin
val trade = transactionDomain(
    key = TradeKey(tradeId),
    storages = setOf(sellerOffer, buyerOffer),
) {
    commit(tradeRepository::commitAtomically)
}
```

Menu validation rejects a cross-model route without one common owner. Sequential callbacks and compensating rollback are not presented as atomicity.

## Durable commits

An external commit may already have succeeded when the player closes or the process stops. Before invoking persistence, the framework journals a durable proposal under a stable transaction ID. The transaction domain provides an idempotent commit and outcome resolution:

```kotlin
durableCommit(
    commit = { proposal ->
        repository.commit(
            transactionId = proposal.id,
            expectedRevision = proposal.before.revision,
            after = proposal.after,
        )
    },
    resolve = repository::resolve,
)
```

Once submitted, durable storage work survives menu close, disconnect, and caller cancellation. Restart recovery resolves ambiguous outcomes and installs accepted shared snapshots. Ordinary menu actions and effects still cancel with the session; this exception exists only for work that crossed an external commit boundary.

## Cursor and item recovery

Internal remount and navigation retain the committed logical cursor. A real session end settles it through a deterministic pipeline:

1. Merge into player hotbar and main inventory under entity ownership.
2. Write overflow to a durable per-player recovery mailbox before clearing the cursor.
3. Deliver mailbox contents automatically on a later safe join or inventory opportunity.
4. Use world drops only when a plug-in explicitly chooses that policy.

A temporary editor may request return-to-origin, but it succeeds only against a compatible current revision and valid rules; otherwise recovery takes over. Pending proposals require no recovery because their before-state remains authoritative. Accepted durable player deltas use the same inventory-or-mailbox settlement if the viewer is gone.

## Standard components

The module ships polished components built only through the public API:

- paged grids and lists;
- scrolling windows;
- tabs and selection groups;
- confirmation screens;
- toggles and number steppers;
- borders, fillers, and layout helpers;
- back, close, previous, and next controls;
- loading, empty, failed, and ready content states;
- prompt and search integration when input is installed.

Example:

```kotlin
val browser = paged(
    items = waypoints,
    key = Waypoint::id,
    region = rows(0..4),
    pageSize = 45,
)

browser.items(::WaypointSlot)
browser.previous(slot = 45)
browser.status(slot = 49)
browser.next(slot = 53)
```

Standard components receive no privileged runtime API. Plug-ins may wrap, copy, style, or replace them, and their source acts as compile-checked advanced examples.

## Deterministic testing

`framework-menus-test` drives the production semantic engine through a fake native host adapter:

```kotlin
menuTest {
    val menu = open(player("Alex")) {
        WaypointMenu(fakeStore)
    }

    menu.assertHost<ChestHost> {
        title isEqualTo "Waypoints"
        rows isEqualTo 6
    }

    menu.primaryClick(row = 0, column = 0)
    menu.assertRoute(WaypointScreen.Details(firstWaypoint.id))

    menu.shiftClick(player.main[3])
    repository.acceptNextCommit()
    runCurrent()

    menu.assertNoItemCreationOrLoss()
    menu.assertNoPendingWork()
}
```

The harness supports typed gestures, virtual time, action concurrency, Flow emissions, navigation, host remounts, commit outcomes, disconnects, restart recovery, semantic snapshots, and conservation assertions. Tests do not construct Bukkit events or fake scheduler ownership.

Property and model tests exhaust the transaction state machine across gesture sequences, full inventories, stack boundaries, filters, stale revisions, conflicting viewers, rejection, cancellation, and close races.

Separate contract suites run every native host adapter on real Paper and Folia. A real client verifies protocol-visible behavior, including drag, number keys, creative input, host remount, close reasons, and cursor settlement.

## Inspection

The runtime exposes redacted semantic snapshots and bounded typed traces:

```kotlin
val snapshot = menus.inspect(player).snapshot()

snapshot.host
snapshot.componentTree
snapshot.stateCells
snapshot.slotBindings
snapshot.pendingActions
snapshot.pendingTransactions
snapshot.navigation
snapshot.lastRenders
snapshot.lastGestures
```

Typed observers expose render, gesture, action, effect, transaction, recovery, navigation, and close lifecycle without mutable control. When commands are installed, development configuration may register inspect, trace, and export commands. Sensitive payloads and PDC are redacted by default; plug-ins may register safe diagnostic renderers.

## Concurrency and Folia

- Native callbacks retain their Paper/Folia ownership only inside the private adapter.
- Gestures are immutable before plug-in actions start.
- Actions run as supervised plug-in tasks and use explicit kernel ownership for Paper access.
- Session state mutation and reconciliation are serialized per session.
- Shared storage locks are keyed by stable storage identity across sessions.
- Native inventory updates occur through the player's current entity ownership context.
- Entity retirement, disconnect, close, replacement, and plug-in stop compete through atomic transitions with one typed winner.
- Durable commits and recovery are runtime-owned work with explicit shutdown and restart behavior.

## Validation and failure reporting

Before a render commits, validation checks at least:

- host property and capacity validity;
- duplicate component, item, and state keys;
- duplicate physical slot ownership;
- region overflow;
- action/storage role conflicts;
- storage snapshot size and revision consistency;
- transfer-route reachability and destination order;
- transaction-domain coverage;
- item materialization and component validity;
- host-specific protocol invariants.

Failures contain component paths, route, host coordinates, semantic slot keys, and redacted item summaries. They flow to the nearest error boundary or the framework failure reporter. No validation failure partially mutates a native inventory.

## Documentation and agent use

The implemented module must ship:

- a tutorial for the first action menu;
- how-to pages for navigation, prompts, editable storage, persistent storage, custom components, themes, and testing;
- complete item, menu, host, gesture, transaction, recovery, and test references;
- a host capability matrix for the pinned Paper line;
- KDoc on every public declaration;
- ABI baselines;
- compile-checked standard components and sample menus;
- semantic trace examples for diagnosing common failures;
- an agent workflow that begins with the smallest appropriate API and states when real Paper, Folia, and client verification are required.

## Implementation slices

Implementation must remain vertical. Each slice ends with docs, tests, and a playable proof before another host or subsystem begins:

1. Complete the approved items-module slices through exact snapshots.
2. Add the pure menu tree, keyed state, context parameters, validation, reconciliation model, and test harness with an action-only chest host.
3. Add Paper and Folia chest adapters, typed gestures, action concurrency, feedback, lifecycle, inspection, and a real-client action-menu proof.
4. Add session-local storage, the complete gesture transaction engine, rules, routes, player inventory, cursor settlement, property tests, and real-client conservation tests.
5. Add shared external storage, pessimistic commits, resource locking, transaction domains, durable journaling, restart resolution, and recovery mailbox.
6. Add Flow state, effects, error boundaries, typed navigation, transparent remount, choice results, and focused-input suspension.
7. Add the full standard component library and theme/local system using only public APIs.
8. Add each remaining concrete host one at a time with a typed contract, server-free model tests, Paper/Folia adapter tests, docs, and playable sample.
9. Finish agent documentation, host matrix, complete samples, API baselines, load tests, and release acceptance across the pinned Paper/Folia/client line.

A slice may deepen an existing interface when evidence requires it. It may not bypass the transaction engine, add a generic raw-event escape hatch, or introduce code generation to make progress appear faster.
