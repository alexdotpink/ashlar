package dev.placeholder.framework.menus.storage

import dev.placeholder.framework.items.ItemSnapshot
import java.nio.file.Path
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.junit.jupiter.api.io.TempDir

internal class MenuTransactionEngineTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `primary pickup and placement preserve exact item amount`() {
        val storage = snapshot(SOURCE, listOf(diamonds(7), null))
        val engine = engine(storage)

        val pickup = assertIs<MenuTransactionPlan.Proposed>(
            engine.plan(
                MenuTransactionState(mapOf(SOURCE to storage)),
                MenuStorageGesture.Primary(MenuSlotAddress(SOURCE, 0)),
            ),
        ).proposal
        assertNull(pickup.changes.getValue(SOURCE).after[0])
        assertEquals(7, pickup.cursorAfter?.amount)

        val pickedState = MenuTransactionState(
            mapOf(SOURCE to pickup.changes.getValue(SOURCE).after),
            pickup.cursorAfter,
        )
        val placement = assertIs<MenuTransactionPlan.Proposed>(
            engine.plan(pickedState, MenuStorageGesture.Secondary(MenuSlotAddress(SOURCE, 1))),
        ).proposal
        assertEquals(1, placement.changes.getValue(SOURCE).after[1]?.amount)
        assertEquals(6, placement.cursorAfter?.amount)
        assertEquals(7, amount(placement))
    }

    @Test
    fun `shift transfer merges before filling empty destinations`() {
        val source = snapshot(SOURCE, listOf(diamonds(64)))
        val destination = snapshot(DESTINATION, listOf(diamonds(60), null))
        val engine = MenuTransactionEngine(
            rules = mapOf(
                SOURCE to MenuStorageRules.uniform(1),
                DESTINATION to MenuStorageRules.uniform(2),
            ),
            routes = listOf(MenuTransferRoute(SOURCE, listOf(DESTINATION))),
        )

        val proposal = assertIs<MenuTransactionPlan.Proposed>(
            engine.plan(
                MenuTransactionState(mapOf(SOURCE to source, DESTINATION to destination)),
                MenuStorageGesture.ShiftTransfer(MenuSlotAddress(SOURCE, 0)),
            ),
        ).proposal

        assertNull(proposal.changes.getValue(SOURCE).after[0])
        assertEquals(64, proposal.changes.getValue(DESTINATION).after[0]?.amount)
        assertEquals(60, proposal.changes.getValue(DESTINATION).after[1]?.amount)
        assertEquals(124, amount(proposal))
    }

    @Test
    fun `slot filters reject without producing a partial proposal`() {
        val source = snapshot(SOURCE, listOf(dirt(8)))
        val destination = snapshot(DESTINATION, listOf(null))
        val diamondOnly = MenuSlotRule(accepts = { it.material == Material.DIAMOND })
        val engine = MenuTransactionEngine(
            rules = mapOf(
                SOURCE to MenuStorageRules.uniform(1),
                DESTINATION to MenuStorageRules.uniform(1, diamondOnly),
            ),
            routes = listOf(MenuTransferRoute(SOURCE, listOf(DESTINATION))),
        )

        val rejected = assertIs<MenuTransactionPlan.Rejected>(
            engine.plan(
                MenuTransactionState(mapOf(SOURCE to source, DESTINATION to destination)),
                MenuStorageGesture.ShiftTransfer(MenuSlotAddress(SOURCE, 0)),
            ),
        )
        assertEquals(MenuTransactionFailure.RuleRejected, rejected.failure)
    }

    @Test
    fun `even and single drags use stable target order`() {
        val storage = snapshot(SOURCE, listOf(null, null, null))
        val engine = engine(storage)
        val targets = List(3) { index -> MenuSlotAddress(SOURCE, index) }

        val even = assertIs<MenuTransactionPlan.Proposed>(
            engine.plan(
                MenuTransactionState(mapOf(SOURCE to storage), diamonds(5)),
                MenuStorageGesture.Drag(targets, MenuDragMode.EVEN),
            ),
        ).proposal
        assertEquals(listOf(2, 2, 1), even.changes.getValue(SOURCE).after.slots.map { it?.amount })
        assertNull(even.cursorAfter)

        val single = assertIs<MenuTransactionPlan.Proposed>(
            engine.plan(
                MenuTransactionState(mapOf(SOURCE to storage), diamonds(5)),
                MenuStorageGesture.Drag(targets, MenuDragMode.SINGLE),
            ),
        ).proposal
        assertEquals(listOf(1, 1, 1), single.changes.getValue(SOURCE).after.slots.map { it?.amount })
        assertEquals(2, single.cursorAfter?.amount)
    }

    @Test
    fun `shared storage lock rejects a conflicting proposal without queueing`() = runTest {
        val before = snapshot(SOURCE, listOf(diamonds(2)))
        val after = MenuStorageSnapshot(SOURCE, 1, listOf(diamonds(1)))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val domain = object : MenuTransactionDomain {
            override val id: String = "vault"
            override val storages: Set<MenuStorageId> = setOf(SOURCE)

            override suspend fun commit(proposal: MenuTransactionProposal): MenuTransactionDecision {
                entered.complete(Unit)
                release.await()
                return MenuTransactionDecision.Commit(mapOf(SOURCE to after))
            }

            override suspend fun resolve(id: MenuTransactionId): MenuTransactionResolution =
                MenuTransactionResolution.Pending
        }
        val storage = externalMenuStorage(
            SOURCE,
            MutableStateFlow(before),
            MenuStorageRules.uniform(1),
            domain,
        )
        val proposal = MenuTransactionProposal(
            id = MenuTransactionId.create(),
            changes = mapOf(SOURCE to MenuStorageChange(before, after)),
            cursorBefore = null,
            cursorAfter = diamonds(1),
        )
        val coordinator = MenuTransactionCoordinator()
        val first = async { coordinator.submit(proposal, mapOf(SOURCE to storage), session = "one") }
        entered.await()

        val second = coordinator.submit(
            proposal.copy(id = MenuTransactionId.create()),
            mapOf(SOURCE to storage),
            session = "two",
        )
        assertIs<MenuTransactionFailure.ResourceBusy>(assertIs<MenuTransactionSubmission.Failed>(second).failure)
        release.complete(Unit)
        assertIs<MenuTransactionSubmission.Committed>(first.await())
    }

    @Test
    fun `transaction journal round trips the complete detached proposal`() = runTest {
        val journal = FileMenuTransactionJournal(temporaryDirectory.resolve("journal"))
        val before = snapshot(SOURCE, listOf(diamonds(3)))
        val after = MenuStorageSnapshot(SOURCE, 1, listOf(diamonds(2)))
        val proposal = MenuTransactionProposal(
            id = MenuTransactionId.create(),
            playerId = UUID.randomUUID(),
            changes = mapOf(SOURCE to MenuStorageChange(before, after)),
            cursorBefore = null,
            cursorAfter = diamonds(1),
            emissions = listOf(MenuTransactionEmission.Drop(dirt(1))),
        )

        journal.record(JournaledMenuTransaction("vault", proposal))
        assertEquals(proposal, journal.pending().single().proposal)
        journal.complete(proposal.id)
        assertTrue(journal.pending().isEmpty())
    }

    @Test
    fun `recovery mailbox retains exact items until acknowledgement`() = runTest {
        val mailbox = FileItemRecoveryMailbox(temporaryDirectory.resolve("mailbox"))
        val player = UUID.randomUUID()
        val deposited = mailbox.deposit(player, listOf(diamonds(4), dirt(2)))

        assertEquals(deposited, mailbox.pending(player))
        mailbox.acknowledge(player, setOf(deposited.first().id))
        assertEquals(listOf(deposited.last()), mailbox.pending(player))
        mailbox.acknowledge(player, setOf(deposited.last().id))
        assertTrue(mailbox.pending(player).isEmpty())
    }

    private fun engine(snapshot: MenuStorageSnapshot): MenuTransactionEngine = MenuTransactionEngine(
        mapOf(snapshot.id to MenuStorageRules.uniform(snapshot.size)),
    )

    private fun snapshot(id: MenuStorageId, items: List<ItemSnapshot?>): MenuStorageSnapshot =
        MenuStorageSnapshot(id, 0, items)

    private fun diamonds(amount: Int): ItemSnapshot =
        ItemSnapshot.detached(Material.DIAMOND, amount, 64, "diamond")

    private fun dirt(amount: Int): ItemSnapshot =
        ItemSnapshot.detached(Material.DIRT, amount, 64, "dirt")

    private fun amount(proposal: MenuTransactionProposal): Int =
        proposal.changes.values.sumOf { change -> change.after.slots.sumOf { it?.amount ?: 0 } } +
            (proposal.cursorAfter?.amount ?: 0) +
            proposal.emissions.sumOf { emission ->
                when (emission) {
                    is MenuTransactionEmission.Drop -> emission.item.amount
                }
            }

    private companion object {
        val SOURCE: MenuStorageId = MenuStorageId("test", "source")
        val DESTINATION: MenuStorageId = MenuStorageId("test", "destination")
    }
}
