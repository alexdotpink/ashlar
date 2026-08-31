package dev.placeholder.framework.menus

import java.util.ArrayDeque

/** One bounded semantic lifecycle event retained for diagnostics. */
public sealed interface MenuTrace {
    /** A semantic render reached the native presentation. */
    public data class RenderCommitted(
        public val revision: Long,
        public val reconciliation: MenuReconciliation,
    ) : MenuTrace

    /** A current-revision gesture entered semantic dispatch. */
    public data class GestureReceived(
        public val revision: Long,
        public val kind: MenuGestureKind,
        public val hostSlots: List<Int>,
        public val playerSlots: List<PlayerInventorySlot>,
    ) : MenuTrace

    /** An interceptor rejected a current-revision gesture. */
    public data class GestureIntercepted(public val kind: MenuGestureKind) : MenuTrace

    /** A suspending action began execution. */
    public data class ActionStarted(public val identity: String) : MenuTrace
    /** A suspending action returned without failure. */
    public data class ActionCompleted(public val identity: String) : MenuTrace
    /** An item transaction committed. */
    public data class TransactionCommitted(public val id: String) : MenuTrace
    /** Item movement was rejected before native mutation. */
    public data class TransactionRejected(public val reason: String) : MenuTrace
    /** A watched storage published a new revision. */
    public data class StorageChanged(public val storage: String, public val revision: Long) : MenuTrace
    /** A committed effect began execution. */
    public data class EffectStarted(public val identity: String) : MenuTrace
    /** A committed effect ran its cleanup or was cancelled. */
    public data class EffectDisposed(public val identity: String) : MenuTrace
    /** The retained navigation route stack changed. */
    public data class NavigationChanged(public val routes: List<String>) : MenuTrace
    /** Recovery returned items to a player or their mailbox. */
    public data class RecoveryDelivered(public val itemCount: Int) : MenuTrace
    /** Typed feedback was sent through the active theme. */
    public data class Feedback(
        public val severity: MenuFeedbackSeverity,
        public val targetSlot: Int?,
    ) : MenuTrace
    /** Native presentation was hidden for focused input. */
    public data object PresentationSuspended : MenuTrace
    /** Native presentation was restored after focused input. */
    public data object PresentationRestored : MenuTrace
    /** The logical menu session ended. */
    public data class Closed(public val reason: MenuClose) : MenuTrace
}

/**
 * Redacted, immutable view of one active logical menu session.
 *
 * [snapshot] is the latest committed render. [trace] is oldest first and bounded by the runtime.
 * Pending action, transaction, and effect identifiers are diagnostic strings, not control handles.
 */
public data class MenuInspection(
    public val snapshot: MenuRenderSnapshot,
    public val trace: List<MenuTrace>,
    public val pendingActions: List<String>,
    public val pendingTransactions: List<String>,
    public val activeEffects: List<String>,
)

internal class MenuTraceBuffer(private val capacity: Int = 128) {
    private val entries: ArrayDeque<MenuTrace> = ArrayDeque()

    @Synchronized
    fun add(event: MenuTrace) {
        if (entries.size == capacity) entries.removeFirst()
        entries.addLast(event)
    }

    @Synchronized
    fun snapshot(): List<MenuTrace> = entries.toList()
}
