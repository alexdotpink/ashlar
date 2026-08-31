package dev.placeholder.framework.menus.testing

import dev.placeholder.framework.input.testing.InputTestHarness
import dev.placeholder.framework.items.ItemSnapshot
import dev.placeholder.framework.items.item
import dev.placeholder.framework.menus.MenuDispatch
import dev.placeholder.framework.menus.MenuInterception
import dev.placeholder.framework.menus.MenuTrace
import dev.placeholder.framework.menus.SlotRegion
import dev.placeholder.framework.menus.chest
import dev.placeholder.framework.menus.component
import dev.placeholder.framework.menus.effect
import dev.placeholder.framework.menus.playerInventory
import dev.placeholder.framework.menus.slot
import dev.placeholder.framework.menus.standard.searchControl
import dev.placeholder.framework.menus.standard.paged
import dev.placeholder.framework.menus.standard.scrolling
import dev.placeholder.framework.menus.storage.MenuDragMode
import dev.placeholder.framework.menus.storage.MenuStorageId
import dev.placeholder.framework.menus.storage.MenuStorageRules
import dev.placeholder.framework.menus.storage.MenuStorageSnapshot
import dev.placeholder.framework.menus.storage.MenuTransactionDecision
import dev.placeholder.framework.menus.storage.MenuTransactionDomain
import dev.placeholder.framework.menus.storage.MenuTransactionId
import dev.placeholder.framework.menus.storage.MenuTransactionProposal
import dev.placeholder.framework.menus.storage.MenuTransactionResolution
import dev.placeholder.framework.menus.storage.PlayerInventorySection
import dev.placeholder.framework.menus.storage.externalMenuStorage
import dev.placeholder.framework.menus.storage.localMenuStorage
import dev.placeholder.framework.menus.storage.reference
import dev.placeholder.framework.menus.storage.transferRoute
import dev.placeholder.framework.menus.storage
import dev.placeholder.framework.menus.transfers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow
import net.kyori.adventure.text.Component
import org.bukkit.Material

class MenuUxHarnessTest {
    private val icon = item(Material.COMPASS)

    @Test
    fun `focused chat hides presentation and restores latest state and effects`() = menuTest {
        InputTestHarness().use { input ->
            val player = input.player("Alex")
            var result: String? = null
            var starts = 0
            var disposals = 0
            val retainedCursor = ItemSnapshot.detached(Material.EMERALD, 3, 64)
            val menu = open(player, cursor = retainedCursor) {
                component("search") {
                    effect("visible") {
                        starts++
                        onDispose { disposals++ }
                    }
                    chest("Search", rows = 1) {
                        searchControl(
                            slot = 0,
                            item = icon,
                            input = input.playerInput,
                            player = player,
                            prompt = Component.text("Search?"),
                            blankFeedback = Component.text("Enter a value."),
                        ) { query -> result = query }
                    }
                }
            }

            menu.primaryClick(0)
            assertFalse(menu.isPresented)
            assertEquals(1, disposals)
            assertEquals(retainedCursor, menu.cursor)
            assertEquals(1, menu.presentationSuspensions())
            assertEquals(0, menu.nativeCloseCalls())

            input.answer(player, "  market square  ")
            runCurrent()

            assertTrue(menu.isPresented)
            assertEquals("market square", result)
            assertEquals(2, starts)
            assertTrue(menu.inspect().trace.any { it is dev.placeholder.framework.menus.MenuTrace.PresentationRestored })
            menu.close()
        }
    }

    @Test
    fun `interceptors reject before actions while observers receive semantic lifecycle`() = menuTest {
        val observed = mutableListOf<MenuTrace>()
        observe { observation -> observed += observation.event }
        var actions = 0
        val rejection = intercept { MenuInterception.Reject() }
        val menu = open {
            chest("Observed", rows = 1) {
                slot(0) {
                    item = icon
                    onPrimary { actions++ }
                }
            }
        }

        assertIs<MenuDispatch.Intercepted>(menu.primaryClick(0))
        assertEquals(0, actions)
        assertTrue(observed.any { it is MenuTrace.GestureReceived })
        assertTrue(observed.any { it is MenuTrace.GestureIntercepted })

        rejection.close()
        assertIs<MenuDispatch.Accepted>(menu.primaryClick(0))
        assertEquals(1, actions)
        assertTrue(observed.any { it is MenuTrace.ActionStarted })
        assertTrue(observed.any { it is MenuTrace.ActionCompleted })
        assertTrue(observed.any { it is MenuTrace.RenderCommitted })
    }

    @Test
    fun `external storage remains observed while presentation is suspended`() = menuTest {
        InputTestHarness().use { input ->
            val player = input.player("StorageObserver")
            val id = MenuStorageId("test", "external")
            val snapshots = MutableStateFlow(MenuStorageSnapshot(id, 0, listOf(null)))
            val domain = object : MenuTransactionDomain {
                override val id: String = "test-domain"
                override val storages: Set<MenuStorageId> = setOf(id)
                override suspend fun commit(proposal: MenuTransactionProposal): MenuTransactionDecision =
                    MenuTransactionDecision.Commit(proposal.changes.mapValues { (_, change) -> change.after })
                override suspend fun resolve(id: MenuTransactionId): MenuTransactionResolution =
                    MenuTransactionResolution.NotCommitted
            }
            val external = externalMenuStorage(
                id,
                snapshots,
                MenuStorageRules.uniform(1),
                domain,
            )
            val menu = open(player) {
                chest("Observed storage", rows = 1) {
                    storage(external, SlotRegion.of(listOf(0)))
                    searchControl(
                        slot = 8,
                        item = icon,
                        input = input.playerInput,
                        player = player,
                        prompt = Component.text("Search?"),
                        blankFeedback = Component.text("Required"),
                    ) {}
                }
            }

            menu.primaryClick(8)
            assertFalse(menu.isPresented)
            val emeralds = ItemSnapshot.detached(Material.EMERALD, 4, 64)
            snapshots.value = MenuStorageSnapshot(id, 1, listOf(emeralds))
            runCurrent()

            assertEquals(emeralds, menu.chest[0]?.storedItem)

            input.answer(player, "done")
            runCurrent()
            assertTrue(menu.isPresented)
            menu.close()
        }
    }

    @Test
    fun `scrolling retains offset and keyed child identity`() = menuTest {
        val menu = open {
            component("browser") {
                val window = scrolling(listOf("a", "b", "c"), key = { it }, windowSize = 2)
                chest("Scroll", rows = 1) {
                    window.items(SlotRegion.of(listOf(0, 1))) { _, index ->
                        slot(index) { item = icon }
                    }
                    window.next(slot = 8, item = icon)
                }
            }
        }

        assertEquals("/root/browser/scrolling[a]", menu.chest[0]?.owner.toString())
        menu.primaryClick(8)
        assertEquals("1", menu.render.stateCells["/root/browser/scrolling:offset"])
        assertEquals("/root/browser/scrolling[b]", menu.chest[0]?.owner.toString())
    }

    @Test
    fun `pagination and scrolling namespace identical domain keys`() = menuTest {
        val menu = open {
            component("browser") {
                val values = listOf("a", "b", "c")
                val pages = paged(values, key = { it }, pageSize = 2)
                val window = scrolling(values, key = { it }, windowSize = 2)
                chest("Namespaced", rows = 1) {
                    pages.items(SlotRegion.of(listOf(0, 1))) { _, index ->
                        slot(index) { item = icon }
                    }
                    window.items(SlotRegion.of(listOf(3, 4))) { _, index ->
                        slot(index) { item = icon }
                    }
                }
            }
        }

        assertEquals("/root/browser/pagination[a]", menu.chest[0]?.owner.toString())
        assertEquals("/root/browser/scrolling[a]", menu.chest[3]?.owner.toString())
    }

    @Test
    fun `storage pickup and placement preserve exact quantities`() = menuTest {
        val id = MenuStorageId("test", "vault")
        val diamonds = ItemSnapshot.detached(Material.DIAMOND, 5, 64)
        val vault = localMenuStorage(id, listOf(diamonds, null))
        val menu = open {
            chest("Vault", rows = 1) {
                storage(vault, SlotRegion.of(listOf(0, 1)))
            }
        }

        assertIs<MenuDispatch.Accepted>(menu.primaryClick(0))
        assertEquals(diamonds, menu.cursor)
        menu.assertStorageItem(id, 0, null)
        menu.assertNoItemCreationOrLoss()

        assertIs<MenuDispatch.Accepted>(menu.primaryClick(1))
        assertNull(menu.cursor)
        menu.assertStorageItem(id, 1, diamonds)
        menu.assertNoItemCreationOrLoss()
    }

    @Test
    fun `ordered drag and player transfer use the production transaction engine`() = menuTest {
        val id = MenuStorageId("test", "drag")
        val vault = localMenuStorage(id, listOf(null, null, null))
        val cursor = ItemSnapshot.detached(Material.DIAMOND, 5, 64)
        val menu = open(
            playerInventory = mapOf(PlayerInventorySection.HOTBAR to List(9) { null }),
            cursor = cursor,
        ) {
            playerInventory(PlayerInventorySection.HOTBAR)
            chest("Drag", rows = 1) {
                storage(vault, SlotRegion.of(listOf(0, 1, 2)))
            }
        }

        assertIs<MenuDispatch.Accepted>(
            menu.drag(hostSlots = listOf(0, 1, 2), mode = MenuDragMode.EVEN),
        )
        assertEquals(2, vault.snapshots.value[0]?.amount)
        assertEquals(2, vault.snapshots.value[1]?.amount)
        assertEquals(1, vault.snapshots.value[2]?.amount)
        assertNull(menu.cursor)
        menu.assertNoItemCreationOrLoss()
    }

    @Test
    fun `shift transfer routes from player inventory into menu storage`() = menuTest {
        val id = MenuStorageId("test", "transfer")
        val vault = localMenuStorage(id, listOf(null, null))
        val diamonds = ItemSnapshot.detached(Material.DIAMOND, 5, 64)
        val hotbar = listOf(diamonds) + List<ItemSnapshot?>(8) { null }
        val menu = open(
            playerInventory = mapOf(PlayerInventorySection.HOTBAR to hotbar),
        ) {
            playerInventory(PlayerInventorySection.HOTBAR)
            transfers(transferRoute(PlayerInventorySection.HOTBAR.reference(), vault.reference()))
            chest("Transfer", rows = 1) {
                storage(vault, SlotRegion.of(listOf(0, 1)))
            }
        }

        assertIs<MenuDispatch.Accepted>(menu.shiftClick(PlayerInventorySection.HOTBAR, 0))
        assertNull(menu.playerItem(PlayerInventorySection.HOTBAR, 0))
        assertEquals(diamonds, vault.snapshots.value[0])
        menu.assertNoItemCreationOrLoss()
    }
}
