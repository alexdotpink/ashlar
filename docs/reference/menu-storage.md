# Menu storage and transactions reference

Menu storage is an immutable transaction model. The native adapter cancels ashlar-owned movement, computes a complete proposal, commits it pessimistically, then installs accepted storage, player-inventory, cursor, and drop changes together.

## Storage models

`MenuStorageId` is the stable identity used by viewers, transaction locks, journals, and domains. `MenuStorageSnapshot` contains an ID, monotonic revision, and fixed-size list of exact `ItemSnapshot?` values.

Use session or application-owned in-memory storage when no external persistence is involved:

```kotlin
val backpack = localMenuStorage(
    id = MenuStorageId("sample", "backpack"),
    initial = List(27) { null },
)
```

`localMenuStorage` accepts proposals immediately and advances its `StateFlow` revision. Keep the returned object stable. Constructing it inside a render would reset the model on each render.

Inside a menu renderer, `rememberStorage(id, initial, rules)` retains a local storage in keyed session state:

```kotlin
component("draft") {
    val draft = rememberStorage(
        MenuStorageId("sample", "draft-kit"),
        initial = List(9) { null },
    )
    storage(draft, region(0..8))
}
```

The delegated component key and storage ID form its session identity. Removing that component releases the storage. Rerendering it returns the same storage instance.

External storage attaches an authoritative snapshot stream and one atomic commit owner:

```kotlin
val vault = externalMenuStorage(
    id = vaultId,
    snapshots = repository.snapshots,
    rules = vaultRules,
    transactionDomain = repository,
)
```

The stream ID, slot count, rules, and domain ownership must agree. One `MenuTransactionDomain` may atomically own several persistent storages.

## Slot rules

`MenuSlotRule` controls insertion, extraction, and effective capacity:

```kotlin
val diamondOnly = MenuSlotRule(
    accepts = { it.material == Material.DIAMOND },
    canExtract = { true },
    maximumAmount = { minOf(16, it.maximumAmount) },
)

val rules = MenuStorageRules.of(
    listOf(diamondOnly, MenuSlotRule.Locked) + List(7) { MenuSlotRule.Vanilla },
)
```

The maximum must be positive. It cannot override item stackability or create more items than the proposal contains. `Locked` rejects insertion and extraction. `Vanilla` accepts Paper's captured maximum.

## Declaring storage in a host

Bind every storage slot to one equally sized ordered region:

```kotlin
chest("Backpack", rows = 4) {
    storage(backpack, region = rows(0..2))
    playerInventory(PlayerInventorySection.MAIN, PlayerInventorySection.HOTBAR)
    transfers(
        transferRoute(
            backpack.reference(),
            PlayerInventorySection.HOTBAR.reference(),
            PlayerInventorySection.MAIN.reference(),
        ),
        transferRoute(
            PlayerInventorySection.MAIN.reference(),
            backpack.reference(),
        ),
        transferRoute(
            PlayerInventorySection.HOTBAR.reference(),
            backpack.reference(),
        ),
    )
}
```

A region may not overlap an action slot or another storage. Storage works with every `InventoryHostScope`, not only chest. Declare each player section before a route references it. Shift transfers try destinations in the declared order and never guess an undeclared route.

The player inventory sections are `MAIN`, `HOTBAR`, `OFFHAND`, and `ARMOR`. Native player slots are projected to stable `PlayerInventorySlot` values.

## Gesture planning

`MenuTransactionEngine` is the server-free planner. It consumes `MenuTransactionState` and one `MenuStorageGesture`, returning `Proposed`, `Rejected`, or `NoChange`.

Gestures cover primary and secondary pickup or placement, shift transfer, hotbar and offhand swaps, one-item and stack drops, cursor drops, double collection, and even or single-item drag distribution. The engine checks snapshot revisions, slot rules, exact stackability, effective maximums, conservation, and declared route order. It does not mutate the input snapshots.

`MenuTransactionProposal` contains a stable `MenuTransactionId`, player identity and section mappings when present, all before and after storage values, cursor before and after, and explicit emissions. `resources` lists only storage identities and the player cursor changed by that proposal.

## Pessimistic commit

`MenuTransactionCoordinator.submit` acquires the proposal resources without waiting. A conflict returns `ResourceBusy`; stale gestures are never queued. Unrelated storage and action work can continue.

Local storage changes commit in memory. Persistent participants must share one `MenuTransactionDomain`. Its `commit` receives the complete proposal and returns either authoritative committed snapshots or a rejection component:

```kotlin
class VaultDomain(...) : MenuTransactionDomain {
    override val id = "sample-vault"
    override val storages = setOf(vaultId)

    override suspend fun commit(proposal: MenuTransactionProposal): MenuTransactionDecision =
        repository.commitOnce(proposal.id, proposal)

    override suspend fun resolve(id: MenuTransactionId): MenuTransactionResolution =
        repository.resolve(id)
}
```

Commit must be idempotent by transaction ID. Rejection leaves storage and cursor at their before values. A committed snapshot must retain its ID and size and advance its revision. `MenuTransactionSubmission` distinguishes committed, domain rejection, and framework failure.

The external storage wrapper publishes an accepted authoritative snapshot immediately, even if the repository's `StateFlow` has not emitted it yet. A later repository value may advance the revision. Publishing different values for the same revision fails.

## Durable commit and restart resolution

Register each persistent owner with the menu runtime:

```kotlin
val registration = menus.registerTransactionDomain(vaultDomain)
```

`registerTransactionDomain` returns `MenuRegistration`. Keep it for the lifetime of the domain. Registration starts resolution of matching journal entries. A domain encountered through a live storage also becomes known for that process, but explicit registration is what makes restart resolution available before a menu renders.

The shipped `PlayerMenus` owns `FileMenuTransactionJournal` under the plug-in data folder. Journal format version 2 records the complete proposal and explicit player-section mapping. The runtime writes the journal before calling `MenuTransactionDomain.commit`. Submitted durable work belongs to the plug-in, so menu close, disconnect, replacement, and caller cancellation do not cancel it.

On restart, the runtime calls `resolve(id)`:

- `Pending` keeps the entry for another pass.
- `NotCommitted` and `Rejected` remove it.
- `Committed` validates authoritative snapshots and starts player settlement.
- An unavailable domain leaves its entries untouched until registration.

The runtime removes a committed journal entry only after native application or durable recovery settlement succeeds. If recovery needs to remove player-held items and the current holdings cannot satisfy the exact delta, settlement stays pending. It does not guess or duplicate items.

Durable proposals currently reject `MenuTransactionEmission.Drop` with `DurableEmissionUnsupported`. A world entity cannot share the transaction's durable player receipt. Local, non-durable drop gestures remain supported.

## Cursor and overflow recovery

`ItemRecoveryMailbox` stores exact snapshots when they cannot safely return to a live inventory. `FileItemRecoveryMailbox` writes one bounded checksummed file per player:

```kotlin
val deposited = mailbox.deposit(deliveryId, playerId, overflow)
val pending = mailbox.pending(playerId)
mailbox.acknowledge(playerId, deliveredIds)
```

The delivery ID makes deposit replay idempotent. Repeating the ID with a different payload fails. The file adapter keeps an atomic replay marker until the durable source completes. Deposit comes before discarding the source cursor or acknowledging the transaction. Acknowledge only item IDs that reached inventory.

Paper settlement records a transaction receipt in player persistent data and saves player data before journal acknowledgement. A replay sees that receipt instead of applying the delta twice. The runtime clears receipts and delivery replay markers after acknowledgement.

The Paper adapter tries mailbox delivery on player join and before mounting a menu. It acknowledges only entries that fit completely. Entries without capacity remain pending. Cursor settlement always deposits first, then tries live delivery. The file implementation caps one player at 10,000 pending items and serializes access per player.

Server-free tests cover runtime ownership, restart outcomes, player-delta calculation, replay rules, and the complete transaction gesture matrix. Paper and Folia fixtures cover adapter integration. Crash timing, cursor conservation, and join delivery still require connected-client acceptance on the pinned server line.
