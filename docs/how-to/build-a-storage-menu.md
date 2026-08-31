# Build a transactional storage menu

Use `rememberStorage` when the backpack belongs to one menu session. The current keyed component retains it across rerenders.

Bind it to a chest and declare player participants plus shift routes:

```kotlin
context(MenuScope)
fun BackpackMenu() {
    val backpack = rememberStorage(
        MenuStorageId("waypoints", "backpack"),
        initial = List(27) { null },
    )
    chest("Backpack", rows = 4) {
        storage(backpack, rows(0..2))
        playerInventory(PlayerInventorySection.MAIN, PlayerInventorySection.HOTBAR)
        transfers(
            transferRoute(
                backpack.reference(),
                PlayerInventorySection.HOTBAR.reference(),
                PlayerInventorySection.MAIN.reference(),
            ),
            transferRoute(PlayerInventorySection.MAIN.reference(), backpack.reference()),
            transferRoute(PlayerInventorySection.HOTBAR.reference(), backpack.reference()),
        )
        closeControl(31, closeIcon)
    }
}
```

The storage occupies slots 0 through 26. Slot 31 remains free for the close action. A shift click follows only these routes. Move storage to an application-owned object when several sessions must share it.

For database storage, expose a `StateFlow<MenuStorageSnapshot>` and implement `MenuTransactionDomain`. The commit must atomically compare the before revisions, apply the complete proposal once by ID, and return authoritative snapshots. `resolve` must report the outcome of a stable transaction ID after restart. Attach both with `externalMenuStorage`.

Register the domain during plug-in startup:

```kotlin
override fun ComponentContext.start() {
    own(menus.registerTransactionDomain(vaultDomain))
}
```

The menu runtime journals before calling the domain. A submitted commit continues if its menu closes. Committed player output reaches live inventory or the recovery mailbox. The adapter retries mailbox delivery on join and before opening another menu.

Test rules and conservation through the pure transaction engine first. Then test the declaration with `menuTest`. Run Paper and Folia fixtures for player inventory and adapter mapping. Use a connected client before claiming cursor, disconnect, crash recovery, or automatic join delivery behavior.
