package dev.placeholder.framework.menus.storage

import dev.placeholder.framework.items.ItemSnapshot
import net.kyori.adventure.text.Component
import java.util.UUID

/** Stable identifier used to make an external storage commit idempotent. */
@JvmInline
public value class MenuTransactionId(public val value: UUID) {
    override fun toString(): String = value.toString()

    public companion object {
        /** Creates a fresh transaction identifier. */
        public fun create(): MenuTransactionId = MenuTransactionId(UUID.randomUUID())
    }
}

/** Immutable before and after values for one storage touched by a transaction. */
public data class MenuStorageChange(
    public val before: MenuStorageSnapshot,
    public val after: MenuStorageSnapshot,
) {
    init {
        require(before.id == after.id) { "A storage change cannot alter storage identity" }
        require(after.revision > before.revision) { "An accepted storage change must advance its revision" }
        require(before.size == after.size) { "A storage change cannot resize storage" }
    }
}

/** Complete immutable item-movement proposal. */
public data class MenuTransactionProposal(
    public val id: MenuTransactionId,
    public val playerId: UUID? = null,
    public val changes: Map<MenuStorageId, MenuStorageChange>,
    public val cursorBefore: ItemSnapshot?,
    public val cursorAfter: ItemSnapshot?,
    public val emissions: List<MenuTransactionEmission> = emptyList(),
) {
    init {
        require(changes.isNotEmpty() || cursorBefore != cursorAfter || emissions.isNotEmpty()) {
            "A transaction must change storage, cursor state, or an external item disposition"
        }
        require(changes.all { (id, change) -> id == change.before.id }) {
            "Transaction change keys must match their storage identities"
        }
    }

    /** Stable resources locked while this proposal is awaiting a decision. */
    public val resources: Set<MenuTransactionResource> = buildSet {
        changes.keys.mapTo(this) { MenuTransactionResource.Storage(it) }
        if (cursorBefore != cursorAfter) add(MenuTransactionResource.Cursor)
    }
}

/** Explicit item disposition outside menu storage and cursor state. */
public sealed interface MenuTransactionEmission {
    /** Item stack requested for a world drop under the active recovery policy. */
    public data class Drop(public val item: ItemSnapshot) : MenuTransactionEmission
}

/** Resource identity used for conflict rejection. */
public sealed interface MenuTransactionResource {
    /** One shared storage identity. */
    public data class Storage(public val id: MenuStorageId) : MenuTransactionResource

    /** The current session's logical cursor. */
    public data object Cursor : MenuTransactionResource
}

/** Expected result from the one owner of a transaction proposal. */
public sealed interface MenuTransactionDecision {
    /** Persistence accepted these authoritative storage values. */
    public data class Commit(
        public val snapshots: Map<MenuStorageId, MenuStorageSnapshot>,
    ) : MenuTransactionDecision

    /** Persistence rejected the complete proposal without changing any participant. */
    public data class Reject(public val message: Component) : MenuTransactionDecision
}

/** Atomic commit owner for one or more persistent storage identities. */
public interface MenuTransactionDomain {
    /** Stable domain identity used by diagnostics and durable recovery. */
    public val id: String

    /** Persistent storages whose cross-model proposals this domain may own. */
    public val storages: Set<MenuStorageId>

    /** Commits [proposal] idempotently by its stable identifier. */
    public suspend fun commit(proposal: MenuTransactionProposal): MenuTransactionDecision

    /** Resolves the durable outcome of a previously submitted identifier. */
    public suspend fun resolve(id: MenuTransactionId): MenuTransactionResolution
}

/** Durable outcome lookup after an interrupted or ambiguous external commit. */
public sealed interface MenuTransactionResolution {
    /** No commit was submitted or persistence proved that it did not commit. */
    public data object NotCommitted : MenuTransactionResolution

    /** Persistence is still deciding and the framework should retry resolution later. */
    public data object Pending : MenuTransactionResolution

    /** Persistence accepted these authoritative snapshots. */
    public data class Committed(
        public val snapshots: Map<MenuStorageId, MenuStorageSnapshot>,
    ) : MenuTransactionResolution

    /** Persistence permanently rejected the transaction. */
    public data class Rejected(public val message: Component? = null) : MenuTransactionResolution
}

/** Immediate reason a transaction proposal could not be produced or submitted. */
public sealed interface MenuTransactionFailure {
    /** A source address is absent or no longer matches the viewed revision. */
    public data object StaleState : MenuTransactionFailure

    /** Slot rules reject the requested insertion or extraction. */
    public data object RuleRejected : MenuTransactionFailure

    /** No declared transfer route can accept the item. */
    public data object NoTransferRoute : MenuTransactionFailure

    /** Another proposal currently owns an involved resource. */
    public data class ResourceBusy(public val resource: MenuTransactionResource) : MenuTransactionFailure

    /** Persistent participants do not share one transaction domain. */
    public data object MissingTransactionDomain : MenuTransactionFailure
}
