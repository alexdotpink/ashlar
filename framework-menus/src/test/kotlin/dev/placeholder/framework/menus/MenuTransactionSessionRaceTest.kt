@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.placeholder.framework.menus

import dev.placeholder.framework.execution.PlayerRef
import dev.placeholder.framework.items.ItemSnapshot
import dev.placeholder.framework.menus.storage.JournaledMenuTransaction
import dev.placeholder.framework.menus.storage.MenuDurableTransactionRuntime
import dev.placeholder.framework.menus.storage.MenuNativeCommit
import dev.placeholder.framework.menus.storage.MenuNativeTransaction
import dev.placeholder.framework.menus.storage.MenuPlayerDelivery
import dev.placeholder.framework.menus.storage.MenuPlayerSettlement
import dev.placeholder.framework.menus.storage.MenuSettlementResult
import dev.placeholder.framework.menus.storage.MenuStorageId
import dev.placeholder.framework.menus.storage.MenuStorageRules
import dev.placeholder.framework.menus.storage.MenuStorageSnapshot
import dev.placeholder.framework.menus.storage.MenuTransactionCoordinator
import dev.placeholder.framework.menus.storage.MenuTransactionDecision
import dev.placeholder.framework.menus.storage.MenuTransactionDomain
import dev.placeholder.framework.menus.storage.MenuTransactionId
import dev.placeholder.framework.menus.storage.MenuTransactionJournal
import dev.placeholder.framework.menus.storage.MenuTransactionProposal
import dev.placeholder.framework.menus.storage.MenuTransactionResolution
import dev.placeholder.framework.menus.storage.externalMenuStorage
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import net.kyori.adventure.text.Component
import org.bukkit.Material

internal class MenuTransactionSessionRaceTest {
    @Test
    fun `submitted durable pickup settles exactly once after session closes`() = runTest {
        val before = MenuStorageSnapshot(STORAGE, 0, listOf(diamonds(2)))
        val after = MenuStorageSnapshot(STORAGE, 1, listOf(null))
        val source = MutableStateFlow(before)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var commits = 0
        val domain = object : MenuTransactionDomain {
            override val id: String = "close-race"
            override val storages: Set<MenuStorageId> = setOf(STORAGE)

            override suspend fun commit(proposal: MenuTransactionProposal): MenuTransactionDecision {
                commits++
                entered.complete(Unit)
                release.await()
                source.value = after
                return MenuTransactionDecision.Commit(mapOf(STORAGE to after))
            }

            override suspend fun resolve(id: MenuTransactionId): MenuTransactionResolution =
                MenuTransactionResolution.Pending
        }
        val storage = externalMenuStorage(
            STORAGE,
            source,
            MenuStorageRules.uniform(1),
            domain,
        )
        val journal = RecordingJournal()
        val settled = mutableListOf<MenuPlayerDelivery>()
        val runtime = MenuDurableTransactionRuntime(
            this,
            MenuTransactionCoordinator(journal),
            MenuPlayerSettlement { delivery ->
                settled += delivery
                MenuSettlementResult.Settled
            },
        )
        val host = ClosingTransactionHost()
        val player = PlayerRef(PLAYER)
        val session = MenuSessionCore(
            player = player,
            nativeHost = host,
            parentScope = this,
            transactions = runtime,
            choice = null,
            content = {
                chest("Race", rows = 1) {
                    storage(storage, SlotRegion.of(listOf(0)))
                }
            },
            onClosed = {},
        )
        session.start()
        val render = requireNotNull(host.render)

        val dispatch = requireNotNull(host.callbacks).dispatch(
            MenuInteraction(
                player = player,
                revision = render.revision,
                slot = 0,
                gesture = MenuGesture.Primary,
                clicked = diamonds(2),
                cursor = null,
            ),
        )
        assertIs<MenuDispatch.Accepted>(dispatch)
        runCurrent()
        entered.await()
        assertEquals(1, session.inspection()?.pendingTransactions?.size)

        assertTrue(session.close(MenuClose.Explicit))
        runCurrent()
        assertEquals(1, host.closeCalls)
        assertEquals(0, host.nativeCommits)
        assertEquals(before, storage.snapshots.value)

        release.complete(Unit)
        runCurrent()

        assertEquals(1, commits)
        assertEquals(after, storage.snapshots.value)
        assertEquals(1, host.nativeCommits)
        assertEquals(1, settled.size)
        assertEquals(diamonds(2), settled.single().committed.cursor)
        assertTrue(journal.pending().isEmpty())
        assertTrue(session.inspection()?.pendingTransactions?.isEmpty() == true)
        assertEquals(1, host.closeCalls)
    }

    @Test
    fun `stale render revision never reaches transaction persistence`() = runTest {
        val before = MenuStorageSnapshot(STORAGE, 0, listOf(diamonds(2)))
        var commits = 0
        val domain = object : MenuTransactionDomain {
            override val id: String = "stale-render"
            override val storages: Set<MenuStorageId> = setOf(STORAGE)

            override suspend fun commit(proposal: MenuTransactionProposal): MenuTransactionDecision {
                commits++
                return MenuTransactionDecision.Reject(Component.text("unexpected"))
            }

            override suspend fun resolve(id: MenuTransactionId): MenuTransactionResolution =
                MenuTransactionResolution.NotCommitted
        }
        val storage = externalMenuStorage(
            STORAGE,
            MutableStateFlow(before),
            MenuStorageRules.uniform(1),
            domain,
        )
        val host = ClosingTransactionHost()
        val player = PlayerRef(PLAYER)
        val session = MenuSessionCore(
            player = player,
            nativeHost = host,
            parentScope = this,
            transactions = MenuDurableTransactionRuntime(
                this,
                MenuTransactionCoordinator(),
                MenuPlayerSettlement { MenuSettlementResult.Pending },
            ),
            choice = null,
            content = {
                chest("Stale", rows = 1) {
                    storage(storage, SlotRegion.of(listOf(0)))
                }
            },
            onClosed = {},
        )
        session.start()
        val render = requireNotNull(host.render)

        val dispatch = requireNotNull(host.callbacks).dispatch(
            MenuInteraction(
                player = player,
                revision = render.revision - 1,
                slot = 0,
                gesture = MenuGesture.Primary,
                clicked = diamonds(2),
                cursor = null,
            ),
        )

        assertEquals(MenuDispatch.StaleRevision, dispatch)
        runCurrent()
        assertEquals(0, commits)
        assertEquals(before, storage.snapshots.value)
        assertNull(session.inspection()?.pendingTransactions?.singleOrNull())
        session.close(MenuClose.Explicit)
    }

    private class ClosingTransactionHost : MenuNativeHost {
        var render: MenuRenderSnapshot? = null
        var callbacks: MenuNativeCallbacks? = null
        var closeCalls: Int = 0
        var nativeCommits: Int = 0
        private var closed: Boolean = false

        override suspend fun mount(render: MenuRenderSnapshot, callbacks: MenuNativeCallbacks) {
            this.render = render
            this.callbacks = callbacks
        }

        override suspend fun reconcile(render: MenuRenderSnapshot, change: MenuReconciliation) {
            this.render = render
        }

        override suspend fun close() {
            closeCalls++
            closed = true
        }

        override suspend fun commitTransaction(transaction: MenuNativeTransaction): MenuNativeCommit {
            nativeCommits++
            return if (closed) MenuNativeCommit.Unavailable else MenuNativeCommit.Applied
        }
    }

    private class RecordingJournal : MenuTransactionJournal {
        private val entries = linkedMapOf<MenuTransactionId, JournaledMenuTransaction>()

        override suspend fun record(entry: JournaledMenuTransaction) {
            entries[entry.proposal.id] = entry
        }

        override suspend fun complete(id: MenuTransactionId) {
            entries.remove(id)
        }

        override suspend fun pending(): List<JournaledMenuTransaction> = entries.values.toList()
    }

    private fun diamonds(amount: Int): ItemSnapshot =
        ItemSnapshot.detached(Material.DIAMOND, amount, 64, "diamond")

    private companion object {
        val PLAYER: UUID = UUID.fromString("83162340-e388-4539-a36e-ea05a9d8f91e")
        val STORAGE: MenuStorageId = MenuStorageId("test", "race")
    }
}
