package pink.alex.ashlar.menus.internal.paper

import pink.alex.ashlar.execution.PlayerRef
import pink.alex.ashlar.menus.EnchantmentButton
import pink.alex.ashlar.menus.LecternPageDirection
import pink.alex.ashlar.menus.MenuHostInput
import io.papermc.paper.event.player.PlayerChangeBeaconEffectEvent
import io.papermc.paper.event.player.PlayerLecternPageChangeEvent
import java.lang.reflect.Proxy
import java.util.UUID
import org.bukkit.block.Block
import org.bukkit.block.Lectern
import org.bukkit.entity.Player
import org.bukkit.event.enchantment.EnchantItemEvent
import org.bukkit.event.inventory.PrepareAnvilEvent
import org.bukkit.event.inventory.TradeSelectEvent
import org.bukkit.inventory.AnvilInventory
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryView
import org.bukkit.inventory.MerchantInventory
import org.bukkit.inventory.PlayerInventory
import org.bukkit.inventory.view.AnvilView
import org.bukkit.inventory.view.MerchantView
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PaperMenuHostInputEventsTest {
    @Test
    fun `anvil rename changes are deduplicated and projected without a native event`() {
        var renameText = ""
        val fixture = viewFixture<AnvilView, AnvilInventory>(3) { renameText }
        val inputs = mutableListOf<MenuHostInput>()
        val events = boundEvents(fixture, inputs)
        renameText = "Market"
        val event = PrepareAnvilEvent(fixture.view, null)

        events.onPrepareAnvil(event)
        events.onPrepareAnvil(event)

        val input = assertIs<MenuHostInput.AnvilRenameText>(inputs.single())
        assertEquals("Market", input.text)
        events.close()
    }

    @Test
    fun `merchant selection is cancelled and projected by stable index`() {
        val fixture = viewFixture<MerchantView, MerchantInventory>(3)
        val inputs = mutableListOf<MenuHostInput>()
        val events = boundEvents(fixture, inputs)
        val event = TradeSelectEvent(fixture.view, 2)

        events.onTradeSelected(event)

        assertTrue(event.isCancelled)
        assertEquals(2, assertIs<MenuHostInput.MerchantTradeSelected>(inputs.single()).index)
        events.close()
    }

    @Test
    fun `enchantment button is cancelled before typed projection`() {
        val fixture = viewFixture<InventoryView, Inventory>(2)
        val inputs = mutableListOf<MenuHostInput>()
        val events = boundEvents(fixture, inputs)
        val event = enchantmentEvent(fixture, button = 1)

        events.onEnchantmentButton(event)

        assertTrue(event.isCancelled)
        assertEquals(
            EnchantmentButton.SECOND,
            assertIs<MenuHostInput.EnchantmentButtonPressed>(inputs.single()).button,
        )
        events.close()
    }

    @Test
    fun `beacon and lectern protocols are cancelled and detached`() {
        val fixture = viewFixture<InventoryView, Inventory>(1)
        val inputs = mutableListOf<MenuHostInput>()
        val events = boundEvents(fixture, inputs)
        val beacon = PlayerChangeBeaconEffectEvent(fixture.player, null, null, proxy())
        val lectern = lecternEvent(fixture.player, oldPage = 3, newPage = 4)

        events.onBeaconEffectsSelected(beacon)
        events.onLecternPageChanged(lectern)

        assertTrue(beacon.isCancelled)
        assertTrue(lectern.isCancelled)
        assertIs<MenuHostInput.BeaconEffectsSelected>(inputs[0])
        val page = assertIs<MenuHostInput.LecternPageChanged>(inputs[1])
        assertEquals(3, page.previousPage)
        assertEquals(4, page.page)
        assertEquals(LecternPageDirection.NEXT, page.direction)
        events.close()
    }

    private fun <V : InventoryView, I : Inventory> boundEvents(
        fixture: ViewFixture<V, I>,
        inputs: MutableList<MenuHostInput>,
    ): PaperMenuEvents = PaperMenuEvents().also { events ->
        events.bind(
            fixture.view,
            PlayerRef(fixture.playerId),
            revision = { 12 },
            interaction = {},
            hostInput = inputs::add,
            nativeClose = { _, _ -> },
        )
    }

    private inline fun <reified V : InventoryView, reified I : Inventory> viewFixture(
        topSize: Int,
        crossinline renameText: () -> String? = { null },
    ): ViewFixture<V, I> {
        val playerId = UUID.randomUUID()
        val playerInventory = inventory<PlayerInventory>(43)
        val top = inventory<I>(topSize)
        lateinit var view: V
        val player = proxy<Player> { method ->
            when (method.name) {
                "getUniqueId" -> playerId
                "getName" -> "MenuTester"
                "getInventory" -> playerInventory
                "getOpenInventory" -> view
                else -> defaultValue(method.returnType)
            }
        }
        view = proxy { method ->
            when (method.name) {
                "getPlayer" -> player
                "getTopInventory" -> top
                "getBottomInventory" -> playerInventory
                "getItem", "getCursor" -> null
                "countSlots" -> topSize + 36
                "getRenameText" -> renameText()
                else -> defaultValue(method.returnType)
            }
        }
        return ViewFixture(playerId, player, view, top)
    }

    private inline fun <reified I : Inventory> inventory(size: Int): I = proxy { method ->
        when (method.name) {
            "getSize" -> size
            "getItem" -> null
            else -> defaultValue(method.returnType)
        }
    }

    private fun enchantmentEvent(fixture: ViewFixture<*, *>, button: Int): EnchantItemEvent {
        val constructor = EnchantItemEvent::class.java.declaredConstructors.single()
        return constructor.newInstance(
            fixture.player,
            fixture.view,
            null,
            null,
            1,
            emptyMap<Any, Any>(),
            null,
            1,
            button,
        ) as EnchantItemEvent
    }

    private fun lecternEvent(player: Player, oldPage: Int, newPage: Int): PlayerLecternPageChangeEvent {
        val constructor = PlayerLecternPageChangeEvent::class.java.declaredConstructors.single()
        return constructor.newInstance(
            player,
            proxy<Lectern>(),
            null,
            PlayerLecternPageChangeEvent.PageChangeDirection.RIGHT,
            oldPage,
            newPage,
        ) as PlayerLecternPageChangeEvent
    }

    private inline fun <reified T> proxy(
        crossinline invoke: (java.lang.reflect.Method) -> Any? = { method -> defaultValue(method.returnType) },
    ): T = Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
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

    private data class ViewFixture<V : InventoryView, I : Inventory>(
        val playerId: UUID,
        val player: Player,
        val view: V,
        val top: I,
    )
}
