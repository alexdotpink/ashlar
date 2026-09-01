package pink.alex.ashlar.menus.internal.paper

import pink.alex.ashlar.menus.MenuGesture
import pink.alex.ashlar.menus.MenuOutsideButton
import pink.alex.ashlar.menus.PlayerInventorySlot
import pink.alex.ashlar.menus.storage.MenuDragMode
import pink.alex.ashlar.menus.storage.PlayerInventorySection
import org.bukkit.event.inventory.ClickType
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PaperMenuGestureMapperTest {
    @Test
    fun `projects every supported click without exposing Paper`() {
        val mappings = mapOf(
            ClickType.LEFT to MenuGesture.Primary,
            ClickType.RIGHT to MenuGesture.Secondary,
            ClickType.MIDDLE to MenuGesture.Middle,
            ClickType.SHIFT_LEFT to MenuGesture.ShiftPrimary,
            ClickType.SHIFT_RIGHT to MenuGesture.ShiftSecondary,
            ClickType.SWAP_OFFHAND to MenuGesture.SwapOffhand,
            ClickType.DROP to MenuGesture.DropOne,
            ClickType.CONTROL_DROP to MenuGesture.DropStack,
            ClickType.DOUBLE_CLICK to MenuGesture.DoubleClick,
            ClickType.CREATIVE to MenuGesture.Creative,
            ClickType.WINDOW_BORDER_LEFT to MenuGesture.Outside(MenuOutsideButton.PRIMARY),
            ClickType.WINDOW_BORDER_RIGHT to MenuGesture.Outside(MenuOutsideButton.SECONDARY),
        )

        mappings.forEach { (click, expected) ->
            assertEquals(expected, PaperMenuGestureMapper.click(click, -1, outside = false))
        }
        assertEquals(
            MenuGesture.NumberKey(4),
            PaperMenuGestureMapper.click(ClickType.NUMBER_KEY, 4, outside = false),
        )
    }

    @Test
    fun `outside raw slots override the mouse button`() {
        assertEquals(
            MenuGesture.Outside(MenuOutsideButton.PRIMARY),
            PaperMenuGestureMapper.click(ClickType.LEFT, -1, outside = true),
        )
        assertEquals(
            MenuGesture.Outside(MenuOutsideButton.SECONDARY),
            PaperMenuGestureMapper.click(ClickType.RIGHT, -1, outside = true),
        )
    }

    @Test
    fun `unknown and malformed number-key clicks are not dispatched`() {
        assertNull(PaperMenuGestureMapper.click(ClickType.UNKNOWN, -1, outside = false))
        assertNull(PaperMenuGestureMapper.click(ClickType.NUMBER_KEY, -1, outside = false))
        assertNull(PaperMenuGestureMapper.click(ClickType.NUMBER_KEY, 9, outside = false))
    }

    @Test
    fun `drag projection retains only owned host slots in stable order`() {
        assertEquals(
            MenuGesture.Drag(MenuDragMode.EVEN),
            PaperMenuGestureMapper.drag(
                hostSlots = listOf(0, 8, 26),
                playerSlots = emptyList(),
                mode = MenuDragMode.EVEN,
            ),
        )
        assertNull(
            PaperMenuGestureMapper.drag(
                hostSlots = emptyList(),
                playerSlots = emptyList(),
                mode = MenuDragMode.SINGLE,
            ),
        )
    }

    @Test
    fun `player inventory indexes become typed stable coordinates`() {
        assertEquals(
            PlayerInventorySlot(PlayerInventorySection.HOTBAR, 8),
            PaperMenuGestureMapper.playerSlot(8),
        )
        assertEquals(
            PlayerInventorySlot(PlayerInventorySection.MAIN, 0),
            PaperMenuGestureMapper.playerSlot(9),
        )
        assertEquals(
            PlayerInventorySlot(PlayerInventorySection.ARMOR, 3),
            PaperMenuGestureMapper.playerSlot(39),
        )
        assertEquals(
            PlayerInventorySlot(PlayerInventorySection.OFFHAND, 0),
            PaperMenuGestureMapper.playerSlot(40),
        )
        assertNull(PaperMenuGestureMapper.playerSlot(41))
    }
}
