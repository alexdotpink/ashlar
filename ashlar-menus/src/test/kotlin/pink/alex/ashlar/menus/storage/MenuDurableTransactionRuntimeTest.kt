package pink.alex.ashlar.menus.storage

import pink.alex.ashlar.items.ItemSnapshot
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.bukkit.Material

internal class MenuDurableTransactionRuntimeTest {
    @Test
    fun `submitted durable commit survives session cancellation and acknowledges after settlement`() = runTest {
        val journal = RecordingJournal()
        val coordinator = MenuTransactionCoordinator(journal)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val before = snapshot(0, diamonds(2))
        val after = snapshot(1, diamonds(1))
        val domain = domain(before.id) { proposal ->
            entered.complete(Unit)
            release.await()
            MenuTransactionDecision.Commit(mapOf(before.id to after))
        }
        val storage = externalMenuStorage(
            before.id,
            MutableStateFlow(before),
            MenuStorageRules.uniform(1),
            domain,
        )
        val settled = mutableListOf<MenuPlayerDelivery>()
        val runtime = MenuDurableTransactionRuntime(
            scope = this,
            coordinator = coordinator,
            settlement = MenuPlayerSettlement { delivery ->
                settled += delivery
                MenuSettlementResult.Settled
            },
        )
        val sessionScope = CoroutineScope(coroutineContext + SupervisorJob())
        val proposal = proposal(before, after)

        val work = runtime.submit(
            sessionScope = sessionScope,
            session = "menu-session",
            proposal = proposal,
            storages = mapOf(before.id to storage),
            nativeCommit = { MenuNativeCommit.Unavailable },
        )
        entered.await()
        sessionScope.cancel()
        release.complete(Unit)
        work.job.join()

        assertTrue(work.durable)
        assertEquals(listOf(proposal.id), settled.map { it.proposal.id })
        assertTrue(journal.pending().isEmpty())
    }

    @Test
    fun `registered domain resolves and settles committed journal entry once`() = runTest {
        val journal = RecordingJournal()
        val before = snapshot(0, diamonds(2))
        val after = snapshot(1, diamonds(1))
        val proposal = proposal(before, after)
        journal.record(JournaledMenuTransaction("vault", proposal))
        val resolved = CompletableDeferred<Unit>()
        val domain = object : MenuTransactionDomain {
            override val id: String = "vault"
            override val storages: Set<MenuStorageId> = setOf(before.id)

            override suspend fun commit(proposal: MenuTransactionProposal): MenuTransactionDecision =
                error("restart recovery must resolve, not resubmit")

            override suspend fun resolve(id: MenuTransactionId): MenuTransactionResolution =
                MenuTransactionResolution.Committed(mapOf(before.id to after))
        }
        val runtime = MenuDurableTransactionRuntime(
            scope = this,
            coordinator = MenuTransactionCoordinator(journal),
            settlement = MenuPlayerSettlement {
                resolved.complete(Unit)
                MenuSettlementResult.Settled
            },
        )

        runtime.register(domain)
        resolved.await()
        testScheduler.runCurrent()

        assertTrue(journal.pending().isEmpty())
    }

    @Test
    fun `durable drop is rejected before persistence`() = runTest {
        val before = snapshot(0, diamonds(2))
        val after = snapshot(1, diamonds(1))
        var commits = 0
        val domain = domain(before.id) {
            commits++
            MenuTransactionDecision.Commit(mapOf(before.id to after))
        }
        val storage = externalMenuStorage(
            before.id,
            MutableStateFlow(before),
            MenuStorageRules.uniform(1),
            domain,
        )
        val coordinator = MenuTransactionCoordinator()
        val submission = coordinator.submit(
            proposal(before, after).copy(emissions = listOf(MenuTransactionEmission.Drop(diamonds(1)))),
            mapOf(before.id to storage),
            session = UUID.randomUUID(),
        )

        assertIs<MenuTransactionSubmission.Failed>(submission)
        assertEquals(MenuTransactionFailure.DurableEmissionUnsupported, submission.failure)
        assertEquals(0, commits)
    }

    private fun proposal(
        before: MenuStorageSnapshot,
        after: MenuStorageSnapshot,
    ): MenuTransactionProposal = MenuTransactionProposal(
        id = MenuTransactionId.create(),
        playerId = PLAYER,
        changes = mapOf(before.id to MenuStorageChange(before, after)),
        cursorBefore = diamonds(1),
        cursorAfter = null,
        playerStorages = mapOf(PlayerInventorySection.MAIN to before.id),
    )

    private fun domain(
        storage: MenuStorageId,
        commit: suspend (MenuTransactionProposal) -> MenuTransactionDecision,
    ): MenuTransactionDomain = object : MenuTransactionDomain {
        override val id: String = "vault"
        override val storages: Set<MenuStorageId> = setOf(storage)

        override suspend fun commit(proposal: MenuTransactionProposal): MenuTransactionDecision = commit(proposal)

        override suspend fun resolve(id: MenuTransactionId): MenuTransactionResolution =
            MenuTransactionResolution.Pending
    }

    private fun snapshot(revision: Long, item: ItemSnapshot): MenuStorageSnapshot =
        MenuStorageSnapshot(STORAGE, revision, listOf(item))

    private fun diamonds(amount: Int): ItemSnapshot =
        ItemSnapshot.detached(Material.DIAMOND, amount, 64, "diamond")

    private class RecordingJournal : MenuTransactionJournal {
        private val entries = linkedMapOf<MenuTransactionId, JournaledMenuTransaction>()

        override suspend fun record(entry: JournaledMenuTransaction) {
            entries[entry.proposal.id] = entry
        }

        override suspend fun complete(id: MenuTransactionId) {
            entries.remove(id)
        }

        override suspend fun pending(): List<JournaledMenuTransaction> =
            entries.values.sortedBy { it.proposal.id.toString() }
    }

    private companion object {
        val PLAYER: UUID = UUID.fromString("1ed2dfa7-07df-4d36-8bc0-436b500bc3f2")
        val STORAGE: MenuStorageId = MenuStorageId("test", "vault")
    }
}
