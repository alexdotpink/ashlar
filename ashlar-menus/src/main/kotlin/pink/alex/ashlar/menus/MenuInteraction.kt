package pink.alex.ashlar.menus

import pink.alex.ashlar.execution.PlayerRef
import pink.alex.ashlar.items.ItemSnapshot
import pink.alex.ashlar.menus.storage.MenuDragMode
import pink.alex.ashlar.menus.storage.PlayerInventorySection
import pink.alex.ashlar.menus.storage.MenuTransactionFailure
import net.kyori.adventure.text.Component

/** Stable categories used for action registration and semantic inspection. */
public enum class MenuGestureKind {
    PRIMARY,
    SECONDARY,
    MIDDLE,
    SHIFT_PRIMARY,
    SHIFT_SECONDARY,
    NUMBER_KEY,
    SWAP_OFFHAND,
    DROP_ONE,
    DROP_STACK,
    DOUBLE_CLICK,
    DRAG,
    CREATIVE,
    OUTSIDE,
}

/** Immutable input projected from one native inventory interaction. */
public sealed interface MenuGesture {
    /** The stable category used to select a declared handler. */
    public val kind: MenuGestureKind

    /** Ordinary left-click input. */
    public data object Primary : MenuGesture { override val kind: MenuGestureKind = MenuGestureKind.PRIMARY }
    /** Ordinary right-click input. */
    public data object Secondary : MenuGesture { override val kind: MenuGestureKind = MenuGestureKind.SECONDARY }
    /** Middle-click input. */
    public data object Middle : MenuGesture { override val kind: MenuGestureKind = MenuGestureKind.MIDDLE }
    /** Shift-modified left-click input. */
    public data object ShiftPrimary : MenuGesture { override val kind: MenuGestureKind = MenuGestureKind.SHIFT_PRIMARY }
    /** Shift-modified right-click input. */
    public data object ShiftSecondary : MenuGesture { override val kind: MenuGestureKind = MenuGestureKind.SHIFT_SECONDARY }
    /** Number-key input naming a zero-based hotbar position. */
    public data class NumberKey(public val index: Int) : MenuGesture {
        init { require(index in 0..8) { "A hotbar index must be between 0 and 8" } }
        override val kind: MenuGestureKind = MenuGestureKind.NUMBER_KEY
    }
    /** Swap-to-offhand key input. */
    public data object SwapOffhand : MenuGesture { override val kind: MenuGestureKind = MenuGestureKind.SWAP_OFFHAND }
    /** Drop-one input. */
    public data object DropOne : MenuGesture { override val kind: MenuGestureKind = MenuGestureKind.DROP_ONE }
    /** Drop-stack input. */
    public data object DropStack : MenuGesture { override val kind: MenuGestureKind = MenuGestureKind.DROP_STACK }
    /** Double-click collection input. */
    public data object DoubleClick : MenuGesture { override val kind: MenuGestureKind = MenuGestureKind.DOUBLE_CLICK }
    /** Multi-slot drag input with its decoded distribution mode. */
    public data class Drag(
        public val mode: MenuDragMode,
    ) : MenuGesture {
        override val kind: MenuGestureKind = MenuGestureKind.DRAG
    }
    /** Creative-inventory mutation input. */
    public data object Creative : MenuGesture { override val kind: MenuGestureKind = MenuGestureKind.CREATIVE }
    /** Cursor drop outside the inventory view. */
    public data class Outside(public val button: MenuOutsideButton) : MenuGesture {
        override val kind: MenuGestureKind = MenuGestureKind.OUTSIDE
    }
}

/** Mouse button used to drop from the logical cursor outside a menu view. */
public enum class MenuOutsideButton {
    PRIMARY,
    SECONDARY,
}

/** Stable symbolic coordinate in the viewing player's inventory. */
public data class PlayerInventorySlot(
    public val section: PlayerInventorySection,
    public val index: Int,
) {
    init {
        require(index in 0 until section.size) { "Slot $index is outside player section $section" }
    }
}

/** Detached action input tied to the committed render that displayed it. */
public data class MenuInteraction(
    public val player: PlayerRef,
    public val revision: Long,
    public val slot: Int?,
    public val hostSlots: List<Int> = listOfNotNull(slot),
    public val playerSlot: PlayerInventorySlot? = null,
    public val playerSlots: List<PlayerInventorySlot> = listOfNotNull(playerSlot),
    public val playerInventory: Map<PlayerInventorySection, List<ItemSnapshot?>> = emptyMap(),
    public val gesture: MenuGesture,
    public val clicked: ItemSnapshot? = null,
    public val cursor: ItemSnapshot? = null,
)

/** Concurrency applied independently to one stable action identity. */
public enum class MenuActionConcurrency {
    SINGLE_FLIGHT,
    RESTART_LATEST,
    PARALLEL,
}

/** Expected result of dispatching a detached menu interaction. */
public sealed interface MenuDispatch {
    /** Dispatch accepted the interaction for action or transaction work. */
    public data object Accepted : MenuDispatch
    /** The interaction targeted an older committed render. */
    public data object StaleRevision : MenuDispatch
    /** No action or storage binding owns the targeted slot. */
    public data object EmptySlot : MenuDispatch
    /** The target has no handler for this gesture. */
    public data object UnsupportedGesture : MenuDispatch
    /** The active native host has no handler for this non-slot input. */
    public data object UnsupportedHostInput : MenuDispatch
    /** Single-flight policy rejected a duplicate action. */
    public data object AlreadyRunning : MenuDispatch
    /** A registered interceptor rejected the interaction. */
    public data object Intercepted : MenuDispatch
    /** Storage planning or submission rejected item movement. */
    public data class TransactionRejected(public val failure: MenuTransactionFailure) : MenuDispatch
    /** The logical session was already closed. */
    public data object Closed : MenuDispatch
}

/** Severity for theme-controlled action feedback. */
public enum class MenuFeedbackSeverity {
    INFO,
    SUCCESS,
    WARNING,
    REJECTION,
}

/** Typed feedback emitted by an action without selecting a transport. */
public data class MenuFeedback(
    public val message: Component,
    public val severity: MenuFeedbackSeverity,
    public val targetSlot: Int? = null,
)

/** Capabilities available to a detached suspending menu action. */
public interface MenuActionScope {
    /** Emits feedback through the active menu presentation. */
    public fun feedback(value: MenuFeedback)

    /** Ends the current logical session. */
    public fun close(reason: MenuClose = MenuClose.Explicit)

    /** Completes a typed choice session with [value]. */
    public fun finish(value: Any)

    /** Temporarily hides native presentation while [block] owns focused player input. */
    public suspend fun <T> withFocusedInput(block: suspend () -> T): T
}

internal data class MenuActionIdentity(
    override val component: ComponentIdentity,
    val slot: Int,
    val name: String,
) : MenuActionJobIdentity

internal data class MenuActionDeclaration(
    val identity: MenuActionIdentity,
    val concurrency: MenuActionConcurrency,
    val boundary: BoundaryIdentity?,
    val feedbackTheme: MenuFeedbackTheme,
    val handler: suspend MenuActionScope.(MenuInteraction) -> Unit,
)
