# Menu storage and transactions reference

Menu storage is an immutable transaction model. The native adapter cancels framework-owned movement, computes a complete proposal, commits it pessimistically, then installs accepted storage, player-inventory, cursor, and drop changes together.

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

## Declaring storage in a chest

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

A region may not overlap an action slot or another storage. Declare each player section before a route references it. Shift transfers try destinations in the declared order and never guess an undeclared route.

The player inventory sections are `MAIN`, `HOTBAR`, `OFFHAND`, and `ARMOR`. Native player slots are projected to stable `PlayerInventorySlot` values.

## Gesture planning

`MenuTransactionEngine` is the server-free planner. It consumes `MenuTransactionState` and one `MenuStorageGesture`, returning `Proposed`, `Rejected`, or `NoChange`.

Gestures cover primary and secondary pickup or placement, shift transfer, hotbar and offhand swaps, one-item and stack drops, cursor drops, double collection, and even or single-item drag distribution. The engine checks snapshot revisions, slot rules, exact stackability, effective maximums, conservation, and declared route order. It does not mutate the input snapshots.

`MenuTransactionProposal` contains a stable `MenuTransactionId`, all before and after storage values, cursor before and after, and explicit emissions such as a world drop. `resources` lists only storage identities and the session cursor changed by that proposal.

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

## Durable journal and restart resolution

Pass `FileMenuTransactionJournal` to a coordinator before using an external domain. The coordinator records the intent before submitting persistence. The journal uses atomic replacement and checksummed bounded entries.

`recover(domains)` asks each domain to resolve its recorded IDs and returns `MenuTransactionRecovery` values:

- `MissingDomain` keeps the entry because its owner is unavailable.
- `Pending` keeps it for another resolution pass.
- `NotCommitted` and `Rejected` complete the journal record.
- `Committed` returns authoritative snapshots for settlement. The caller acknowledges only after native or mailbox delivery is safe.

The shipped `PlayerMenus` creates a file journal for its native transaction coordinator. Full plug-in restart orchestration of recovered domain outcomes is not yet automatic. Applications using external storage must call and settle recovery explicitly.

## Cursor and overflow recovery

`ItemRecoveryMailbox` stores exact snapshots when they cannot safely return to a live inventory. `FileItemRecoveryMailbox` writes one bounded checksummed file per player:

```kotlin
val deposited = mailbox.deposit(playerId, overflow)
val pending = mailbox.pending(playerId)
mailbox.acknowledge(playerId, deliveredIds)
```

Deposit comes before discarding the source cursor or acknowledging the transaction. Acknowledge only IDs actually delivered. The file implementation caps one player at 10,000 entries and serializes access per player.

The mailbox and restart result types are implemented and tested as isolated durability components. End-to-end native session close, restart settlement, and mailbox delivery still require application wiring and connected-client proof.
