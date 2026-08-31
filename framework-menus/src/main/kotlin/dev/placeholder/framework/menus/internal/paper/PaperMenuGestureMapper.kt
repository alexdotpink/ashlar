package dev.placeholder.framework.menus.internal.paper

import dev.placeholder.framework.menus.MenuGesture
import dev.placeholder.framework.menus.MenuOutsideButton
import dev.placeholder.framework.menus.PlayerInventorySlot
import dev.placeholder.framework.menus.storage.MenuDragMode
import dev.placeholder.framework.menus.storage.PlayerInventorySection
import org.bukkit.event.inventory.ClickType

/** Projects Paper's mutable click vocabulary into detached framework gestures. */
internal object PaperMenuGestureMapper {
    fun click(
        click: ClickType,
        hotbarButton: Int,
        outside: Boolean,
    ): MenuGesture? {
        if (outside) {
            val button = when (click) {
                ClickType.RIGHT,
                ClickType.WINDOW_BORDER_RIGHT,
                -> MenuOutsideButton.SECONDARY
                else -> MenuOutsideButton.PRIMARY
            }
            return MenuGesture.Outside(button)
        }
        return when (click) {
            ClickType.LEFT -> MenuGesture.Primary
            ClickType.RIGHT -> MenuGesture.Secondary
            ClickType.MIDDLE -> MenuGesture.Middle
            ClickType.SHIFT_LEFT -> MenuGesture.ShiftPrimary
            ClickType.SHIFT_RIGHT -> MenuGesture.ShiftSecondary
            ClickType.NUMBER_KEY -> hotbarButton
                .takeIf { index -> index in 0..8 }
                ?.let { index -> MenuGesture.NumberKey(index) }
            ClickType.SWAP_OFFHAND -> MenuGesture.SwapOffhand
            ClickType.DROP -> MenuGesture.DropOne
            ClickType.CONTROL_DROP -> MenuGesture.DropStack
            ClickType.DOUBLE_CLICK -> MenuGesture.DoubleClick
            ClickType.CREATIVE -> MenuGesture.Creative
            ClickType.WINDOW_BORDER_LEFT,
            -> MenuGesture.Outside(MenuOutsideButton.PRIMARY)
            ClickType.WINDOW_BORDER_RIGHT -> MenuGesture.Outside(MenuOutsideButton.SECONDARY)
            ClickType.UNKNOWN -> null
        }
    }

    fun drag(
        hostSlots: List<Int>,
        playerSlots: List<PlayerInventorySlot>,
        mode: MenuDragMode,
    ): MenuGesture.Drag? =
        if (hostSlots.isEmpty() && playerSlots.isEmpty()) null else MenuGesture.Drag(mode)

    fun playerSlot(index: Int): PlayerInventorySlot? = when (index) {
        in 0..8 -> PlayerInventorySlot(PlayerInventorySection.HOTBAR, index)
        in 9..35 -> PlayerInventorySlot(PlayerInventorySection.MAIN, index - 9)
        in 36..39 -> PlayerInventorySlot(PlayerInventorySection.ARMOR, index - 36)
        40 -> PlayerInventorySlot(PlayerInventorySection.OFFHAND, 0)
        else -> null
    }
}
