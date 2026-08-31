package dev.placeholder.framework.menus

import java.util.ArrayDeque

/** One bounded semantic lifecycle event retained for diagnostics. */
public sealed interface MenuTrace {
    public data class RenderCommitted(
        public val revision: Long,
        public val reconciliation: MenuReconciliation,
    ) : MenuTrace

    public data class GestureReceived(
        public val revision: Long,
        public val kind: MenuGestureKind,
        public val hostSlots: List<Int>,
        public val playerSlots: List<PlayerInventorySlot>,
    ) : MenuTrace

    public data class GestureIntercepted(public val kind: MenuGestureKind) : MenuTrace

    public data class ActionStarted(public val identity: String) : MenuTrace
    public data class ActionCompleted(public val identity: String) : MenuTrace
    public data class TransactionCommitted(public val id: String) : MenuTrace
    public data class TransactionRejected(public val reason: String) : MenuTrace
    public data class StorageChanged(public val storage: String, public val revision: Long) : MenuTrace
    public data class EffectStarted(public val identity: String) : MenuTrace
    public data class EffectDisposed(public val identity: String) : MenuTrace
    public data class NavigationChanged(public val routes: List<String>) : MenuTrace
    public data class RecoveryDelivered(public val itemCount: Int) : MenuTrace
    public data class Feedback(
        public val severity: MenuFeedbackSeverity,
        public val targetSlot: Int?,
    ) : MenuTrace
    public data object PresentationSuspended : MenuTrace
    public data object PresentationRestored : MenuTrace
    public data class Closed(public val reason: MenuClose) : MenuTrace
}

/** Redacted, immutable view of one active logical menu session. */
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
