package dev.placeholder.framework.menus

import net.kyori.adventure.text.Component
import org.bukkit.potion.PotionEffectType

/** Declares a five-slot hopper host. */
context(menu: MenuScope)
public fun hopper(
    title: Component,
    content: context(ContainerHostScope) () -> Unit,
): Unit = containerHost(title, 5, { slots -> HopperHostSnapshot(title, slots) }, content)

/** Declares a generic three-by-three inventory host. */
context(menu: MenuScope)
public fun generic3x3(
    title: Component,
    content: context(ContainerHostScope) () -> Unit,
): Unit = containerHost(title, 9, { slots -> Generic3x3HostSnapshot(title, slots) }, content)

/** Declares a 27-slot shulker inventory host. */
context(menu: MenuScope)
public fun shulker(
    title: Component,
    content: context(ContainerHostScope) () -> Unit,
): Unit = containerHost(title, 27, { slots -> ShulkerHostSnapshot(title, slots) }, content)

/** Declares an anvil host with typed [AnvilSlot] positions. */
context(menu: MenuScope)
public fun anvil(
    title: Component,
    repairCost: Int = 0,
    maximumRepairCost: Int = 40,
    repairItemCount: Int = 0,
    bypassEnchantmentLevelRestriction: Boolean = false,
    content: context(RoleHostScope<AnvilSlot>) () -> Unit,
): Unit = roleHost(
    title,
    capacity = 3,
    index = AnvilSlot::index,
    snapshot = { slots ->
        AnvilHostSnapshot(
            title,
            slots,
            repairCost,
            maximumRepairCost,
            repairItemCount,
            bypassEnchantmentLevelRestriction,
        )
    },
    content,
)

/** Declares a merchant host with ordered [offers]. */
context(menu: MenuScope)
public fun merchant(
    title: Component,
    offers: List<MerchantOfferSnapshot>,
    content: context(RoleHostScope<MerchantSlot>) () -> Unit,
): Unit = roleHost(title, 3, MerchantSlot::index, { slots -> MerchantHostSnapshot(title, slots, offers) }, content)

/** Declares a furnace host with client-visible cooking and burning progress. */
context(menu: MenuScope)
public fun furnace(
    title: Component,
    cooking: MenuProgress = MenuProgress(0, 1),
    burning: MenuProgress = MenuProgress(0, 1),
    content: context(RoleHostScope<FurnaceSlot>) () -> Unit,
): Unit = furnaceHost(title, FurnaceHostKind.FURNACE, cooking, burning, content)

/** Declares a blast-furnace host with client-visible cooking and burning progress. */
context(menu: MenuScope)
public fun blastFurnace(
    title: Component,
    cooking: MenuProgress = MenuProgress(0, 1),
    burning: MenuProgress = MenuProgress(0, 1),
    content: context(RoleHostScope<FurnaceSlot>) () -> Unit,
): Unit = furnaceHost(title, FurnaceHostKind.BLAST_FURNACE, cooking, burning, content)

/** Declares a smoker host with client-visible cooking and burning progress. */
context(menu: MenuScope)
public fun smoker(
    title: Component,
    cooking: MenuProgress = MenuProgress(0, 1),
    burning: MenuProgress = MenuProgress(0, 1),
    content: context(RoleHostScope<FurnaceSlot>) () -> Unit,
): Unit = furnaceHost(title, FurnaceHostKind.SMOKER, cooking, burning, content)

/** Declares a brewing-stand host with its native progress values. */
context(menu: MenuScope)
public fun brewing(
    title: Component,
    fuelLevel: Int = 0,
    brewingTicks: Int = 0,
    recipeBrewTime: Int = 400,
    content: context(RoleHostScope<BrewingSlot>) () -> Unit,
): Unit = roleHost(
    title,
    5,
    BrewingSlot::index,
    { slots -> BrewingHostSnapshot(title, slots, fuelLevel, brewingTicks, recipeBrewTime) },
    content,
)

/** Declares a crafting-table host with typed [CraftingSlot] positions. */
context(menu: MenuScope)
public fun crafting(
    title: Component,
    content: context(RoleHostScope<CraftingSlot>) () -> Unit,
): Unit = roleHost(title, 10, CraftingSlot::index, { slots -> CraftingHostSnapshot(title, slots) }, content)

/** Declares a crafter host and the grid positions disabled for input. */
context(menu: MenuScope)
public fun crafter(
    title: Component,
    disabledSlots: Set<CrafterSlot> = emptySet(),
    content: context(RoleHostScope<CrafterSlot>) () -> Unit,
): Unit = roleHost(
    title,
    9,
    CrafterSlot::index,
    { slots -> CrafterHostSnapshot(title, slots, disabledSlots) },
    content,
)

/** Declares an enchantment-table host with exactly three offer positions. */
context(menu: MenuScope)
public fun enchantment(
    title: Component,
    seed: Int,
    offers: List<EnchantmentOfferSnapshot?> = List(3) { null },
    content: context(RoleHostScope<EnchantmentSlot>) () -> Unit,
): Unit = roleHost(
    title,
    2,
    EnchantmentSlot::index,
    { slots -> EnchantmentHostSnapshot(title, slots, seed, offers) },
    content,
)

/** Declares a grindstone host with typed [GrindstoneSlot] positions. */
context(menu: MenuScope)
public fun grindstone(
    title: Component,
    content: context(RoleHostScope<GrindstoneSlot>) () -> Unit,
): Unit = roleHost(title, 3, GrindstoneSlot::index, { slots -> GrindstoneHostSnapshot(title, slots) }, content)

/** Declares a smithing-table host with typed [SmithingSlot] positions. */
context(menu: MenuScope)
public fun smithing(
    title: Component,
    content: context(RoleHostScope<SmithingSlot>) () -> Unit,
): Unit = roleHost(title, 4, SmithingSlot::index, { slots -> SmithingHostSnapshot(title, slots) }, content)

/** Declares a loom host with typed [LoomSlot] positions. */
context(menu: MenuScope)
public fun loom(
    title: Component,
    content: context(RoleHostScope<LoomSlot>) () -> Unit,
): Unit = roleHost(title, 4, LoomSlot::index, { slots -> LoomHostSnapshot(title, slots) }, content)

/** Declares a cartography-table host with typed [CartographySlot] positions. */
context(menu: MenuScope)
public fun cartography(
    title: Component,
    content: context(RoleHostScope<CartographySlot>) () -> Unit,
): Unit = roleHost(title, 3, CartographySlot::index, { slots -> CartographyHostSnapshot(title, slots) }, content)

/** Declares a stonecutter host with typed [StonecutterSlot] positions. */
context(menu: MenuScope)
public fun stonecutter(
    title: Component,
    content: context(RoleHostScope<StonecutterSlot>) () -> Unit,
): Unit = roleHost(title, 2, StonecutterSlot::index, { slots -> StonecutterHostSnapshot(title, slots) }, content)

/** Declares a beacon host with the effects selected in its native controls. */
context(menu: MenuScope)
public fun beacon(
    title: Component,
    primaryEffect: PotionEffectType? = null,
    secondaryEffect: PotionEffectType? = null,
    content: context(RoleHostScope<BeaconSlot>) () -> Unit,
): Unit = roleHost(
    title,
    1,
    BeaconSlot::index,
    { slots -> BeaconHostSnapshot(title, slots, primaryEffect, secondaryEffect) },
    content,
)

/** Declares a lectern host at zero-based [page]. */
context(menu: MenuScope)
public fun lectern(
    title: Component,
    page: Int = 0,
    content: context(RoleHostScope<LecternSlot>) () -> Unit,
): Unit = roleHost(title, 1, LecternSlot::index, { slots -> LecternHostSnapshot(title, slots, page) }, content)

context(menu: MenuScope)
private fun furnaceHost(
    title: Component,
    kind: FurnaceHostKind,
    cooking: MenuProgress,
    burning: MenuProgress,
    content: context(RoleHostScope<FurnaceSlot>) () -> Unit,
) = roleHost(
    title,
    3,
    FurnaceSlot::index,
    { slots -> FurnaceHostSnapshot(title, slots, kind, cooking, burning) },
    content,
)

context(menu: MenuScope)
private fun containerHost(
    title: Component,
    capacity: Int,
    snapshot: (List<MenuSlotSnapshot>) -> MenuHostSnapshot,
    content: context(ContainerHostScope) () -> Unit,
) {
    menu.builder.containerHost(menu, RenderedHostDescriptor(title, capacity, snapshot), content)
}

context(menu: MenuScope)
private fun <R : Enum<R>> roleHost(
    title: Component,
    capacity: Int,
    index: (R) -> Int,
    snapshot: (List<MenuSlotSnapshot>) -> MenuHostSnapshot,
    content: context(RoleHostScope<R>) () -> Unit,
) {
    menu.builder.roleHost(menu, RenderedHostDescriptor(title, capacity, snapshot), index, content)
}
