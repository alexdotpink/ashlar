package pink.alex.ashlar.menus.storage

import pink.alex.ashlar.items.ItemSnapshot
import kotlinx.coroutines.flow.StateFlow

/** Stable identity shared by every viewer of one logical item storage. */
public data class MenuStorageId(
    public val namespace: String,
    public val value: String,
) {
    init {
        require(namespace.matches(SEGMENT)) { "Invalid storage namespace '$namespace'" }
        require(value.isNotBlank()) { "Storage value must not be blank" }
    }

    override fun toString(): String = "$namespace:$value"

    private companion object {
        val SEGMENT: Regex = Regex("[a-z0-9._-]+")
    }
}

/** One immutable, versioned value of a logical storage model. */
public data class MenuStorageSnapshot(
    public val id: MenuStorageId,
    public val revision: Long,
    public val slots: List<ItemSnapshot?>,
) {
    init {
        require(revision >= 0) { "Storage revision cannot be negative" }
    }

    /** Returns the item at [index]. */
    public operator fun get(index: Int): ItemSnapshot? = slots[index]

    /** Number of addressable storage slots. */
    public val size: Int
        get() = slots.size
}

/** Immutable address of one slot in one storage model. */
public data class MenuSlotAddress(
    public val storage: MenuStorageId,
    public val index: Int,
) {
    init {
        require(index >= 0) { "Storage slot index cannot be negative" }
    }
}

/** Symbolic section of the viewing player's inventory resolved by the native adapter. */
public enum class PlayerInventorySection(public val size: Int) {
    HOTBAR(9),
    MAIN(27),
    OFFHAND(1),
    ARMOR(4),
}

/** Insertion, extraction, and capacity policy for one storage slot. */
public class MenuSlotRule(
    public val accepts: (ItemSnapshot) -> Boolean = { true },
    public val canExtract: (ItemSnapshot) -> Boolean = { true },
    public val maximumAmount: (ItemSnapshot) -> Int = ItemSnapshot::maximumAmount,
) {
    internal fun maximum(item: ItemSnapshot): Int = maximumAmount(item).also { maximum ->
        require(maximum > 0) { "A storage slot maximum must be positive, got $maximum" }
    }

    public companion object {
        /** Unrestricted vanilla-compatible storage behavior. */
        public val Vanilla: MenuSlotRule = MenuSlotRule()

        /** A slot which rejects insertion and extraction. */
        public val Locked: MenuSlotRule = MenuSlotRule(
            accepts = { false },
            canExtract = { false },
        )
    }
}

/** Complete slot policy for one storage model. */
public class MenuStorageRules private constructor(
    private val rules: List<MenuSlotRule>,
) {
    /** Returns the rule for [index]. */
    public operator fun get(index: Int): MenuSlotRule = rules[index]

    /** Number of governed slots. */
    public val size: Int
        get() = rules.size

    public companion object {
        /** Applies [rule] to every slot. */
        public fun uniform(size: Int, rule: MenuSlotRule = MenuSlotRule.Vanilla): MenuStorageRules {
            require(size >= 0) { "Storage rule size cannot be negative" }
            return MenuStorageRules(List(size) { rule })
        }

        /** Creates rules in physical slot order. */
        public fun of(rules: List<MenuSlotRule>): MenuStorageRules = MenuStorageRules(rules.toList())
    }
}

/** Read-only storage capability attached to one or more menu sessions. */
public interface MenuStorage {
    /** Stable identity used for sharing and transaction locking. */
    public val id: MenuStorageId

    /** Current authoritative version, updated whenever persistence changes. */
    public val snapshots: StateFlow<MenuStorageSnapshot>

    /** Per-slot movement policy. */
    public val rules: MenuStorageRules

    /** Domain which atomically owns proposals involving this persistent storage. */
    public val transactionDomain: MenuTransactionDomain?
}
