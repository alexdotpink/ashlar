package dev.placeholder.framework.menus.storage

import dev.placeholder.framework.items.ItemSnapshot
import java.util.UUID

/**
 * Cursor and storage values used to compute one pure item-movement proposal.
 * [storages] must be keyed by each snapshot's own identity.
 */
public data class MenuTransactionState(
    public val storages: Map<MenuStorageId, MenuStorageSnapshot>,
    public val cursor: ItemSnapshot? = null,
) {
    init {
        require(storages.all { (id, snapshot) -> id == snapshot.id }) {
            "Transaction-state keys must match storage identities"
        }
    }
}

/** Ordered automatic destinations for shift transfer from one storage. */
public sealed interface MenuStorageReference {
    /** A declared menu storage. */
    public data class Storage(public val id: MenuStorageId) : MenuStorageReference
    /** One symbolic section of the viewing player's inventory. */
    public data class Player(public val section: PlayerInventorySection) : MenuStorageReference
}

/** Creates a transfer reference to this declared storage. */
public fun MenuStorage.reference(): MenuStorageReference = MenuStorageReference.Storage(id)

/** Creates a symbolic transfer reference to this viewer inventory section. */
public fun PlayerInventorySection.reference(): MenuStorageReference = MenuStorageReference.Player(this)

/** Declares one ordered automatic transfer route. */
public fun transferRoute(
    source: MenuStorageReference,
    vararg destinations: MenuStorageReference,
): MenuTransferRoute = MenuTransferRoute(source, destinations.toList())

/**
 * Ordered automatic destinations for shift transfer from one storage participant.
 * The engine fills compatible stacks before empty slots within each destination.
 */
public data class MenuTransferRoute(
    public val source: MenuStorageReference,
    public val destinations: List<MenuStorageReference>,
) {
    init {
        require(destinations.isNotEmpty()) { "A transfer route needs at least one destination" }
        require(source !in destinations) { "A transfer route cannot target its own source" }
        require(destinations.distinct().size == destinations.size) { "A transfer route cannot repeat destinations" }
    }

    public constructor(source: MenuStorageId, destinations: List<MenuStorageId>) : this(
        MenuStorageReference.Storage(source),
        destinations.map { destination -> MenuStorageReference.Storage(destination) },
    )
}

/** Distribution method for a multi-slot cursor drag. */
public enum class MenuDragMode {
    EVEN,
    SINGLE,
}

/** Storage-level gesture after a host maps physical slots to stable addresses. */
public sealed interface MenuStorageGesture {
    /** Address that initiated the gesture, or null for cursor-wide gestures. */
    public val source: MenuSlotAddress?

    /** Pick up, place, merge, or swap a full cursor stack. */
    public data class Primary(override val source: MenuSlotAddress) : MenuStorageGesture
    /** Pick up half a stack or place one cursor item. */
    public data class Secondary(override val source: MenuSlotAddress) : MenuStorageGesture
    /** Move a stack through its declared automatic transfer route. */
    public data class ShiftTransfer(override val source: MenuSlotAddress) : MenuStorageGesture
    /** Swap the source with one hotbar storage address. */
    public data class HotbarSwap(
        override val source: MenuSlotAddress,
        public val hotbar: MenuSlotAddress,
    ) : MenuStorageGesture
    /** Swap the source with the offhand storage address. */
    public data class OffhandSwap(
        override val source: MenuSlotAddress,
        public val offhand: MenuSlotAddress,
    ) : MenuStorageGesture
    /** Remove one item from the source for a world-drop emission. */
    public data class DropOne(override val source: MenuSlotAddress) : MenuStorageGesture
    /** Remove the source stack for a world-drop emission. */
    public data class DropStack(override val source: MenuSlotAddress) : MenuStorageGesture
    /** Remove one item or the full stack from the logical cursor. */
    public data class DropCursor(public val one: Boolean) : MenuStorageGesture {
        override val source: MenuSlotAddress? = null
    }
    /** Fill the logical cursor from compatible slots in [order]. */
    public data class DoubleCollect(
        public val order: List<MenuSlotAddress>,
    ) : MenuStorageGesture {
        override val source: MenuSlotAddress? = null
    }
    /** Distribute the logical cursor across ordered [targets]. */
    public data class Drag(
        public val targets: List<MenuSlotAddress>,
        public val mode: MenuDragMode,
    ) : MenuStorageGesture {
        init {
            require(targets.isNotEmpty()) { "A storage drag needs at least one target" }
            require(targets.distinct().size == targets.size) { "A storage drag cannot repeat targets" }
        }

        override val source: MenuSlotAddress? = null
    }
}

/** Result of pure gesture planning. */
public sealed interface MenuTransactionPlan {
    /** Pure planning produced an immutable transaction proposal. */
    public data class Proposed(public val proposal: MenuTransactionProposal) : MenuTransactionPlan
    /** Pure planning rejected the gesture without changing state. */
    public data class Rejected(public val failure: MenuTransactionFailure) : MenuTransactionPlan
    /** The gesture was valid but did not change any participant. */
    public data object NoChange : MenuTransactionPlan
}

/** Pure vanilla-compatible item movement over immutable storage snapshots. */
public class MenuTransactionEngine(
    rules: Map<MenuStorageId, MenuStorageRules>,
    routes: List<MenuTransferRoute> = emptyList(),
    participants: Map<MenuStorageReference, MenuStorageId> = emptyMap(),
) {
    private val rules: Map<MenuStorageId, MenuStorageRules> = rules.toMap()
    private val participantIds: Map<MenuStorageReference, MenuStorageId> = participants.toMap()
    private val routes: Map<MenuStorageId, ResolvedMenuTransferRoute> = routes.map { route ->
        ResolvedMenuTransferRoute(
            resolve(route.source),
            route.destinations.map(::resolve),
        )
    }.associateBy(ResolvedMenuTransferRoute::source)

    init {
        require(routes.size == this.routes.size) { "Only one transfer route may be declared per source storage" }
    }

    /** Computes a complete proposal without mutating [state]. */
    public fun plan(
        state: MenuTransactionState,
        gesture: MenuStorageGesture,
        id: MenuTransactionId = MenuTransactionId.create(),
        playerId: UUID? = null,
    ): MenuTransactionPlan {
        val mutable = MutableTransactionState(state, rules)
        val failure = when (gesture) {
            is MenuStorageGesture.Primary -> mutable.primary(gesture.source)
            is MenuStorageGesture.Secondary -> mutable.secondary(gesture.source)
            is MenuStorageGesture.ShiftTransfer -> mutable.shift(gesture.source, routes[gesture.source.storage])
            is MenuStorageGesture.HotbarSwap -> mutable.swap(gesture.source, gesture.hotbar)
            is MenuStorageGesture.OffhandSwap -> mutable.swap(gesture.source, gesture.offhand)
            is MenuStorageGesture.DropOne -> mutable.drop(gesture.source, one = true)
            is MenuStorageGesture.DropStack -> mutable.drop(gesture.source, one = false)
            is MenuStorageGesture.DropCursor -> mutable.dropCursor(gesture.one)
            is MenuStorageGesture.DoubleCollect -> mutable.collect(gesture.order)
            is MenuStorageGesture.Drag -> mutable.drag(gesture.targets, gesture.mode)
        }
        if (failure != null) return MenuTransactionPlan.Rejected(failure)
        val playerStorages = participantIds.entries.mapNotNull { (reference, storageId) ->
            (reference as? MenuStorageReference.Player)?.section?.let { it to storageId }
        }.toMap()
        return mutable.proposal(id, playerId, playerStorages) ?: MenuTransactionPlan.NoChange
    }

    private fun resolve(reference: MenuStorageReference): MenuStorageId = when (reference) {
        is MenuStorageReference.Storage -> reference.id
        is MenuStorageReference.Player -> participantIds[reference]
            ?: error("No storage identity was supplied for player section ${reference.section}")
    }
}

private data class ResolvedMenuTransferRoute(
    val source: MenuStorageId,
    val destinations: List<MenuStorageId>,
)

private class MutableTransactionState(
    private val before: MenuTransactionState,
    private val rules: Map<MenuStorageId, MenuStorageRules>,
) {
    private val slots: MutableMap<MenuStorageId, MutableList<ItemSnapshot?>> =
        before.storages.mapValuesTo(linkedMapOf()) { (_, snapshot) -> snapshot.slots.toMutableList() }
    private var cursor: ItemSnapshot? = before.cursor
    private val emissions: MutableList<MenuTransactionEmission> = mutableListOf()

    init {
        for ((id, snapshot) in before.storages) {
            require(rules[id]?.size == snapshot.size) {
                "Storage $id has ${snapshot.size} slots but ${rules[id]?.size ?: 0} rules"
            }
        }
    }

    fun primary(address: MenuSlotAddress): MenuTransactionFailure? {
        if (!exists(address)) return MenuTransactionFailure.StaleState
        val slot = item(address) ?: if (cursor == null) return null else return insert(address, cursor!!.amount)
        val held = cursor
        if (held == null) {
            if (!rule(address).canExtract(slot)) return MenuTransactionFailure.RuleRejected
            set(address, null)
            cursor = slot
            return null
        }
        if (slot.stackableWith(held)) return insert(address, held.amount)
        if (!rule(address).canExtract(slot) || !rule(address).accepts(held)) {
            return MenuTransactionFailure.RuleRejected
        }
        val maximum = rule(address).maximum(held).coerceAtMost(held.maximumAmount)
        if (held.amount > maximum) return MenuTransactionFailure.RuleRejected
        set(address, held)
        cursor = slot
        return null
    }

    fun secondary(address: MenuSlotAddress): MenuTransactionFailure? {
        if (!exists(address)) return MenuTransactionFailure.StaleState
        val slot = item(address)
        val held = cursor
        if (held == null) {
            if (slot == null) return null
            if (!rule(address).canExtract(slot)) return MenuTransactionFailure.RuleRejected
            val taken = (slot.amount + 1) / 2
            cursor = slot.withAmount(taken)
            set(address, slot.withAmountOrNull(slot.amount - taken))
            return null
        }
        if (slot == null || slot.stackableWith(held)) return insert(address, 1)
        return primary(address)
    }

    fun shift(address: MenuSlotAddress, route: ResolvedMenuTransferRoute?): MenuTransactionFailure? {
        if (!exists(address)) return MenuTransactionFailure.StaleState
        val source = item(address) ?: return null
        if (!rule(address).canExtract(source)) return MenuTransactionFailure.RuleRejected
        if (route == null) return MenuTransactionFailure.NoTransferRoute
        val destinations = route.destinations.flatMap { destination ->
            val storage = slots[destination] ?: return MenuTransactionFailure.StaleState
            storage.indices.map { MenuSlotAddress(destination, it) }
        }
        var remainder = source
        for (target in destinations.filter { item(it)?.stackableWith(remainder) == true }) {
            remainder = moveInto(target, remainder) ?: run {
                set(address, null)
                return null
            }
        }
        for (target in destinations.filter { item(it) == null }) {
            remainder = moveInto(target, remainder) ?: run {
                set(address, null)
                return null
            }
        }
        if (remainder.amount == source.amount) return MenuTransactionFailure.RuleRejected
        set(address, remainder)
        return null
    }

    fun swap(left: MenuSlotAddress, right: MenuSlotAddress): MenuTransactionFailure? {
        if (!exists(left) || !exists(right)) return MenuTransactionFailure.StaleState
        val leftItem = item(left)
        val rightItem = item(right)
        if (leftItem == null && rightItem == null) return null
        if (leftItem != null && !rule(left).canExtract(leftItem)) return MenuTransactionFailure.RuleRejected
        if (rightItem != null && !rule(right).canExtract(rightItem)) return MenuTransactionFailure.RuleRejected
        if (rightItem != null && !fits(left, rightItem)) return MenuTransactionFailure.RuleRejected
        if (leftItem != null && !fits(right, leftItem)) return MenuTransactionFailure.RuleRejected
        set(left, rightItem)
        set(right, leftItem)
        return null
    }

    fun drop(address: MenuSlotAddress, one: Boolean): MenuTransactionFailure? {
        if (!exists(address)) return MenuTransactionFailure.StaleState
        val existing = item(address) ?: return null
        if (!rule(address).canExtract(existing)) return MenuTransactionFailure.RuleRejected
        val amount = if (one) 1 else existing.amount
        emissions += MenuTransactionEmission.Drop(existing.withAmount(amount))
        set(address, existing.withAmountOrNull(existing.amount - amount))
        return null
    }

    fun dropCursor(one: Boolean): MenuTransactionFailure? {
        val existing = cursor ?: return null
        val amount = if (one) 1 else existing.amount
        emissions += MenuTransactionEmission.Drop(existing.withAmount(amount))
        cursor = existing.withAmountOrNull(existing.amount - amount)
        return null
    }

    fun collect(order: List<MenuSlotAddress>): MenuTransactionFailure? {
        val held = cursor ?: return null
        val maximum = held.maximumAmount
        if (held.amount >= maximum) return null
        var collected = held.amount
        for (address in order.distinct()) {
            if (!exists(address)) return MenuTransactionFailure.StaleState
            val existing = item(address) ?: continue
            if (!existing.stackableWith(held) || !rule(address).canExtract(existing)) continue
            val moved = minOf(existing.amount, maximum - collected)
            if (moved == 0) break
            collected += moved
            set(address, existing.withAmountOrNull(existing.amount - moved))
        }
        cursor = held.withAmount(collected)
        return null
    }

    fun drag(targets: List<MenuSlotAddress>, mode: MenuDragMode): MenuTransactionFailure? {
        val held = cursor ?: return null
        val eligible = targets.filter { address ->
            if (!exists(address)) return MenuTransactionFailure.StaleState
            val existing = item(address)
            canPlace(address, held) && (existing == null || existing.stackableWith(held))
        }
        if (eligible.isEmpty()) return MenuTransactionFailure.RuleRejected
        var remaining = held.amount
        val limitPerTarget = if (mode == MenuDragMode.SINGLE) 1 else Int.MAX_VALUE
        var progress: Boolean
        val added = eligible.associateWith { 0 }.toMutableMap()
        do {
            progress = false
            for (address in eligible) {
                if (remaining == 0) break
                if (added.getValue(address) >= limitPerTarget) continue
                val existing = item(address)
                val maximum = rule(address).maximum(held).coerceAtMost(held.maximumAmount)
                if ((existing?.amount ?: 0) + added.getValue(address) >= maximum) continue
                added[address] = added.getValue(address) + 1
                remaining--
                progress = true
            }
        } while (remaining > 0 && progress && mode == MenuDragMode.EVEN)
        if (remaining == held.amount) return MenuTransactionFailure.RuleRejected
        for ((address, amount) in added) {
            if (amount == 0) continue
            val existing = item(address)
            set(address, held.withAmount((existing?.amount ?: 0) + amount))
        }
        cursor = held.withAmountOrNull(remaining)
        return null
    }

    private fun insert(address: MenuSlotAddress, requested: Int): MenuTransactionFailure? {
        val held = cursor ?: return null
        if (!canPlace(address, held)) return MenuTransactionFailure.RuleRejected
        val existing = item(address)
        if (existing != null && !existing.stackableWith(held)) return MenuTransactionFailure.RuleRejected
        val maximum = rule(address).maximum(held).coerceAtMost(held.maximumAmount)
        val amount = minOf(requested, maximum - (existing?.amount ?: 0), held.amount)
        if (amount <= 0) return MenuTransactionFailure.RuleRejected
        set(address, held.withAmount((existing?.amount ?: 0) + amount))
        cursor = held.withAmountOrNull(held.amount - amount)
        return null
    }

    private fun moveInto(address: MenuSlotAddress, source: ItemSnapshot): ItemSnapshot? {
        if (!canPlace(address, source)) return source
        val existing = item(address)
        if (existing != null && !existing.stackableWith(source)) return source
        val maximum = rule(address).maximum(source).coerceAtMost(source.maximumAmount)
        val moved = minOf(source.amount, maximum - (existing?.amount ?: 0))
        if (moved <= 0) return source
        set(address, source.withAmount((existing?.amount ?: 0) + moved))
        return source.withAmountOrNull(source.amount - moved)
    }

    private fun canPlace(address: MenuSlotAddress, item: ItemSnapshot): Boolean =
        rule(address).accepts(item)

    private fun fits(address: MenuSlotAddress, item: ItemSnapshot): Boolean =
        canPlace(address, item) &&
            item.amount <= rule(address).maximum(item).coerceAtMost(item.maximumAmount)

    private fun exists(address: MenuSlotAddress): Boolean =
        slots[address.storage]?.let { address.index in it.indices } == true

    private fun item(address: MenuSlotAddress): ItemSnapshot? =
        slots.getValue(address.storage)[address.index]

    private fun set(address: MenuSlotAddress, item: ItemSnapshot?) {
        slots.getValue(address.storage)[address.index] = item
    }

    private fun rule(address: MenuSlotAddress): MenuSlotRule = rules.getValue(address.storage)[address.index]

    fun proposal(
        id: MenuTransactionId,
        playerId: UUID?,
        playerStorages: Map<PlayerInventorySection, MenuStorageId>,
    ): MenuTransactionPlan.Proposed? {
        val changes = linkedMapOf<MenuStorageId, MenuStorageChange>()
        for ((storageId, current) in slots) {
            val original = before.storages.getValue(storageId)
            if (current == original.slots) continue
            val after = MenuStorageSnapshot(storageId, original.revision + 1, current.toList())
            changes[storageId] = MenuStorageChange(original, after)
        }
        if (changes.isEmpty() && cursor == before.cursor && emissions.isEmpty()) return null
        return MenuTransactionPlan.Proposed(
            MenuTransactionProposal(
                id = id,
                playerId = playerId,
                changes = changes,
                cursorBefore = before.cursor,
                cursorAfter = cursor,
                emissions = emissions.toList(),
                playerStorages = playerStorages,
            ),
        )
    }
}

private fun ItemSnapshot.withAmountOrNull(amount: Int): ItemSnapshot? =
    if (amount == 0) null else withAmount(amount)
