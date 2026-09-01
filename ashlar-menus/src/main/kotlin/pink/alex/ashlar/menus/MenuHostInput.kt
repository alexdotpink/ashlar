package pink.alex.ashlar.menus

import pink.alex.ashlar.execution.PlayerRef
import net.kyori.adventure.key.Key

/** Stable categories for non-slot input emitted by specialized native menu hosts. */
public enum class MenuHostInputKind {
    ANVIL_RENAME_TEXT,
    MERCHANT_TRADE_SELECTED,
    LOOM_PATTERN_SELECTED,
    STONECUTTER_RECIPE_SELECTED,
    ENCHANTMENT_BUTTON,
    BEACON_EFFECTS_SELECTED,
    LECTERN_PAGE_CHANGED,
}

/** One of the three buttons exposed by an enchantment host. */
public enum class EnchantmentButton(public val index: Int) {
    FIRST(0),
    SECOND(1),
    THIRD(2),
    ;

    public companion object {
        /** Resolves a native zero-based button index. */
        public fun fromIndex(index: Int): EnchantmentButton = entries.getOrNull(index)
            ?: throw IllegalArgumentException("An enchantment button index must be between 0 and 2")
    }
}

/** Direction reported by a lectern page change. */
public enum class LecternPageDirection {
    PREVIOUS,
    NEXT,
}

/**
 * Immutable non-slot input projected from a specialized native menu host.
 *
 * Native Paper events remain inside the adapter. A handler receives the stable player, render
 * revision, and only the semantic value declared by its host protocol.
 */
public sealed interface MenuHostInput {
    /** Player who produced this input. */
    public val player: PlayerRef

    /** Committed render revision that displayed the native host. */
    public val revision: Long

    /** Stable category used to select a declared handler. */
    public val kind: MenuHostInputKind

    /**
     * New decoded text entered into an anvil rename field.
     *
     * @property text complete client-provided rename text
     */
    public data class AnvilRenameText(
        override val player: PlayerRef,
        override val revision: Long,
        public val text: String,
    ) : MenuHostInput {
        override val kind: MenuHostInputKind = MenuHostInputKind.ANVIL_RENAME_TEXT
    }

    /**
     * Newly selected zero-based merchant offer.
     *
     * @property index zero-based index in the host's declared offer list
     */
    public data class MerchantTradeSelected(
        override val player: PlayerRef,
        override val revision: Long,
        public val index: Int,
    ) : MenuHostInput {
        init {
            require(index >= 0) { "A merchant trade index cannot be negative" }
        }

        override val kind: MenuHostInputKind = MenuHostInputKind.MERCHANT_TRADE_SELECTED
    }

    /** Newly selected loom [pattern] by stable registry key. */
    public data class LoomPatternSelected(
        override val player: PlayerRef,
        override val revision: Long,
        public val pattern: Key,
    ) : MenuHostInput {
        override val kind: MenuHostInputKind = MenuHostInputKind.LOOM_PATTERN_SELECTED
    }

    /** Newly selected stonecutter [recipe] by stable recipe key. */
    public data class StonecutterRecipeSelected(
        override val player: PlayerRef,
        override val revision: Long,
        public val recipe: Key,
    ) : MenuHostInput {
        override val kind: MenuHostInputKind = MenuHostInputKind.STONECUTTER_RECIPE_SELECTED
    }

    /** Enchantment [button] pressed before vanilla mutates the inventory. */
    public data class EnchantmentButtonPressed(
        override val player: PlayerRef,
        override val revision: Long,
        public val button: EnchantmentButton,
    ) : MenuHostInput {
        override val kind: MenuHostInputKind = MenuHostInputKind.ENCHANTMENT_BUTTON
    }

    /**
     * Primary and secondary beacon effect keys submitted by the client.
     *
     * [consumesPayment] reports whether accepting this input consumes the payment slot.
     */
    public data class BeaconEffectsSelected(
        override val player: PlayerRef,
        override val revision: Long,
        public val primary: Key?,
        public val secondary: Key?,
        public val consumesPayment: Boolean,
    ) : MenuHostInput {
        override val kind: MenuHostInputKind = MenuHostInputKind.BEACON_EFFECTS_SELECTED
    }

    /**
     * One requested lectern page transition from [previousPage] to [page] in [direction].
     * Page values are zero-based.
     */
    public data class LecternPageChanged(
        override val player: PlayerRef,
        override val revision: Long,
        public val previousPage: Int,
        public val page: Int,
        public val direction: LecternPageDirection,
    ) : MenuHostInput {
        init {
            require(previousPage >= 0) { "A previous lectern page cannot be negative" }
            require(page >= 0) { "A lectern page cannot be negative" }
        }

        override val kind: MenuHostInputKind = MenuHostInputKind.LECTERN_PAGE_CHANGED
    }
}

/** Handles anvil rename-field changes for the current anvil host. */
context(host: RoleHostScope<AnvilSlot>)
public fun renameText(
    concurrency: MenuActionConcurrency = MenuActionConcurrency.RESTART_LATEST,
    action: suspend MenuActionScope.(MenuHostInput.AnvilRenameText) -> Unit,
) {
    host.builder.hostInput(host, MenuHostInputKind.ANVIL_RENAME_TEXT, concurrency, action)
}

/** Handles selection of one merchant offer for the current merchant host. */
context(host: RoleHostScope<MerchantSlot>)
public fun onTradeSelected(
    concurrency: MenuActionConcurrency = MenuActionConcurrency.RESTART_LATEST,
    action: suspend MenuActionScope.(MenuHostInput.MerchantTradeSelected) -> Unit,
) {
    host.builder.hostInput(host, MenuHostInputKind.MERCHANT_TRADE_SELECTED, concurrency, action)
}

/** Handles selection of one loom pattern for the current loom host. */
context(host: RoleHostScope<LoomSlot>)
public fun onPatternSelected(
    concurrency: MenuActionConcurrency = MenuActionConcurrency.RESTART_LATEST,
    action: suspend MenuActionScope.(MenuHostInput.LoomPatternSelected) -> Unit,
) {
    host.builder.hostInput(host, MenuHostInputKind.LOOM_PATTERN_SELECTED, concurrency, action)
}

/** Handles selection of one stonecutter recipe for the current stonecutter host. */
context(host: RoleHostScope<StonecutterSlot>)
public fun onRecipeSelected(
    concurrency: MenuActionConcurrency = MenuActionConcurrency.RESTART_LATEST,
    action: suspend MenuActionScope.(MenuHostInput.StonecutterRecipeSelected) -> Unit,
) {
    host.builder.hostInput(host, MenuHostInputKind.STONECUTTER_RECIPE_SELECTED, concurrency, action)
}

/** Handles an enchantment button before vanilla applies the enchantment. */
context(host: RoleHostScope<EnchantmentSlot>)
public fun onEnchantmentButton(
    concurrency: MenuActionConcurrency = MenuActionConcurrency.SINGLE_FLIGHT,
    action: suspend MenuActionScope.(MenuHostInput.EnchantmentButtonPressed) -> Unit,
) {
    host.builder.hostInput(host, MenuHostInputKind.ENCHANTMENT_BUTTON, concurrency, action)
}

/** Handles submitted primary and secondary beacon effects. */
context(host: RoleHostScope<BeaconSlot>)
public fun onBeaconEffectsSelected(
    concurrency: MenuActionConcurrency = MenuActionConcurrency.SINGLE_FLIGHT,
    action: suspend MenuActionScope.(MenuHostInput.BeaconEffectsSelected) -> Unit,
) {
    host.builder.hostInput(host, MenuHostInputKind.BEACON_EFFECTS_SELECTED, concurrency, action)
}

/** Handles a requested lectern page change. */
context(host: RoleHostScope<LecternSlot>)
public fun onPageChanged(
    concurrency: MenuActionConcurrency = MenuActionConcurrency.RESTART_LATEST,
    action: suspend MenuActionScope.(MenuHostInput.LecternPageChanged) -> Unit,
) {
    host.builder.hostInput(host, MenuHostInputKind.LECTERN_PAGE_CHANGED, concurrency, action)
}

internal interface MenuActionJobIdentity {
    val component: ComponentIdentity
}

internal data class MenuHostActionIdentity(
    override val component: ComponentIdentity,
    val kind: MenuHostInputKind,
) : MenuActionJobIdentity

internal data class MenuHostActionDeclaration(
    val identity: MenuHostActionIdentity,
    val concurrency: MenuActionConcurrency,
    val boundary: BoundaryIdentity?,
    val feedbackTheme: MenuFeedbackTheme,
    val handler: suspend MenuActionScope.(MenuHostInput) -> Unit,
)
