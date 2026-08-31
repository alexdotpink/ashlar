package dev.placeholder.framework.menus.storage

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Complete accepted player-bound state retained until native or durable settlement succeeds. */
internal data class MenuPlayerDelivery(
    val proposal: MenuTransactionProposal,
    val committed: CommittedMenuTransaction,
)

/** Result of attempting to durably settle accepted player-bound state. */
internal enum class MenuSettlementResult {
    Settled,
    Pending,
}

/** Internal seam between durable transaction ownership and Paper player inventory recovery. */
internal fun interface MenuPlayerSettlement {
    suspend fun settle(delivery: MenuPlayerDelivery): MenuSettlementResult

    suspend fun acknowledged(delivery: MenuPlayerDelivery) {}
}

/** One submitted transaction and whether its work belongs to the plug-in rather than the menu session. */
internal data class MenuTransactionWork(
    val job: Job,
    val durable: Boolean,
)

/** Plug-in-owned durable commit, restart-resolution, and acknowledgement runtime. */
internal class MenuDurableTransactionRuntime(
    private val scope: CoroutineScope,
    private val coordinator: MenuTransactionCoordinator,
    private val settlement: MenuPlayerSettlement,
) {
    private val domains: ConcurrentHashMap<String, MenuTransactionDomain> = ConcurrentHashMap()

    fun submit(
        sessionScope: CoroutineScope,
        session: Any,
        proposal: MenuTransactionProposal,
        storages: Map<MenuStorageId, MenuStorage>,
        nativeCommit: suspend (MenuNativeTransaction) -> MenuNativeCommit,
        completed: suspend (MenuTransactionSubmission) -> Unit = {},
    ): MenuTransactionWork {
        val durable = coordinator.isDurable(proposal, storages)
        storages.values.mapNotNull(MenuStorage::transactionDomain).forEach(::remember)
        val owner = if (durable) scope else sessionScope
        val job = owner.launch {
            val submission = coordinator.submit(proposal, storages, session)
            if (submission is MenuTransactionSubmission.Committed) {
                val transaction = submission.transaction
                val native = runCatching {
                    nativeCommit(MenuNativeTransaction(transaction, proposal.playerStorages))
                }.getOrDefault(MenuNativeCommit.Unavailable)
                val settled = native == MenuNativeCommit.Applied || !transaction.requiresAcknowledgement ||
                    settlement.settle(MenuPlayerDelivery(proposal, transaction)) == MenuSettlementResult.Settled
                if (transaction.requiresAcknowledgement && settled) {
                    val delivery = MenuPlayerDelivery(proposal, transaction)
                    coordinator.acknowledge(transaction.id)
                    settlement.acknowledged(delivery)
                }
            }
            completed(submission)
        }
        return MenuTransactionWork(job, durable)
    }

    fun register(domain: MenuTransactionDomain): AutoCloseable {
        val existing = domains.putIfAbsent(domain.id, domain)
        require(existing == null || existing === domain) { "Transaction domain '${domain.id}' is already registered" }
        scope.launch { recover(domain) }
        return AutoCloseable { domains.remove(domain.id, domain) }
    }

    fun retry(playerId: java.util.UUID? = null) {
        scope.launch {
            domains.values.forEach { domain -> recover(domain, playerId) }
        }
    }

    private fun remember(domain: MenuTransactionDomain) {
        domains.putIfAbsent(domain.id, domain)
    }

    private suspend fun recover(
        domain: MenuTransactionDomain,
        playerId: java.util.UUID? = null,
    ) {
        for (recovery in coordinator.recover(mapOf(domain.id to domain))) {
            val committed = recovery as? MenuTransactionRecovery.Committed ?: continue
            val proposal = committed.entry.proposal
            if (playerId != null && proposal.playerId != playerId) continue
            val authoritative = proposal.changes.mapValues { (id, change) ->
                committed.snapshots[id] ?: change.after
            }
            val transaction = CommittedMenuTransaction(
                proposal.id,
                authoritative,
                proposal.cursorAfter,
                proposal.emissions,
                requiresAcknowledgement = true,
            )
            if (settlement.settle(MenuPlayerDelivery(proposal, transaction)) == MenuSettlementResult.Settled) {
                coordinator.acknowledge(proposal.id)
                settlement.acknowledged(MenuPlayerDelivery(proposal, transaction))
            }
        }
    }
}
