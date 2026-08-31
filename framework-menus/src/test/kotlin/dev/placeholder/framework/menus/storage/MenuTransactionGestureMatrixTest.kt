package dev.placeholder.framework.menus.storage

import dev.placeholder.framework.items.ItemSnapshot
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import net.kyori.adventure.text.Component
import org.bukkit.Material

internal class MenuTransactionGestureMatrixTest {
    @Test
    fun `hotbar and offhand swaps exchange exact snapshots`() {
        val menu = snapshot(MENU, listOf(diamonds(3), dirt(2)))
        val hotbar = snapshot(HOTBAR, listOf(emeralds(4)))
        val offhand = snapshot(OFFHAND, listOf(gold(5)))
        val engine = engine(menu, hotbar, offhand)
        val initial = MenuTransactionState(
            mapOf(MENU to menu, HOTBAR to hotbar, OFFHAND to offhand),
        )

        val hotbarSwap = proposed(
            engine.plan(
                initial,
                MenuStorageGesture.HotbarSwap(address(MENU, 0), address(HOTBAR, 0)),
            ),
        )
        assertEquals(emeralds(4), hotbarSwap.changes.getValue(MENU).after[0])
        assertEquals(diamonds(3), hotbarSwap.changes.getValue(HOTBAR).after[0])
        assertConserved(initial, hotbarSwap)

        val afterHotbar = apply(initial, hotbarSwap)
        val offhandSwap = proposed(
            engine.plan(
                afterHotbar,
                MenuStorageGesture.OffhandSwap(address(MENU, 1), address(OFFHAND, 0)),
            ),
        )
        assertEquals(gold(5), offhandSwap.changes.getValue(MENU).after[1])
        assertEquals(dirt(2), offhandSwap.changes.getValue(OFFHAND).after[0])
        assertConserved(afterHotbar, offhandSwap)
    }

    @Test
    fun `slot and cursor drops emit the removed exact quantities`() {
        var state = MenuTransactionState(
            mapOf(MENU to snapshot(MENU, listOf(diamonds(3), dirt(2)))),
            cursor = emeralds(4),
        )
        val engine = engine(state.storages.getValue(MENU))

        val dropOne = proposed(engine.plan(state, MenuStorageGesture.DropOne(address(MENU, 0))))
        assertEquals(diamonds(2), dropOne.changes.getValue(MENU).after[0])
        assertEquals(listOf(MenuTransactionEmission.Drop(diamonds(1))), dropOne.emissions)
        assertConserved(state, dropOne)
        state = apply(state, dropOne)

        val dropStack = proposed(engine.plan(state, MenuStorageGesture.DropStack(address(MENU, 1))))
        assertNull(dropStack.changes.getValue(MENU).after[1])
        assertEquals(listOf(MenuTransactionEmission.Drop(dirt(2))), dropStack.emissions)
        assertConserved(state, dropStack)
        state = apply(state, dropStack)

        val dropCursorOne = proposed(engine.plan(state, MenuStorageGesture.DropCursor(one = true)))
        assertEquals(emeralds(3), dropCursorOne.cursorAfter)
        assertEquals(listOf(MenuTransactionEmission.Drop(emeralds(1))), dropCursorOne.emissions)
        assertConserved(state, dropCursorOne)
        state = apply(state, dropCursorOne)

        val dropCursorStack = proposed(engine.plan(state, MenuStorageGesture.DropCursor(one = false)))
        assertNull(dropCursorStack.cursorAfter)
        assertEquals(listOf(MenuTransactionEmission.Drop(emeralds(3))), dropCursorStack.emissions)
        assertConserved(state, dropCursorStack)
    }

    @Test
    fun `double collect follows declared order and stops at the item maximum`() {
        val menu = snapshot(MENU, listOf(diamonds(3), diamonds(4), dirt(8)))
        val hotbar = snapshot(HOTBAR, listOf(diamonds(2), diamonds(1)))
        val state = MenuTransactionState(
            mapOf(MENU to menu, HOTBAR to hotbar),
            cursor = diamonds(60),
        )

        val proposal = proposed(
            engine(menu, hotbar).plan(
                state,
                MenuStorageGesture.DoubleCollect(
                    listOf(
                        address(MENU, 1),
                        address(HOTBAR, 0),
                        address(MENU, 0),
                        address(MENU, 2),
                        address(HOTBAR, 1),
                    ),
                ),
            ),
        )

        assertEquals(diamonds(64), proposal.cursorAfter)
        assertNull(proposal.changes.getValue(MENU).after[1])
        assertEquals(diamonds(2), hotbar[0])
        assertEquals(diamonds(3), proposal.changes.getValue(MENU).after[0])
        assertConserved(state, proposal)
    }

    @Test
    fun `invalid and stale addresses reject without mutating input snapshots`() {
        val menu = snapshot(MENU, listOf(diamonds(3), null))
        val state = MenuTransactionState(mapOf(MENU to menu), cursor = diamonds(1))
        val engine = engine(menu)
        val missing = address(MENU, 99)

        listOf(
            MenuStorageGesture.Primary(missing),
            MenuStorageGesture.HotbarSwap(address(MENU, 0), missing),
            MenuStorageGesture.Drag(listOf(address(MENU, 1), missing), MenuDragMode.EVEN),
            MenuStorageGesture.DoubleCollect(listOf(address(MENU, 0), missing)),
        ).forEach { gesture ->
            val rejection = assertIs<MenuTransactionPlan.Rejected>(engine.plan(state, gesture))
            assertEquals(MenuTransactionFailure.StaleState, rejection.failure)
            assertSame(menu, state.storages.getValue(MENU))
            assertEquals(listOf(diamonds(3), null), menu.slots)
            assertEquals(diamonds(1), state.cursor)
        }
    }

    @Test
    fun `coordinator rejects an obsolete revision before entering persistence`() = runTest {
        val current = MenuStorageSnapshot(MENU, 2, listOf(diamonds(2)))
        val stale = MenuStorageSnapshot(MENU, 1, listOf(diamonds(3)))
        val proposed = MenuStorageSnapshot(MENU, 2, listOf(diamonds(2)))
        var commits = 0
        val domain = object : MenuTransactionDomain {
            override val id: String = "revision-domain"
            override val storages: Set<MenuStorageId> = setOf(MENU)

            override suspend fun commit(proposal: MenuTransactionProposal): MenuTransactionDecision {
                commits++
                return MenuTransactionDecision.Reject(Component.text("must not run"))
            }

            override suspend fun resolve(id: MenuTransactionId): MenuTransactionResolution =
                MenuTransactionResolution.NotCommitted
        }
        val storage = externalMenuStorage(
            MENU,
            MutableStateFlow(current),
            MenuStorageRules.uniform(1),
            domain,
        )
        val proposal = MenuTransactionProposal(
            id = MenuTransactionId.create(),
            changes = mapOf(MENU to MenuStorageChange(stale, proposed)),
            cursorBefore = null,
            cursorAfter = diamonds(1),
        )

        val failed = assertIs<MenuTransactionSubmission.Failed>(
            MenuTransactionCoordinator().submit(proposal, mapOf(MENU to storage), session = Any()),
        )
        assertEquals(MenuTransactionFailure.StaleState, failed.failure)
        assertEquals(0, commits)
        assertEquals(current, storage.snapshots.value)
    }

    @Test
    fun `shared viewers reject conflicts and observe the committed publication`() = runTest {
        val before = snapshot(MENU, listOf(diamonds(2)))
        val after = MenuStorageSnapshot(MENU, 1, listOf(diamonds(1)))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val source = MutableStateFlow(before)
        val domain = object : MenuTransactionDomain {
            override val id: String = "shared-domain"
            override val storages: Set<MenuStorageId> = setOf(MENU)

            override suspend fun commit(proposal: MenuTransactionProposal): MenuTransactionDecision {
                if (calls.incrementAndGet() == 1) {
                    entered.complete(Unit)
                    release.await()
                }
                val committed = proposal.changes.mapValues { (_, change) -> change.after }
                source.value = committed.getValue(MENU)
                return MenuTransactionDecision.Commit(committed)
            }

            override suspend fun resolve(id: MenuTransactionId): MenuTransactionResolution =
                MenuTransactionResolution.Pending
        }
        val sharedStorage = externalMenuStorage(
            MENU,
            source,
            MenuStorageRules.uniform(1),
            domain,
        )
        val firstProposal = MenuTransactionProposal(
            MenuTransactionId.create(),
            changes = mapOf(MENU to MenuStorageChange(before, after)),
            cursorBefore = null,
            cursorAfter = diamonds(1),
        )
        val coordinator = MenuTransactionCoordinator()
        val firstViewer = Any()
        val secondViewer = Any()

        val first = async {
            coordinator.submit(firstProposal, mapOf(MENU to sharedStorage), session = firstViewer)
        }
        entered.await()
        val conflict = coordinator.submit(
            firstProposal.copy(id = MenuTransactionId.create()),
            mapOf(MENU to sharedStorage),
            session = secondViewer,
        )
        val busy = assertIs<MenuTransactionSubmission.Failed>(conflict)
        assertIs<MenuTransactionFailure.ResourceBusy>(busy.failure)

        release.complete(Unit)
        assertIs<MenuTransactionSubmission.Committed>(first.await())
        assertEquals(after, sharedStorage.snapshots.value)
        assertEquals(after, source.value)

        val final = MenuStorageSnapshot(MENU, 2, listOf(null))
        val next = coordinator.submit(
            MenuTransactionProposal(
                MenuTransactionId.create(),
                changes = mapOf(MENU to MenuStorageChange(after, final)),
                cursorBefore = null,
                cursorAfter = diamonds(1),
            ),
            mapOf(MENU to sharedStorage),
            session = secondViewer,
        )
        assertIs<MenuTransactionSubmission.Committed>(next)
        assertEquals(final, sharedStorage.snapshots.value)
        assertEquals(2, calls.get())
    }

    @Test
    fun `randomized gesture sequences conserve every exact item identity`() {
        repeat(32) { seed ->
            val random = Random(seed)
            var state = randomState(random)
            val initial = quantities(state)
            val emitted = mutableListOf<ItemSnapshot>()
            val engine = engine(*state.storages.values.toTypedArray(), routes = randomizedRoutes())
            val addresses = state.storages.flatMap { (id, snapshot) ->
                snapshot.slots.indices.map { index -> address(id, index) }
            }

            repeat(128) {
                val gesture = randomGesture(random, addresses)
                val before = state
                when (val result = engine.plan(state, gesture)) {
                    MenuTransactionPlan.NoChange,
                    is MenuTransactionPlan.Rejected,
                    -> Unit

                    is MenuTransactionPlan.Proposed -> {
                        assertConserved(state, result.proposal)
                        emitted += result.proposal.emissions.map { emission ->
                            assertIs<MenuTransactionEmission.Drop>(emission).item
                        }
                        state = apply(state, result.proposal)
                    }
                }
                assertEquals(before.storages.keys, state.storages.keys)
                assertEquals(initial, quantities(state, emitted), "seed=$seed gesture=$gesture")
            }
        }
    }

    private fun randomState(random: Random): MenuTransactionState {
        fun randomItem(): ItemSnapshot? = when (random.nextInt(5)) {
            0 -> null
            1 -> diamonds(random.nextInt(1, 65))
            2 -> dirt(random.nextInt(1, 65))
            3 -> emeralds(random.nextInt(1, 65))
            else -> gold(random.nextInt(1, 65))
        }

        return MenuTransactionState(
            storages = mapOf(
                MENU to snapshot(MENU, List(6) { randomItem() }),
                HOTBAR to snapshot(HOTBAR, List(3) { randomItem() }),
                OFFHAND to snapshot(OFFHAND, listOf(randomItem())),
            ),
            cursor = randomItem(),
        )
    }

    private fun randomGesture(random: Random, addresses: List<MenuSlotAddress>): MenuStorageGesture {
        val source = addresses.random(random)
        return when (random.nextInt(10)) {
            0 -> MenuStorageGesture.Primary(source)
            1 -> MenuStorageGesture.Secondary(source)
            2 -> MenuStorageGesture.ShiftTransfer(source)
            3 -> MenuStorageGesture.HotbarSwap(source, address(HOTBAR, random.nextInt(3)))
            4 -> MenuStorageGesture.OffhandSwap(source, address(OFFHAND, 0))
            5 -> MenuStorageGesture.DropOne(source)
            6 -> MenuStorageGesture.DropStack(source)
            7 -> MenuStorageGesture.DropCursor(random.nextBoolean())
            8 -> MenuStorageGesture.DoubleCollect(addresses.shuffled(random))
            else -> MenuStorageGesture.Drag(
                addresses.shuffled(random).take(random.nextInt(1, addresses.size + 1)),
                MenuDragMode.entries.random(random),
            )
        }
    }

    private fun randomizedRoutes(): List<MenuTransferRoute> = listOf(
        MenuTransferRoute(MENU, listOf(HOTBAR, OFFHAND)),
        MenuTransferRoute(HOTBAR, listOf(MENU, OFFHAND)),
        MenuTransferRoute(OFFHAND, listOf(MENU, HOTBAR)),
    )

    private fun engine(
        vararg snapshots: MenuStorageSnapshot,
        routes: List<MenuTransferRoute> = emptyList(),
    ): MenuTransactionEngine = MenuTransactionEngine(
        snapshots.associate { snapshot -> snapshot.id to MenuStorageRules.uniform(snapshot.size) },
        routes,
    )

    private fun apply(
        state: MenuTransactionState,
        proposal: MenuTransactionProposal,
    ): MenuTransactionState = MenuTransactionState(
        storages = state.storages + proposal.changes.mapValues { (_, change) -> change.after },
        cursor = proposal.cursorAfter,
    )

    private fun assertConserved(
        state: MenuTransactionState,
        proposal: MenuTransactionProposal,
    ) {
        assertEquals(
            quantities(state),
            quantities(apply(state, proposal), proposal.emissions.map { emission ->
                assertIs<MenuTransactionEmission.Drop>(emission).item
            }),
        )
    }

    private fun quantities(
        state: MenuTransactionState,
        emitted: List<ItemSnapshot> = emptyList(),
    ): Map<String, Int> = quantities(
        state.storages.values.flatMap(MenuStorageSnapshot::slots) + state.cursor + emitted,
    )

    private fun quantities(items: Iterable<ItemSnapshot?>): Map<String, Int> = buildMap {
        items.filterNotNull().forEach { item ->
            val identity = item.withAmount(1).fingerprint()
            put(identity, getOrDefault(identity, 0) + item.amount)
        }
    }

    private fun proposed(plan: MenuTransactionPlan): MenuTransactionProposal =
        assertIs<MenuTransactionPlan.Proposed>(plan).proposal

    private fun snapshot(id: MenuStorageId, items: List<ItemSnapshot?>): MenuStorageSnapshot =
        MenuStorageSnapshot(id, 0, items)

    private fun address(id: MenuStorageId, index: Int): MenuSlotAddress = MenuSlotAddress(id, index)

    private fun diamonds(amount: Int): ItemSnapshot =
        ItemSnapshot.detached(Material.DIAMOND, amount, 64, "diamond")

    private fun dirt(amount: Int): ItemSnapshot =
        ItemSnapshot.detached(Material.DIRT, amount, 64, "dirt")

    private fun emeralds(amount: Int): ItemSnapshot =
        ItemSnapshot.detached(Material.EMERALD, amount, 64, "emerald")

    private fun gold(amount: Int): ItemSnapshot =
        ItemSnapshot.detached(Material.GOLD_INGOT, amount, 64, "gold")

    private companion object {
        val MENU = MenuStorageId("test", "menu")
        val HOTBAR = MenuStorageId("test", "hotbar")
        val OFFHAND = MenuStorageId("test", "offhand")
    }
}
