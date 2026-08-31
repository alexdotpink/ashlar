package dev.placeholder.framework.menus.internal.paper

import dev.placeholder.framework.execution.EntityOutcome
import dev.placeholder.framework.execution.PlayerRef
import dev.placeholder.framework.items.ItemSnapshot
import dev.placeholder.framework.items.Items
import dev.placeholder.framework.menus.storage.FileItemRecoveryMailbox
import dev.placeholder.framework.menus.storage.ItemRecoveryMailbox
import dev.placeholder.framework.menus.storage.MenuPlayerDelivery
import dev.placeholder.framework.menus.storage.MenuPlayerSettlement
import dev.placeholder.framework.menus.storage.MenuSettlementResult
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin

/** Paper-owned durable inventory and recovery-mailbox settlement. */
internal class PaperMenuPlayerSettlement(
    private val plugin: Plugin,
    private val scope: CoroutineScope,
    internal val mailbox: ItemRecoveryMailbox = FileItemRecoveryMailbox(
        plugin.dataFolder.toPath().resolve("framework/menus/recovery"),
    ),
) : MenuPlayerSettlement, Listener, AutoCloseable {
    private val receiptKey = NamespacedKey(plugin, "menu_settlement_receipts")
    private val locks: ConcurrentHashMap<UUID, Mutex> = ConcurrentHashMap()
    private var retryTransactions: (UUID) -> Unit = {}

    init {
        plugin.server.pluginManager.registerEvents(this, plugin)
    }

    fun retryWith(block: (UUID) -> Unit) {
        retryTransactions = block
    }

    override suspend fun settle(delivery: MenuPlayerDelivery): MenuSettlementResult {
        val playerId = delivery.proposal.playerId ?: return MenuSettlementResult.Settled
        val delta = delivery.playerDelta()
        if (delta.removals.isEmpty() && delta.additions.isEmpty()) return MenuSettlementResult.Settled
        return lock(playerId).withLock {
            when (val outcome = PlayerRef(playerId).access(plugin) { player -> settleLive(player, delivery, delta) }) {
                is EntityOutcome.Completed -> outcome.value
                EntityOutcome.Retired -> settleOffline(playerId, delivery, delta)
            }
        }
    }

    override suspend fun acknowledged(delivery: MenuPlayerDelivery) {
        val playerId = delivery.proposal.playerId ?: return
        mailbox.complete(delivery.proposal.id.value, playerId)
        PlayerRef(playerId).access(plugin) { player ->
            player.removeReceipt(delivery.proposal.id.value)
            player.saveData()
        }
    }

    suspend fun settleCursor(deliveryId: UUID, playerId: UUID, item: ItemSnapshot?) {
        if (item == null) return
        lock(playerId).withLock {
            mailbox.deposit(deliveryId, playerId, listOf(item))
            mailbox.complete(deliveryId, playerId)
        }
        deliverPending(PlayerRef(playerId))
    }

    suspend fun deliverPending(player: PlayerRef) {
        lock(player.uniqueId).withLock {
            val pending = mailbox.pending(player.uniqueId)
            for (entry in pending) {
                val outcome = player.access(plugin) { livePlayer ->
                    if (livePlayer.hasReceipt(entry.id)) return@access true
                    if (!livePlayer.inventory.canFit(entry.item)) return@access false
                    check(livePlayer.inventory.addItem(Items.materialize(entry.item)).isEmpty())
                    livePlayer.addReceipt(entry.id)
                    livePlayer.saveData()
                    true
                }
                if (outcome !is EntityOutcome.Completed || !outcome.value) continue
                mailbox.acknowledge(player.uniqueId, setOf(entry.id))
                player.access(plugin) { livePlayer ->
                    livePlayer.removeReceipt(entry.id)
                    livePlayer.saveData()
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onJoin(event: PlayerJoinEvent) {
        val player = PlayerRef(event.player.uniqueId)
        scope.launch {
            deliverPending(player)
            retryTransactions(player.uniqueId)
        }
    }

    override fun close() {
        HandlerList.unregisterAll(this)
    }

    private suspend fun settleOffline(
        playerId: UUID,
        delivery: MenuPlayerDelivery,
        delta: PlayerDelta,
    ): MenuSettlementResult {
        if (delta.removals.isNotEmpty()) return MenuSettlementResult.Pending
        mailbox.deposit(delivery.proposal.id.value, playerId, delta.additions)
        return MenuSettlementResult.Settled
    }

    private fun settleLive(
        player: Player,
        delivery: MenuPlayerDelivery,
        delta: PlayerDelta,
    ): MenuSettlementResult {
        val receipt = delivery.proposal.id.value
        if (player.hasReceipt(receipt)) return MenuSettlementResult.Settled
        val current = (0..40).map { index -> player.inventory.getItem(index).snapshot() }.toMutableList()
        if (!current.removeExact(delta.removals)) return MenuSettlementResult.Pending
        if (!current.insertAll(delta.additions)) {
            return if (delta.removals.isEmpty()) MenuSettlementResult.Pending else MenuSettlementResult.Pending
        }
        current.forEachIndexed { index, item -> player.inventory.setItem(index, item?.let(Items::materialize)) }
        player.addReceipt(receipt)
        player.saveData()
        return MenuSettlementResult.Settled
    }

    private fun lock(playerId: UUID): Mutex = locks.computeIfAbsent(playerId) { Mutex() }

    private fun Player.hasReceipt(id: UUID): Boolean = id.toString() in receipts()

    private fun Player.addReceipt(id: UUID) {
        persistentDataContainer.set(receiptKey, PersistentDataType.STRING, (receipts() + id.toString()).joinToString(","))
    }

    private fun Player.removeReceipt(id: UUID) {
        val remaining = receipts() - id.toString()
        if (remaining.isEmpty()) persistentDataContainer.remove(receiptKey)
        else persistentDataContainer.set(receiptKey, PersistentDataType.STRING, remaining.joinToString(","))
    }

    private fun Player.receipts(): Set<String> = persistentDataContainer
        .get(receiptKey, PersistentDataType.STRING)
        ?.split(',')
        ?.filter(String::isNotBlank)
        ?.toSet()
        .orEmpty()

    internal fun recordApplied(player: Player, id: UUID) {
        player.addReceipt(id)
    }
}

internal data class PlayerDelta(
    val removals: List<ItemSnapshot>,
    val additions: List<ItemSnapshot>,
)

internal fun MenuPlayerDelivery.playerDelta(): PlayerDelta {
    val before = mutableListOf<ItemSnapshot>()
    val after = mutableListOf<ItemSnapshot>()
    for (storageId in proposal.playerStorages.values) {
        val change = proposal.changes[storageId] ?: continue
        change.before.slots.filterNotNullTo(before)
        committed.snapshots[storageId]?.slots?.filterNotNullTo(after)
            ?: change.after.slots.filterNotNullTo(after)
    }
    proposal.cursorBefore?.let(before::add)
    proposal.cursorAfter?.let(after::add)
    return itemDifference(before, after)
}

private fun itemDifference(before: List<ItemSnapshot>, after: List<ItemSnapshot>): PlayerDelta {
    val remainingBefore = before.toMutableList()
    val remainingAfter = after.toMutableList()
    for (beforeItem in before.toList()) {
        val afterIndex = remainingAfter.indexOfFirst { it.stackableWith(beforeItem) }
        if (afterIndex < 0) continue
        val beforeIndex = remainingBefore.indexOfFirst { it === beforeItem }
        val afterItem = remainingAfter[afterIndex]
        val common = minOf(beforeItem.amount, afterItem.amount)
        remainingBefore.removeAt(beforeIndex)
        remainingAfter.removeAt(afterIndex)
        if (beforeItem.amount > common) remainingBefore += beforeItem.withAmount(beforeItem.amount - common)
        if (afterItem.amount > common) remainingAfter += afterItem.withAmount(afterItem.amount - common)
    }
    return PlayerDelta(remainingBefore, remainingAfter)
}

private fun MutableList<ItemSnapshot?>.removeExact(removals: List<ItemSnapshot>): Boolean {
    val required = removals.toMutableList()
    val candidate = toMutableList()
    for (removal in required) {
        var remaining = removal.amount
        for (index in candidate.indices) {
            val existing = candidate[index] ?: continue
            if (!existing.stackableWith(removal)) continue
            val removed = minOf(existing.amount, remaining)
            remaining -= removed
            candidate[index] = if (existing.amount == removed) null else existing.withAmount(existing.amount - removed)
            if (remaining == 0) break
        }
        if (remaining != 0) return false
    }
    clear()
    addAll(candidate)
    return true
}

private fun MutableList<ItemSnapshot?>.insertAll(additions: List<ItemSnapshot>): Boolean {
    for (addition in additions) {
        var remaining = addition.amount
        for (index in 0..35) {
            val existing = this[index] ?: continue
            if (!existing.stackableWith(addition)) continue
            val moved = minOf(remaining, existing.maximumAmount - existing.amount)
            if (moved <= 0) continue
            this[index] = existing.withAmount(existing.amount + moved)
            remaining -= moved
            if (remaining == 0) break
        }
        for (index in 0..35) {
            if (remaining == 0) break
            if (this[index] != null) continue
            val moved = minOf(remaining, addition.maximumAmount)
            this[index] = addition.withAmount(moved)
            remaining -= moved
        }
        if (remaining != 0) return false
    }
    return true
}

private fun org.bukkit.inventory.PlayerInventory.canFit(item: ItemSnapshot): Boolean {
    val candidate = (0..40).map { index -> getItem(index).snapshot() }.toMutableList()
    return candidate.insertAll(listOf(item))
}

private fun ItemStack?.snapshot(): ItemSnapshot? =
    this?.takeUnless(ItemStack::isEmpty)?.let(Items::capture)
