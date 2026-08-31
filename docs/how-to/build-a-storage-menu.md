# Build a transactional storage menu

Keep the storage object outside render. This example creates it once in an application service:

```kotlin
class Backpack(
    val storage: MenuStorage = localMenuStorage(
        MenuStorageId("waypoints", "backpack"),
        initial = List(27) { null },
    ),
)
```

Bind it to a chest and declare player participants plus shift routes:

```kotlin
context(MenuScope)
fun BackpackMenu(backpack: Backpack) {
    chest("Backpack", rows = 4) {
        storage(backpack.storage, rows(0..2))
        playerInventory(PlayerInventorySection.MAIN, PlayerInventorySection.HOTBAR)
        transfers(
            transferRoute(
                backpack.storage.reference(),
                PlayerInventorySection.HOTBAR.reference(),
                PlayerInventorySection.MAIN.reference(),
            ),
            transferRoute(PlayerInventorySection.MAIN.reference(), backpack.storage.reference()),
            transferRoute(PlayerInventorySection.HOTBAR.reference(), backpack.storage.reference()),
        )
        closeControl(31, closeIcon)
    }
}
```

The storage occupies slots 0 through 26. Slot 31 remains free for the close action. A shift click follows only these routes.

For database storage, expose a `StateFlow<MenuStorageSnapshot>` and implement `MenuTransactionDomain`. The commit must atomically compare the before revisions, apply the complete proposal once by ID, and return authoritative snapshots. Attach both with `externalMenuStorage`.

Test rules and conservation through the pure transaction engine first. Then test the declaration with `menuTest`. Run a native fixture for player inventory, cursor, drop, disconnect, and close behavior.
