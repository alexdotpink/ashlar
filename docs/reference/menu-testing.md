# Menu testing reference

Add `framework-menus-test` to test dependencies. It runs the production semantic session runtime with virtual time and a deterministic native host.

```kotlin
@Test
fun `next page updates the visible items`() = menuTest {
    val session = open {
        chest("Waypoints", rows = 1) {
            var page by state(0)
            slot(8) {
                item = nextIcon
                onPrimary { page++ }
            }
        }
    }

    session.assertRevision(1)
    session.primaryClick(8)
    session.assertRevision(2)
}
```

`menuTest` supplies `MenuTestScope` over `kotlinx-coroutines-test`. It closes every session and fails when actions remain pending.

## Driving sessions

`open` starts a session without waiting for close. `choose<T>` returns `MenuTestChoice<T>`. Both accept initial player-inventory sections and a logical cursor. The scope creates deterministic `PlayerRef` values, runs ready work, advances virtual time, and checks pending work.

`MenuTestSession` exposes the newest `MenuRenderSnapshot` and typed `ChestHostSnapshot`. It can dispatch any host gesture, dispatch from a symbolic player-inventory slot, perform shift clicks, send an ordered host/player drag, report native close, close logically, await the close reason, and read feedback or inspection.

Assertions include:

- `assertChest` for title, rows, and semantic slots;
- `assertRevision` for render batching and invalidation;
- `reconciliations` for changed slots, title updates, and remounts;
- `semanticSnapshot` for stable tree text without item payloads;
- `inspect` for state, navigation, effects, actions, transactions, and trace events;
- `cursor`, `playerItem`, and `assertStorageItem` for accepted transaction state;
- `assertNoItemCreationOrLoss` for exact quantities across storage, player inventory, cursor, and emissions;
- `nativeCloseCalls`, `isPresented`, and `assertNoPendingWork` for cleanup and focused-input presentation lifecycle.

Use `advanceTimeBy(duration)` for suspending actions and effects. A session gesture runs work currently ready on the virtual scheduler but does not skip delay. `assertNoPendingWork` checks both actions and transactions.

## Storage engine tests

Test movement rules directly through `MenuTransactionEngine` with `ItemSnapshot.detached`. A detached snapshot carries material, amount, maximum, and explicit stackability identity without loading Paper:

```kotlin
val stone = ItemSnapshot.detached(Material.STONE, 32, 64, "ordinary-stone")
val state = MenuTransactionState(
    storages = mapOf(backpackId to MenuStorageSnapshot(backpackId, 0, listOf(stone, null))),
)

val result = engine.plan(state, MenuStorageGesture.Primary(MenuSlotAddress(backpackId, 0)))
```

Assert the complete proposal: every before and after snapshot, cursor value, emission, revision, and resource. Conservation tests should cover empty, partial, full, filtered, maximum-stack, stale, shift-route, swap, drag, double-collect, and drop cases.

Use real Paper and Folia fixtures when code changes materialization, native slot mapping, inventory events, cursor application, drops, close handling, titles, or remounts. Use a connected client when the claim depends on packets or player-visible behavior. A fake Bukkit event is not native evidence.
