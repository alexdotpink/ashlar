# Menu host reference

A successful render declares one typed `MenuHostSnapshot`. Host DSLs expose the host's valid slots and writable properties. The Paper/Folia adapter uses Paper `MenuType`; no public raw `InventoryType` escape exists.

Changing host kind, capacity, or title remounts native presentation without ending the logical menu session. Other property changes produce `MenuReconciliation.Update` with `propertiesChanged = true`.

## Chest and fixed indexed containers

These hosts accept physical slot indexes. Chest also accepts row and column coordinates.

| DSL | Capacity | Scope or snapshot |
| --- | ---: | --- |
| `chest(title, rows)` | 9 to 54 | `ChestScope`, `ChestHostSnapshot` |
| `hopper` | 5 | `ContainerHostScope`, `HopperHostSnapshot` |
| `generic3x3` | 9 | `ContainerHostScope`, `Generic3x3HostSnapshot` |
| `shulker` | 27 | `ContainerHostScope`, `ShulkerHostSnapshot` |

Chest rows range from one through six. Every fixed host rejects indexes outside its capacity.

## Role-indexed hosts

`RoleHostScope<R>` accepts `slot(role)`. The role enum owns native index mapping.

| DSL | Slot enum | Writable snapshot properties |
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

A lectern must put a `WRITABLE_BOOK` or `WRITTEN_BOOK` in `LecternSlot.BOOK`; Paper closes a
lectern view that has no valid book. Use item data components or the item module's keyed `paper`
escape hatch to author pages. Page changes arrive through `onPageChanged`.

Example:

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
    renameText { input -> previewName(input.text) }
}
```

## Typed native input

Specialized client controls do not pretend to be slot clicks. The adapter projects them through these host-scoped declarations:

| Host | Declaration | `MenuHostInput` subtype | Default concurrency |
| --- | --- | --- | --- |
| Anvil | `renameText` | `AnvilRenameText` | `RESTART_LATEST` |
| Merchant | `onTradeSelected` | `MerchantTradeSelected` | `RESTART_LATEST` |
| Loom | `onPatternSelected` | `LoomPatternSelected` | `RESTART_LATEST` |
| Stonecutter | `onRecipeSelected` | `StonecutterRecipeSelected` | `RESTART_LATEST` |
| Enchantment | `onEnchantmentButton` | `EnchantmentButtonPressed` | `SINGLE_FLIGHT` |
| Beacon | `onBeaconEffectsSelected` | `BeaconEffectsSelected` | `SINGLE_FLIGHT` |
| Lectern | `onPageChanged` | `LecternPageChanged` | `RESTART_LATEST` |

Each input contains `PlayerRef`, committed render revision, and its semantic value. Registry-owned values use Adventure `Key`. Enchantment buttons use `EnchantmentButton`; lectern movement uses `LecternPageDirection`.

Input for an old revision returns `MenuDispatch.StaleRevision`. A host with no matching declaration returns `UnsupportedHostInput`. Declared handlers use ordinary menu action ownership, concurrency, feedback, and error boundaries.

## Native adapter coverage

The shipped Paper/Folia adapter materializes every host in this page with the pinned Paper `MenuType` API. Writable Paper view properties are applied for anvil, merchant, furnace family, brewing, crafter, enchantment, beacon, and lectern. Loom and stonecutter selections, merchant selection, anvil rename text, enchantment buttons, beacon submission, and lectern pages enter through typed host input where Paper exposes events.

Server-free tests cover models, property validation, kind mapping, remount decisions, and typed input dispatch. Paper and Folia fixtures cover adapter loading and native mappings. A Minecraft 26.2 client has opened every listed host, returned through each remount, retained an empty player inventory, and exercised lectern page input. Direct client interaction with anvil, merchant, loom, stonecutter, enchantment, and beacon controls remains release acceptance work.
