package dev.placeholder.framework.menus

import dev.placeholder.framework.items.ItemSpec
import net.kyori.adventure.text.Component
import org.bukkit.enchantments.Enchantment
import org.bukkit.potion.PotionEffectType

/**
 * Progress represented as a current value and a positive total.
 *
 * @property current completed units, which may exceed [total] when the native protocol permits it
 * @property total positive number of units for a complete bar
 */
public data class MenuProgress(public val current: Int, public val total: Int) {
    init {
        require(current >= 0) { "Progress cannot be negative" }
        require(total > 0) { "Progress total must be positive" }
    }
}

/** Furnace-shaped native host kind. */
public enum class FurnaceHostKind { FURNACE, BLAST_FURNACE, SMOKER }

/** Typed positions in an anvil top inventory. */
public enum class AnvilSlot(public val index: Int) { LEFT(0), RIGHT(1), RESULT(2) }
/** Typed positions in a merchant top inventory. */
public enum class MerchantSlot(public val index: Int) { FIRST_COST(0), SECOND_COST(1), RESULT(2) }
/** Typed positions shared by furnace, blast-furnace, and smoker hosts. */
public enum class FurnaceSlot(public val index: Int) { INPUT(0), FUEL(1), RESULT(2) }
/** Typed positions in a brewing-stand top inventory. */
public enum class BrewingSlot(public val index: Int) {
    BOTTLE_ONE(0), BOTTLE_TWO(1), BOTTLE_THREE(2), INGREDIENT(3), FUEL(4),
}
/** Typed positions in a crafting-table top inventory. */
public enum class CraftingSlot(public val index: Int) {
    RESULT(0), GRID_ONE(1), GRID_TWO(2), GRID_THREE(3), GRID_FOUR(4), GRID_FIVE(5),
    GRID_SIX(6), GRID_SEVEN(7), GRID_EIGHT(8), GRID_NINE(9),
}
/** Typed positions in a crafter top inventory. */
public enum class CrafterSlot(public val index: Int) {
    GRID_ONE(0), GRID_TWO(1), GRID_THREE(2), GRID_FOUR(3), GRID_FIVE(4),
    GRID_SIX(5), GRID_SEVEN(6), GRID_EIGHT(7), GRID_NINE(8),
}
/** Typed positions in an enchantment-table top inventory. */
public enum class EnchantmentSlot(public val index: Int) { ITEM(0), LAPIS(1) }
/** Typed positions in a grindstone top inventory. */
public enum class GrindstoneSlot(public val index: Int) { TOP(0), BOTTOM(1), RESULT(2) }
/** Typed positions in a smithing-table top inventory. */
public enum class SmithingSlot(public val index: Int) { TEMPLATE(0), BASE(1), ADDITION(2), RESULT(3) }
/** Typed positions in a loom top inventory. */
public enum class LoomSlot(public val index: Int) { BANNER(0), DYE(1), PATTERN(2), RESULT(3) }
/** Typed positions in a cartography-table top inventory. */
public enum class CartographySlot(public val index: Int) { MAP(0), ADDITION(1), RESULT(2) }
/** Typed positions in a stonecutter top inventory. */
public enum class StonecutterSlot(public val index: Int) { INPUT(0), RESULT(1) }
/** Typed positions in a beacon top inventory. */
public enum class BeaconSlot(public val index: Int) { PAYMENT(0) }
/** Typed positions in a lectern top inventory. */
public enum class LecternSlot(public val index: Int) { BOOK(0) }

/**
 * One immutable custom merchant offer.
 *
 * Prices, usage counters, experience, demand, and discount fields map directly to the native
 * merchant recipe shown to the player.
 */
public data class MerchantOfferSnapshot(
    public val firstCost: ItemSpec,
    public val result: ItemSpec,
    public val secondCost: ItemSpec? = null,
    public val uses: Int = 0,
    public val maximumUses: Int = Int.MAX_VALUE,
    public val rewardsExperience: Boolean = true,
    public val villagerExperience: Int = 0,
    public val priceMultiplier: Float = 0f,
    public val demand: Int = 0,
    public val specialPrice: Int = 0,
    public val ignoresDiscounts: Boolean = false,
) {
    init {
        require(uses >= 0) { "Merchant offer uses cannot be negative" }
        require(maximumUses > 0) { "Merchant maximum uses must be positive" }
        require(uses <= maximumUses) { "Merchant uses cannot exceed maximum uses" }
        require(villagerExperience >= 0) { "Merchant experience cannot be negative" }
        require(priceMultiplier >= 0f) { "Merchant price multiplier cannot be negative" }
    }
}

/**
 * One declared enchantment button. Null entries let vanilla leave a button unavailable.
 *
 * @property enchantment enchantment previewed by the client
 * @property level positive enchantment level
 * @property cost positive experience-level cost displayed for the button
 */
public data class EnchantmentOfferSnapshot(
    public val enchantment: Enchantment,
    public val level: Int,
    public val cost: Int,
) {
    init {
        require(level > 0) { "Enchantment level must be positive" }
        require(cost > 0) { "Enchantment cost must be positive" }
    }
}

/** Immutable five-slot hopper host produced by a committed render. */
public data class HopperHostSnapshot(
    override val title: Component,
    override val slots: List<MenuSlotSnapshot>,
) : MenuHostSnapshot {
    override val capacity: Int = 5
    init { validateHostSlots("hopper", capacity, slots) }
}

/** Immutable generic three-by-three host produced by a committed render. */
public data class Generic3x3HostSnapshot(
    override val title: Component,
    override val slots: List<MenuSlotSnapshot>,
) : MenuHostSnapshot {
    override val capacity: Int = 9
    init { validateHostSlots("generic 3x3", capacity, slots) }
}

/** Immutable 27-slot shulker host produced by a committed render. */
public data class ShulkerHostSnapshot(
    override val title: Component,
    override val slots: List<MenuSlotSnapshot>,
) : MenuHostSnapshot {
    override val capacity: Int = 27
    init { validateHostSlots("shulker", capacity, slots) }
}

/**
 * Immutable anvil host, including the properties applied to its native inventory.
 *
 * [repairCost], [maximumRepairCost], [repairItemCount], and
 * [bypassEnchantmentLevelRestriction] are applied to the native anvil view.
 */
public data class AnvilHostSnapshot(
    override val title: Component,
    override val slots: List<MenuSlotSnapshot>,
    public val repairCost: Int = 0,
    public val maximumRepairCost: Int = 40,
    public val repairItemCount: Int = 0,
    public val bypassEnchantmentLevelRestriction: Boolean = false,
) : MenuHostSnapshot {
    override val capacity: Int = 3
    init {
        validateHostSlots("anvil", capacity, slots)
        require(repairCost >= 0 && maximumRepairCost >= 0 && repairItemCount >= 0)
    }
}

/** Immutable merchant host and its ordered [offers]. */
public data class MerchantHostSnapshot(
    override val title: Component,
    override val slots: List<MenuSlotSnapshot>,
    public val offers: List<MerchantOfferSnapshot>,
) : MenuHostSnapshot {
    override val capacity: Int = 3
    init { validateHostSlots("merchant", capacity, slots) }
}

/**
 * Immutable furnace-shaped host.
 *
 * [kind] selects the native inventory type. [cooking] and [burning] control its progress bars.
 */
public data class FurnaceHostSnapshot(
    override val title: Component,
    override val slots: List<MenuSlotSnapshot>,
    public val kind: FurnaceHostKind = FurnaceHostKind.FURNACE,
    public val cooking: MenuProgress = MenuProgress(0, 1),
    public val burning: MenuProgress = MenuProgress(0, 1),
) : MenuHostSnapshot {
    override val capacity: Int = 3
    init { validateHostSlots(kind.name.lowercase(), capacity, slots) }
}

/**
 * Immutable brewing host with the values displayed by its native progress bars.
 *
 * [fuelLevel] is the remaining fuel display. [brewingTicks] counts the current brew, whose full
 * duration is [recipeBrewTime].
 */
public data class BrewingHostSnapshot(
    override val title: Component,
    override val slots: List<MenuSlotSnapshot>,
    public val fuelLevel: Int = 0,
    public val brewingTicks: Int = 0,
    public val recipeBrewTime: Int = 400,
) : MenuHostSnapshot {
    override val capacity: Int = 5
    init {
        validateHostSlots("brewing", capacity, slots)
        require(fuelLevel >= 0 && brewingTicks >= 0 && recipeBrewTime > 0)
    }
}

/** Immutable crafting-table host produced by a committed render. */
public data class CraftingHostSnapshot(
    override val title: Component,
    override val slots: List<MenuSlotSnapshot>,
) : MenuHostSnapshot {
    override val capacity: Int = 10
    init { validateHostSlots("crafting", capacity, slots) }
}

/** Immutable crafter host with [disabledSlots] blocked in its native grid. */
public data class CrafterHostSnapshot(
    override val title: Component,
    override val slots: List<MenuSlotSnapshot>,
    public val disabledSlots: Set<CrafterSlot> = emptySet(),
) : MenuHostSnapshot {
    override val capacity: Int = 9
    init { validateHostSlots("crafter", capacity, slots) }
}

/** Immutable enchantment host with its client [seed] and exactly three ordered [offers]. */
public data class EnchantmentHostSnapshot(
    override val title: Component,
    override val slots: List<MenuSlotSnapshot>,
    public val seed: Int,
    public val offers: List<EnchantmentOfferSnapshot?> = List(3) { null },
) : MenuHostSnapshot {
    override val capacity: Int = 2
    init {
        validateHostSlots("enchantment", capacity, slots)
        require(offers.size == 3) { "An enchantment host needs exactly three offer positions" }
    }
}

/** Immutable grindstone host produced by a committed render. */
public data class GrindstoneHostSnapshot(
    override val title: Component,
    override val slots: List<MenuSlotSnapshot>,
) : MenuHostSnapshot { override val capacity: Int = 3; init { validateHostSlots("grindstone", capacity, slots) } }

/** Immutable smithing-table host produced by a committed render. */
public data class SmithingHostSnapshot(
    override val title: Component,
    override val slots: List<MenuSlotSnapshot>,
) : MenuHostSnapshot { override val capacity: Int = 4; init { validateHostSlots("smithing", capacity, slots) } }

/** Immutable loom host produced by a committed render. */
public data class LoomHostSnapshot(
    override val title: Component,
    override val slots: List<MenuSlotSnapshot>,
) : MenuHostSnapshot { override val capacity: Int = 4; init { validateHostSlots("loom", capacity, slots) } }

/** Immutable cartography-table host produced by a committed render. */
public data class CartographyHostSnapshot(
    override val title: Component,
    override val slots: List<MenuSlotSnapshot>,
) : MenuHostSnapshot { override val capacity: Int = 3; init { validateHostSlots("cartography", capacity, slots) } }

/** Immutable stonecutter host produced by a committed render. */
public data class StonecutterHostSnapshot(
    override val title: Component,
    override val slots: List<MenuSlotSnapshot>,
) : MenuHostSnapshot { override val capacity: Int = 2; init { validateHostSlots("stonecutter", capacity, slots) } }

/** Immutable beacon host with the currently selected [primaryEffect] and [secondaryEffect]. */
public data class BeaconHostSnapshot(
    override val title: Component,
    override val slots: List<MenuSlotSnapshot>,
    public val primaryEffect: PotionEffectType? = null,
    public val secondaryEffect: PotionEffectType? = null,
) : MenuHostSnapshot { override val capacity: Int = 1; init { validateHostSlots("beacon", capacity, slots) } }

/**
 * Immutable lectern host with its zero-based displayed [page].
 *
 * The Paper/Folia presentation requires [LecternSlot.BOOK] to contain a writable or written book.
 */
public data class LecternHostSnapshot(
    override val title: Component,
    override val slots: List<MenuSlotSnapshot>,
    public val page: Int = 0,
) : MenuHostSnapshot {
    override val capacity: Int = 1
    init { validateHostSlots("lectern", capacity, slots); require(page >= 0) { "Lectern page cannot be negative" } }
}

internal class RenderedHostDescriptor(
    val title: Component,
    val capacity: Int,
    val snapshot: (List<MenuSlotSnapshot>) -> MenuHostSnapshot,
    val owner: ComponentIdentity = ComponentIdentity(listOf("root")),
    val boundary: BoundaryIdentity? = null,
) {
    fun ownedBy(scope: MenuScope): RenderedHostDescriptor = RenderedHostDescriptor(
        title,
        capacity,
        snapshot,
        scope.identity,
        scope.boundary,
    )

    companion object {
        fun chest(title: Component, rows: Int): RenderedHostDescriptor =
            RenderedHostDescriptor(
                title,
                rows * 9,
                snapshot = { slots -> MenuHostSnapshot.Chest(ChestHostSnapshot(title, rows, slots)) },
            )
    }
}

private fun validateHostSlots(name: String, capacity: Int, slots: List<MenuSlotSnapshot>) {
    require(slots.all { slot -> slot.index in 0 until capacity }) { "A slot lies outside the $name host" }
    require(slots.map(MenuSlotSnapshot::index).distinct().size == slots.size) { "$name host slots cannot repeat" }
}
