package dev.placeholder.framework.menus.storage

import dev.placeholder.framework.items.ItemSnapshot
import net.kyori.adventure.text.Component
import java.util.concurrent.ConcurrentHashMap

/** Final accepted transaction state ready for native reconciliation and emissions. */
public data class CommittedMenuTransaction(
    public val id: MenuTransactionId,
    public val snapshots: Map<MenuStorageId, MenuStorageSnapshot>,
    public val cursor: ItemSnapshot?,
    public val emissions: List<MenuTransactionEmission>,
    public val requiresAcknowledgement: Boolean,
)

/** Accepted state plus the session-specific identities of player inventory sections. */
public data class MenuNativeTransaction(
    public val committed: CommittedMenuTransaction,
    public val playerStorages: Map<PlayerInventorySection, MenuStorageId>,
)

/** Result of attempting one pessimistic transaction commit. */
public sealed interface MenuTransactionSubmission {
    public data class Committed(public val transaction: CommittedMenuTransaction) : MenuTransactionSubmission
    public data class Rejected(public val message: Component) : MenuTransactionSubmission
    public data class Failed(public val failure: MenuTransactionFailure) : MenuTransactionSubmission
}

/** Plug-in-scoped lock and commit coordinator shared by every menu session. */
public class MenuTransactionCoordinator(
    private val journal: MenuTransactionJournal? = null,
) {
    private val locks: MutableSet<LockKey> = ConcurrentHashMap.newKeySet()

    /** Commits [proposal] without ever queuing a conflicting stale gesture. */
    public suspend fun submit(
        proposal: MenuTransactionProposal,
        storages: Map<MenuStorageId, MenuStorage>,
        session: Any,
    ): MenuTransactionSubmission {
        val lockKeys = proposal.resources.map { resource -> resource.lockKey(session) }.toSet()
        val acquired = mutableListOf<LockKey>()
        for (key in lockKeys.sortedBy(LockKey::sortKey)) {
            if (!locks.add(key)) {
                acquired.forEach(locks::remove)
                return MenuTransactionSubmission.Failed(
                    MenuTransactionFailure.ResourceBusy(key.publicResource()),
                )
            }
            acquired += key
        }

        return try {
            commit(proposal, storages)
        } finally {
            acquired.forEach(locks::remove)
        }
    }

    private suspend fun commit(
        proposal: MenuTransactionProposal,
        storages: Map<MenuStorageId, MenuStorage>,
    ): MenuTransactionSubmission {
        val participants = linkedMapOf<MenuStorageId, MenuStorage>()
        for ((id, change) in proposal.changes) {
            val storage = storages[id] ?: return MenuTransactionSubmission.Failed(MenuTransactionFailure.StaleState)
            if (storage.snapshots.value.revision != change.before.revision) {
                return MenuTransactionSubmission.Failed(MenuTransactionFailure.StaleState)
            }
            participants[id] = storage
        }

        val persistent = participants.values.filter { it.transactionDomain != null }
        val domains = persistent.mapNotNull(MenuStorage::transactionDomain).distinctBy(MenuTransactionDomain::id)
        if (domains.size > 1) {
            return MenuTransactionSubmission.Failed(MenuTransactionFailure.MissingTransactionDomain)
        }
        val domain = domains.singleOrNull()
        if (domain != null && persistent.any { it.id !in domain.storages }) {
            return MenuTransactionSubmission.Failed(MenuTransactionFailure.MissingTransactionDomain)
        }

        if (domain != null) journal?.record(JournaledMenuTransaction(domain.id, proposal))
        val authoritative = when (val decision = domain?.commit(proposal)) {
            null -> proposal.changes.mapValues { it.value.after }
            is MenuTransactionDecision.Commit -> {
                val missing = persistent.map(MenuStorage::id).filterNot(decision.snapshots::containsKey)
                if (missing.isNotEmpty()) return MenuTransactionSubmission.Failed(MenuTransactionFailure.StaleState)
                proposal.changes.mapValues { (id, change) -> decision.snapshots[id] ?: change.after }
            }
            is MenuTransactionDecision.Reject -> {
                journal?.complete(proposal.id)
                return MenuTransactionSubmission.Rejected(decision.message)
            }
        }

        for ((id, snapshot) in authoritative) {
            val change = proposal.changes.getValue(id)
            if (snapshot.id != id || snapshot.size != change.before.size || snapshot.revision <= change.before.revision) {
                return MenuTransactionSubmission.Failed(MenuTransactionFailure.StaleState)
            }
        }
        for ((id, storage) in participants) {
            if (storage is MutableMenuStorage) storage.install(authoritative.getValue(id))
        }
        return MenuTransactionSubmission.Committed(
            CommittedMenuTransaction(
                id = proposal.id,
                snapshots = authoritative,
                cursor = proposal.cursorAfter,
                emissions = proposal.emissions,
                requiresAcknowledgement = domain != null && journal != null,
            ),
        )
    }

    /** Clears a durable record after native state and recovery delivery are safely installed. */
    public suspend fun acknowledge(id: MenuTransactionId) {
        journal?.complete(id)
    }

    /** Resolves every journal entry without discarding committed entries before settlement. */
    public suspend fun recover(
        domains: Map<String, MenuTransactionDomain>,
    ): List<MenuTransactionRecovery> {
        val durableJournal = journal ?: return emptyList()
        return buildList {
            for (entry in durableJournal.pending()) {
                val domain = domains[entry.domainId]
                if (domain == null) {
                    add(MenuTransactionRecovery.MissingDomain(entry))
                    continue
                }
                when (val resolution = domain.resolve(entry.proposal.id)) {
                    MenuTransactionResolution.Pending -> add(MenuTransactionRecovery.Pending(entry))
                    MenuTransactionResolution.NotCommitted -> {
                        durableJournal.complete(entry.proposal.id)
                        add(MenuTransactionRecovery.NotCommitted(entry.proposal.id))
                    }
                    is MenuTransactionResolution.Rejected -> {
                        durableJournal.complete(entry.proposal.id)
                        add(MenuTransactionRecovery.Rejected(entry.proposal.id, resolution.message))
                    }
                    is MenuTransactionResolution.Committed -> add(
                        MenuTransactionRecovery.Committed(entry, resolution.snapshots),
                    )
                }
            }
        }
    }
}

/** One restart-resolution result from the durable transaction journal. */
public sealed interface MenuTransactionRecovery {
    public data class MissingDomain(public val entry: JournaledMenuTransaction) : MenuTransactionRecovery
    public data class Pending(public val entry: JournaledMenuTransaction) : MenuTransactionRecovery
    public data class NotCommitted(public val id: MenuTransactionId) : MenuTransactionRecovery
    public data class Rejected(
        public val id: MenuTransactionId,
        public val message: Component?,
    ) : MenuTransactionRecovery
    public data class Committed(
        public val entry: JournaledMenuTransaction,
        public val snapshots: Map<MenuStorageId, MenuStorageSnapshot>,
    ) : MenuTransactionRecovery
}

private sealed interface LockKey {
    val sortKey: String

    data class Storage(val id: MenuStorageId) : LockKey {
        override val sortKey: String = "storage:$id"
    }

    data class Cursor(val session: Any) : LockKey {
        override val sortKey: String = "cursor:${System.identityHashCode(session)}"
    }

    fun publicResource(): MenuTransactionResource = when (this) {
        is Storage -> MenuTransactionResource.Storage(id)
        is Cursor -> MenuTransactionResource.Cursor
    }
}

private fun MenuTransactionResource.lockKey(session: Any): LockKey = when (this) {
    is MenuTransactionResource.Storage -> LockKey.Storage(id)
    MenuTransactionResource.Cursor -> LockKey.Cursor(session)
}
