package pink.alex.ashlar.menus.internal.paper

import pink.alex.ashlar.execution.PlayerRef
import pink.alex.ashlar.menus.MenuGesture
import pink.alex.ashlar.menus.MenuInteraction
import pink.alex.ashlar.menus.PlayerInventorySlot
import pink.alex.ashlar.menus.storage.PlayerInventorySection
import java.lang.reflect.Proxy
import java.util.UUID
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryAction
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryType
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryView
import org.bukkit.inventory.PlayerInventory
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PaperMenuEventsTest {
    @Test
    fun `owned top click is cancelled and projected before dispatch`() {
        val events = PaperMenuEvents()
        val fixture = viewFixture(topSize = 27)
        val interactions = mutableListOf<MenuInteraction>()
        events.bind(
            fixture.view,
            PlayerRef(fixture.playerId),
            revision = { 42 },
            playerInventorySections = { setOf(PlayerInventorySection.HOTBAR) },
            interaction = interactions::add,
            nativeClose = { _, _ -> },
        )
        val event = InventoryClickEvent(
            fixture.view,
            InventoryType.SlotType.CONTAINER,
            13,
            ClickType.NUMBER_KEY,
            InventoryAction.HOTBAR_SWAP,
            5,
        )

        events.onClick(event)

        assertTrue(event.isCancelled)
        assertEquals(
            MenuInteraction(
                player = PlayerRef(fixture.playerId),
                revision = 42,
                slot = 13,
                playerInventory = mapOf(PlayerInventorySection.HOTBAR to List(9) { null }),
                gesture = MenuGesture.NumberKey(5),
            ),
            interactions.single(),
        )
        assertEquals(List(9) { null }, interactions.single().playerInventory[PlayerInventorySection.HOTBAR])
        events.close()
    }

    @Test
    fun `bottom inventory mutation is cancelled and projected with a typed coordinate`() {
        val events = PaperMenuEvents()
        val fixture = viewFixture(topSize = 9)
        val interactions = mutableListOf<MenuInteraction>()
        events.bind(
            fixture.view,
            PlayerRef(fixture.playerId),
            revision = { 1 },
            interaction = interactions::add,
            nativeClose = { _, _ -> },
        )
        val event = InventoryClickEvent(
            fixture.view,
            InventoryType.SlotType.CONTAINER,
            10,
            ClickType.SHIFT_LEFT,
            InventoryAction.MOVE_TO_OTHER_INVENTORY,
        )

        events.onClick(event)

        assertTrue(event.isCancelled)
        assertEquals(1, interactions.size)
        assertEquals(PlayerInventorySlot(PlayerInventorySection.HOTBAR, 1), interactions.single().playerSlot)
        assertEquals(MenuGesture.ShiftPrimary, interactions.single().gesture)
        events.close()
    }

    @Test
    fun `internal remount close is suppressed and a later player close wins`() {
        val events = PaperMenuEvents()
        val first = viewFixture(topSize = 9)
        val second = viewFixture(topSize = 18, playerId = first.playerId)
        val closes = mutableListOf<PaperMenuCloseReason>()
        val firstBinding = events.bind(
            first.view,
            PlayerRef(first.playerId),
            revision = { 1 },
            interaction = {},
            nativeClose = { reason, _ -> closes += reason },
        )
        firstBinding.suppressClose()
        events.bind(
            second.view,
            PlayerRef(second.playerId),
            revision = { 2 },
            interaction = {},
            nativeClose = { reason, _ -> closes += reason },
        )

        events.onClose(InventoryCloseEvent(first.view, InventoryCloseEvent.Reason.OPEN_NEW))
        events.onClose(InventoryCloseEvent(second.view, InventoryCloseEvent.Reason.PLAYER))

        assertEquals(listOf(PaperMenuCloseReason.PLAYER_CLOSED), closes)
        events.close()
    }

    @Test
    fun `closing a bound view clears framework presentation items before native settlement`() {
        val events = PaperMenuEvents()
        var clears = 0
        val fixture = viewFixture(topSize = 9, onTopClear = { clears++ })
        events.bind(
            fixture.view,
            PlayerRef(fixture.playerId),
            revision = { 1 },
            interaction = {},
            nativeClose = { _, _ -> },
        )

        events.onClose(InventoryCloseEvent(fixture.view, InventoryCloseEvent.Reason.PLAYER))

        assertEquals(1, clears)
        events.close()
    }

    private fun viewFixture(
        topSize: Int,
        playerId: UUID = UUID.randomUUID(),
        onTopClear: () -> Unit = {},
    ): ViewFixture {
        val playerInventory = playerInventory()
        val player = proxy<Player> { method ->
            when (method.name) {
                "getUniqueId" -> playerId
                "getName" -> "MenuTester"
                "getInventory" -> playerInventory
                else -> defaultValue(method.returnType)
            }
        }
        val top = inventory(topSize, onTopClear)
        val bottom = playerInventory
        val view = proxy<InventoryView> { method ->
            when (method.name) {
                "getPlayer" -> player
                "getTopInventory" -> top
                "getBottomInventory" -> bottom
                "getItem", "getCursor" -> null
                "getType" -> InventoryType.CHEST
                "countSlots" -> topSize + 36
                "convertSlot" -> 1
                else -> defaultValue(method.returnType)
            }
        }
        return ViewFixture(playerId, view)
    }

    private fun inventory(size: Int, onClear: () -> Unit = {}): Inventory = proxy { method ->
        when (method.name) {
            "getSize" -> size
            "getItem" -> null
            "getType" -> InventoryType.CHEST
            "clear" -> onClear()
            else -> defaultValue(method.returnType)
        }
    }

    private fun playerInventory(): PlayerInventory = proxy { method ->
        when (method.name) {
            "getSize" -> 43
            "getItem" -> null
            "getType" -> InventoryType.PLAYER
            else -> defaultValue(method.returnType)
        }
    }

    private inline fun <reified T> proxy(crossinline invoke: (java.lang.reflect.Method) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
            invoke(method)
        } as T

    private fun defaultValue(type: Class<*>): Any? = when (type) {
        java.lang.Boolean.TYPE -> false
        java.lang.Byte.TYPE -> 0.toByte()
        java.lang.Short.TYPE -> 0.toShort()
        java.lang.Integer.TYPE -> 0
        java.lang.Long.TYPE -> 0L
        java.lang.Float.TYPE -> 0f
        java.lang.Double.TYPE -> 0.0
        java.lang.Character.TYPE -> '\u0000'
        else -> null
    }

    private data class ViewFixture(val playerId: UUID, val view: InventoryView)
}
