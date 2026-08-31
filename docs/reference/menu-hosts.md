# Menu host reference

A render declares one typed `MenuHostSnapshot`. Host DSLs expose only valid physical slots and host properties. There is no generic raw inventory-type callback.

## Native chest host

`chest(title, rows)` supports one through six rows and accepts indexed or row-column slots. `ChestScope` exposes its row count. Paper and Folia materialize, reconcile, remount, and close chest hosts.

## Fixed indexed containers

These hosts use `ContainerHostScope` and `slot(index)`:

| DSL | Capacity | Semantic snapshot |
| --- | ---: | --- |
| `hopper` | 5 | `HopperHostSnapshot` |
| `generic3x3` | 9 | `Generic3x3HostSnapshot` |
| `shulker` | 27 | `ShulkerHostSnapshot` |

## Role-indexed hosts

`RoleHostScope<R>` accepts `slot(role)` so plug-in code does not memorize native indexes:

| DSL | Slot enum | Host properties |
| --- | --- | --- |
| `anvil` | `AnvilSlot` | repair cost, maximum cost, repair-item count, level-restriction bypass |
| `merchant` | `MerchantSlot` | immutable `MerchantOfferSnapshot` list |
| `furnace`, `blastFurnace`, `smoker` | `FurnaceSlot` | cooking and burning `MenuProgress` |
| `brewing` | `BrewingSlot` | fuel, brewing ticks, recipe brew time |
| `crafting` | `CraftingSlot` | result and 3x3 grid roles |
| `crafter` | `CrafterSlot` | disabled grid roles |
| `enchantment` | `EnchantmentSlot` | seed and three `EnchantmentOfferSnapshot?` positions |
| `grindstone` | `GrindstoneSlot` | top, bottom, result |
| `smithing` | `SmithingSlot` | template, base, addition, result |
| `loom` | `LoomSlot` | banner, dye, pattern, result |
| `cartography` | `CartographySlot` | map, addition, result |
| `stonecutter` | `StonecutterSlot` | input, result |
| `beacon` | `BeaconSlot` | primary and secondary effects |
| `lectern` | `LecternSlot` | page |

Example semantic declaration:

```kotlin
anvil(
    title = Component.text("Rename token"),
    repairCost = 3,
) {
    slot(AnvilSlot.LEFT) { item = sourceToken }
    slot(AnvilSlot.RESULT) {
        item = renamedToken
        onPrimary { acceptRename() }
    }
}
```

Host snapshots validate capacity and property bounds. `MenuReconciliation.Update.propertiesChanged` reports property changes without pretending they are slot changes.

## Implementation boundary

The semantic renderer, validation, snapshots, reconciliation, and deterministic test host support this typed catalogue. The current Paper/Folia adapter materializes only `MenuHostSnapshot.Chest`. Opening another host through `PlayerMenus` closes through a failed session because no native adapter exists yet. Keep non-chest declarations in semantic tests until their concrete adapters and native fixtures ship.
