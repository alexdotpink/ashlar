package dev.placeholder.framework.menus.internal.paper

import dev.placeholder.framework.execution.EntityContext
import dev.placeholder.framework.items.ItemPresentation
import dev.placeholder.framework.items.ItemPresentationContext
import dev.placeholder.framework.items.Items
import dev.placeholder.framework.menus.ChestHostSnapshot
import org.bukkit.entity.Player
import org.bukkit.inventory.InventoryView
import org.bukkit.inventory.MenuType

/** Entity-owned native presentation operations for an action-only chest. */
internal object PaperChestPresentation {
    context(entityContext: EntityContext)
    fun create(
        player: Player,
        chest: ChestHostSnapshot,
    ): InventoryView {
        requireOwnedPlayer(entityContext, player)
        val view = menuType(chest.rows).create(player, chest.title)
        writeSlots(entityContext, player, view, chest, 0 until chest.rows * 9)
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
        chest: ChestHostSnapshot,
        changedSlots: Set<Int>,
    ) {
        requireOwnedPlayer(entityContext, player)
        require(view.player === player) { "The chest view belongs to another player" }
        require(view.topInventory.size == chest.rows * 9) { "A chest capacity change requires a remount" }
        require(view.title() == chest.title) { "A chest title change requires a remount" }
        writeSlots(entityContext, player, view, chest, changedSlots)
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
        chest: ChestHostSnapshot,
        slots: Iterable<Int>,
    ) {
        val presentation = ItemPresentationContext(
            viewerId = player.uniqueId,
            locale = player.locale(),
        )
        slots.forEach { slot ->
            require(slot in 0 until chest.rows * 9) { "Chest slot $slot is outside ${chest.rows} rows" }
            val declared = chest[slot]
            val item = declared?.storedItem?.let(Items::materialize) ?: declared?.item?.let { spec ->
                Items.materialize(spec, ItemPresentation.MenuAction, presentation)
            }
            entityContext.checkOwnership()
            view.topInventory.setItem(slot, item)
        }
    }

    private fun requireOwnedPlayer(entityContext: EntityContext, player: Player) {
        require(entityContext.entity === player) { "The entity context does not own this menu player" }
        entityContext.checkOwnership()
    }

    private fun menuType(rows: Int): MenuType.Typed<InventoryView, *> = when (rows) {
        1 -> MenuType.GENERIC_9X1
        2 -> MenuType.GENERIC_9X2
        3 -> MenuType.GENERIC_9X3
        4 -> MenuType.GENERIC_9X4
        5 -> MenuType.GENERIC_9X5
        6 -> MenuType.GENERIC_9X6
        else -> error("Chest rows must be between 1 and 6, got $rows")
    }
}
