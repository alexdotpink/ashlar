package dev.placeholder.framework.menus.internal.paper

import dev.placeholder.framework.execution.EntityContext
import dev.placeholder.framework.items.ItemPresentation
import dev.placeholder.framework.items.ItemPresentationContext
import dev.placeholder.framework.items.Items
import dev.placeholder.framework.menus.AnvilHostSnapshot
import dev.placeholder.framework.menus.BeaconHostSnapshot
import dev.placeholder.framework.menus.BrewingHostSnapshot
import dev.placeholder.framework.menus.CrafterHostSnapshot
import dev.placeholder.framework.menus.EnchantmentHostSnapshot
import dev.placeholder.framework.menus.FurnaceHostKind
import dev.placeholder.framework.menus.FurnaceHostSnapshot
import dev.placeholder.framework.menus.LecternHostSnapshot
import dev.placeholder.framework.menus.MenuHostSnapshot
import dev.placeholder.framework.menus.MerchantHostSnapshot
import org.bukkit.entity.Player
import org.bukkit.enchantments.EnchantmentOffer
import org.bukkit.inventory.InventoryView
import org.bukkit.inventory.MerchantRecipe
import org.bukkit.inventory.MenuType
import org.bukkit.inventory.view.AnvilView
import org.bukkit.inventory.view.BeaconView
import org.bukkit.inventory.view.BrewingStandView
import org.bukkit.inventory.view.CrafterView
import org.bukkit.inventory.view.EnchantmentView
import org.bukkit.inventory.view.FurnaceView
import org.bukkit.inventory.view.LecternView
import org.bukkit.inventory.view.MerchantView

/** Entity-owned presentation operations shared by every supported native host. */
internal object PaperChestPresentation {
    fun requiresRemount(before: MenuHostSnapshot, after: MenuHostSnapshot): Boolean =
        nativeKind(before) != nativeKind(after) || before.title != after.title

    context(entityContext: EntityContext)
    fun create(
        player: Player,
        host: MenuHostSnapshot,
    ): InventoryView {
        requireOwnedPlayer(entityContext, player)
        val view = menuType(host).create(player, host.title)
        writeSlots(entityContext, player, view, host, 0 until host.capacity)
        applyProperties(entityContext, view, host)
        return view
    }

    context(entityContext: EntityContext)
    fun open(
        player: Player,
        view: InventoryView,
    ) {
        requireOwnedPlayer(entityContext, player)
        require(view.player === player) { "The chest view belongs to another player" }
        entityContext.checkOwnership()
        player.openInventory(view)
        entityContext.checkOwnership()
        check(player.openInventory.topInventory === view.topInventory) { "Paper rejected the framework menu view" }
    }

    context(entityContext: EntityContext)
    fun update(
        player: Player,
        view: InventoryView,
        host: MenuHostSnapshot,
        changedSlots: Set<Int>,
    ) {
        requireOwnedPlayer(entityContext, player)
        require(view.player === player) { "The chest view belongs to another player" }
        require(view.topInventory.size == host.capacity) { "A host capacity change requires a remount" }
        require(view.title() == host.title) { "A host title change requires a remount" }
        writeSlots(entityContext, player, view, host, changedSlots)
        applyProperties(entityContext, view, host)
    }

    context(entityContext: EntityContext)
    fun close(
        player: Player,
        view: InventoryView,
    ) {
        requireOwnedPlayer(entityContext, player)
        if (player.openInventory.topInventory === view.topInventory) {
            entityContext.checkOwnership()
            view.close()
        }
    }

    private fun writeSlots(
        entityContext: EntityContext,
        player: Player,
        view: InventoryView,
        host: MenuHostSnapshot,
        slots: Iterable<Int>,
    ) {
        val presentation = ItemPresentationContext(
            viewerId = player.uniqueId,
            locale = player.locale(),
        )
        slots.forEach { slot ->
            require(slot in 0 until host.capacity) { "Slot $slot is outside this ${host.capacity}-slot host" }
            val declared = host.slots.firstOrNull { candidate -> candidate.index == slot }
            val item = declared?.storedItem?.let(Items::materialize) ?: declared?.item?.let { spec ->
                Items.materialize(spec, ItemPresentation.MenuAction, presentation)
            }
            entityContext.checkOwnership()
            view.topInventory.setItem(slot, item)
        }
    }

    private fun applyProperties(
        entityContext: EntityContext,
        view: InventoryView,
        host: MenuHostSnapshot,
    ) {
        entityContext.checkOwnership()
        when (host) {
            is AnvilHostSnapshot -> (view as AnvilView).apply {
                repairCost = host.repairCost
                maximumRepairCost = host.maximumRepairCost
                repairItemCountCost = host.repairItemCount
                bypassEnchantmentLevelRestriction(host.bypassEnchantmentLevelRestriction)
            }
            is MerchantHostSnapshot -> (view as MerchantView).merchant.recipes = host.offers.map { offer ->
                MerchantRecipe(
                    Items.materialize(offer.result),
                    offer.uses,
                    offer.maximumUses,
                    offer.rewardsExperience,
                    offer.villagerExperience,
                    offer.priceMultiplier,
                    offer.demand,
                    offer.specialPrice,
                    offer.ignoresDiscounts,
                ).apply {
                    ingredients = listOfNotNull(
                        Items.materialize(offer.firstCost),
                        offer.secondCost?.let(Items::materialize),
                    )
                }
            }
            is FurnaceHostSnapshot -> (view as FurnaceView).apply {
                setCookTime(host.cooking.current, host.cooking.total)
                setBurnTime(host.burning.current, host.burning.total)
            }
            is BrewingHostSnapshot -> (view as BrewingStandView).apply {
                fuelLevel = host.fuelLevel
                brewingTicks = host.brewingTicks
                recipeBrewTime = host.recipeBrewTime
            }
            is CrafterHostSnapshot -> (view as CrafterView).let { crafter ->
                dev.placeholder.framework.menus.CrafterSlot.entries.forEach { slot ->
                    crafter.setSlotDisabled(slot.index, slot in host.disabledSlots)
                }
            }
            is EnchantmentHostSnapshot -> (view as EnchantmentView).apply {
                enchantmentSeed = host.seed
                offers = host.offers.map { offer ->
                    offer?.let { EnchantmentOffer(it.enchantment, it.level, it.cost) }
                }.toTypedArray()
            }
            is BeaconHostSnapshot -> (view as BeaconView).apply {
                primaryEffect = host.primaryEffect
                secondaryEffect = host.secondaryEffect
            }
            is LecternHostSnapshot -> (view as LecternView).page = host.page
            else -> Unit
        }
    }

    private fun requireOwnedPlayer(entityContext: EntityContext, player: Player) {
        require(entityContext.entity === player) { "The entity context does not own this menu player" }
        entityContext.checkOwnership()
    }

    internal fun nativeKind(host: MenuHostSnapshot): PaperMenuKind = when (host) {
        is MenuHostSnapshot.Chest -> when (host.chest.rows) {
            1 -> PaperMenuKind.GENERIC_9X1
            2 -> PaperMenuKind.GENERIC_9X2
            3 -> PaperMenuKind.GENERIC_9X3
            4 -> PaperMenuKind.GENERIC_9X4
            5 -> PaperMenuKind.GENERIC_9X5
            6 -> PaperMenuKind.GENERIC_9X6
            else -> error("Chest rows must be between 1 and 6")
        }
        is dev.placeholder.framework.menus.HopperHostSnapshot -> PaperMenuKind.HOPPER
        is dev.placeholder.framework.menus.Generic3x3HostSnapshot -> PaperMenuKind.GENERIC_3X3
        is dev.placeholder.framework.menus.ShulkerHostSnapshot -> PaperMenuKind.SHULKER_BOX
        is AnvilHostSnapshot -> PaperMenuKind.ANVIL
        is MerchantHostSnapshot -> PaperMenuKind.MERCHANT
        is FurnaceHostSnapshot -> when (host.kind) {
            FurnaceHostKind.FURNACE -> PaperMenuKind.FURNACE
            FurnaceHostKind.BLAST_FURNACE -> PaperMenuKind.BLAST_FURNACE
            FurnaceHostKind.SMOKER -> PaperMenuKind.SMOKER
        }
        is BrewingHostSnapshot -> PaperMenuKind.BREWING_STAND
        is dev.placeholder.framework.menus.CraftingHostSnapshot -> PaperMenuKind.CRAFTING
        is CrafterHostSnapshot -> PaperMenuKind.CRAFTER_3X3
        is EnchantmentHostSnapshot -> PaperMenuKind.ENCHANTMENT
        is dev.placeholder.framework.menus.GrindstoneHostSnapshot -> PaperMenuKind.GRINDSTONE
        is dev.placeholder.framework.menus.SmithingHostSnapshot -> PaperMenuKind.SMITHING
        is dev.placeholder.framework.menus.LoomHostSnapshot -> PaperMenuKind.LOOM
        is dev.placeholder.framework.menus.CartographyHostSnapshot -> PaperMenuKind.CARTOGRAPHY_TABLE
        is dev.placeholder.framework.menus.StonecutterHostSnapshot -> PaperMenuKind.STONECUTTER
        is BeaconHostSnapshot -> PaperMenuKind.BEACON
        is LecternHostSnapshot -> PaperMenuKind.LECTERN
    }

    private fun menuType(host: MenuHostSnapshot): MenuType = when (nativeKind(host)) {
        PaperMenuKind.GENERIC_9X1 -> MenuType.GENERIC_9X1
        PaperMenuKind.GENERIC_9X2 -> MenuType.GENERIC_9X2
        PaperMenuKind.GENERIC_9X3 -> MenuType.GENERIC_9X3
        PaperMenuKind.GENERIC_9X4 -> MenuType.GENERIC_9X4
        PaperMenuKind.GENERIC_9X5 -> MenuType.GENERIC_9X5
        PaperMenuKind.GENERIC_9X6 -> MenuType.GENERIC_9X6
        PaperMenuKind.GENERIC_3X3 -> MenuType.GENERIC_3X3
        PaperMenuKind.HOPPER -> MenuType.HOPPER
        PaperMenuKind.SHULKER_BOX -> MenuType.SHULKER_BOX
        PaperMenuKind.ANVIL -> MenuType.ANVIL
        PaperMenuKind.MERCHANT -> MenuType.MERCHANT
        PaperMenuKind.FURNACE -> MenuType.FURNACE
        PaperMenuKind.BLAST_FURNACE -> MenuType.BLAST_FURNACE
        PaperMenuKind.SMOKER -> MenuType.SMOKER
        PaperMenuKind.BREWING_STAND -> MenuType.BREWING_STAND
        PaperMenuKind.CRAFTING -> MenuType.CRAFTING
        PaperMenuKind.CRAFTER_3X3 -> MenuType.CRAFTER_3X3
        PaperMenuKind.ENCHANTMENT -> MenuType.ENCHANTMENT
        PaperMenuKind.GRINDSTONE -> MenuType.GRINDSTONE
        PaperMenuKind.SMITHING -> MenuType.SMITHING
        PaperMenuKind.LOOM -> MenuType.LOOM
        PaperMenuKind.CARTOGRAPHY_TABLE -> MenuType.CARTOGRAPHY_TABLE
        PaperMenuKind.STONECUTTER -> MenuType.STONECUTTER
        PaperMenuKind.BEACON -> MenuType.BEACON
        PaperMenuKind.LECTERN -> MenuType.LECTERN
    }
}

internal enum class PaperMenuKind {
    GENERIC_9X1, GENERIC_9X2, GENERIC_9X3, GENERIC_9X4, GENERIC_9X5, GENERIC_9X6,
    GENERIC_3X3, HOPPER, SHULKER_BOX, ANVIL, MERCHANT, FURNACE, BLAST_FURNACE,
    SMOKER, BREWING_STAND, CRAFTING, CRAFTER_3X3, ENCHANTMENT, GRINDSTONE, SMITHING,
    LOOM, CARTOGRAPHY_TABLE, STONECUTTER, BEACON, LECTERN,
}
