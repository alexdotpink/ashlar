package dev.placeholder.framework.items

import io.papermc.paper.datacomponent.DataComponentType
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.ItemRarity
import org.bukkit.persistence.PersistentDataType

/** Immutable recipe for an item intentionally authored by a plug-in. */
public class ItemSpec internal constructor(
    /** Vanilla material used to create the stack. */
    public val material: Material,
    /** Authored stack amount. */
    public val amount: Int,
    internal val changes: List<ItemChange>,
) {
    /** Returns an independently built copy with [block] applied. */
    public fun edit(block: ItemSpecBuilder.() -> Unit): ItemSpec =
        ItemSpecBuilder(this).apply(block).build()

    override fun toString(): String = "ItemSpec(material=$material, amount=$amount, changes=${changes.size})"

    override fun equals(other: Any?): Boolean =
        other is ItemSpec && material == other.material && amount == other.amount && changes == other.changes

    override fun hashCode(): Int = 31 * (31 * material.hashCode() + amount) + changes.hashCode()
}

/** Builds one immutable [ItemSpec]. */
public class ItemSpecBuilder internal constructor(base: ItemSpec? = null) {
    /** Vanilla material to materialize. */
    public var material: Material = base?.material ?: Material.STONE
    /** Stack amount, validated again against the effective Paper maximum on materialization. */
    public var amount: Int = base?.amount ?: 1

    private var nameValue: Component? =
        base?.changes?.filterIsInstance<ItemChange.Name>()?.lastOrNull()?.component
    private val changes: LinkedHashMap<Any, ItemChange> = linkedMapOf()

    init {
        base?.changes?.forEach { changes[it.identity] = it }
    }

    /** Sets the item's custom display name. */
    public var name: Component?
        get() = nameValue
        set(value) {
            nameValue = value
            changes[NAME_IDENTITY] = ItemChange.Name(value)
        }

    /** Replaces lore using a small line-oriented builder. */
    public fun lore(block: ItemLoreBuilder.() -> Unit) {
        val lines = ItemLoreBuilder().apply(block).build()
        changes[LORE_IDENTITY] = ItemChange.Lore(lines)
    }

    /** Sets any valued Paper data component without waiting for a framework helper. */
    public fun <T : Any> data(type: DataComponentType.Valued<T>, value: T) {
        changes[type] = ItemChange.SetValued(type, value)
    }

    /** Sets any non-valued Paper data component. */
    public fun data(type: DataComponentType.NonValued) {
        changes[type] = ItemChange.SetNonValued(type)
    }

    /** Removes a component, including a material default. */
    public fun unsetData(type: DataComponentType) {
        changes[type] = ItemChange.Unset(type)
    }

    /** Returns a component to its material default. */
    public fun resetData(type: DataComponentType) {
        changes[type] = ItemChange.Reset(type)
    }

    /** Convenience for the enchantment-glint override component. */
    public fun glint(enabled: Boolean = true) {
        changes[GLINT_IDENTITY] = ItemChange.Glint(enabled)
    }

    /** Convenience for the vanilla rarity component. */
    public fun rarity(value: ItemRarity) {
        changes[RARITY_IDENTITY] = ItemChange.Rarity(value)
    }

    /** Writes a typed value as deterministic bytes in the item's persistent container. */
    public fun <T> persistent(
        key: NamespacedKey,
        value: T,
        codec: PersistentValueCodec<T>,
    ) {
        val encoded = codec.encode(value)
        require(encoded.size <= MAX_PERSISTENT_VALUE_BYTES) {
            "Persistent item value '$key' is ${encoded.size} bytes; maximum is $MAX_PERSISTENT_VALUE_BYTES"
        }
        changes[PersistentIdentity(key)] = ItemChange.Persistent(key, encoded.copyOf())
    }

    /** Removes one persistent value when this spec is materialized. */
    public fun removePersistent(key: NamespacedKey) {
        changes[PersistentIdentity(key)] = ItemChange.RemovePersistent(key)
    }

    /**
     * Applies an advanced Paper mutation each time this spec is materialized.
     * [key] is its structural identity. Change the key when the mutation's output semantics change.
     */
    public fun paper(key: String, mutation: (ItemStack) -> Unit) {
        require(key.isNotBlank()) { "Paper mutation key must not be blank" }
        changes[PaperIdentity(key)] = ItemChange.Paper(PaperIdentity(key), mutation)
    }

    internal fun build(): ItemSpec {
        require(material != Material.AIR && material != Material.CAVE_AIR && material != Material.VOID_AIR) {
            "Material $material is not an item"
        }
        require(amount in 1..MAX_ITEM_AMOUNT) { "Item amount must be from 1 through $MAX_ITEM_AMOUNT, got $amount" }
        return ItemSpec(material, amount, changes.values.toList())
    }

    public companion object {
        /** Maximum encoded size of one persistent value. */
        public const val MAX_PERSISTENT_VALUE_BYTES: Int = 32 * 1024
        /** Highest amount supported by the pinned server line. */
        public const val MAX_ITEM_AMOUNT: Int = 99
    }
}

/** Collects immutable lore lines. */
public class ItemLoreBuilder internal constructor() {
    private val lines: MutableList<Component> = mutableListOf()

    /** Appends [component]. */
    public fun line(component: Component) {
        lines += component
    }

    /** Appends an empty line. */
    public fun emptyLine() {
        lines += Component.empty()
    }

    internal fun build(): List<Component> = lines.toList()
}

/** Creates one immutable authored item. */
public fun item(material: Material, block: ItemSpecBuilder.() -> Unit = {}): ItemSpec =
    ItemSpecBuilder().apply {
        this.material = material
        block()
    }.build()

internal sealed interface ItemChange {
    val identity: Any

    data class Name(val component: Component?) : ItemChange {
        override val identity: Any = NAME_IDENTITY
    }

    data class Lore(val lines: List<Component>) : ItemChange {
        override val identity: Any = LORE_IDENTITY
    }

    data class Glint(val enabled: Boolean) : ItemChange {
        override val identity: Any = GLINT_IDENTITY
    }

    data class Rarity(val rarity: ItemRarity) : ItemChange {
        override val identity: Any = RARITY_IDENTITY
    }

    data class SetValued(val type: DataComponentType.Valued<*>, val value: Any) : ItemChange {
        override val identity: Any get() = type
    }

    data class SetNonValued(val type: DataComponentType.NonValued) : ItemChange {
        override val identity: Any get() = type
    }

    data class Unset(val type: DataComponentType) : ItemChange {
        override val identity: Any get() = type
    }

    data class Reset(val type: DataComponentType) : ItemChange {
        override val identity: Any get() = type
    }

    class Persistent(val key: NamespacedKey, bytes: ByteArray) : ItemChange {
        val bytes: ByteArray = bytes.copyOf()
        override val identity: Any = PersistentIdentity(key)

        override fun equals(other: Any?): Boolean =
            other is Persistent && key == other.key && bytes.contentEquals(other.bytes)

        override fun hashCode(): Int = 31 * key.hashCode() + bytes.contentHashCode()
    }

    data class RemovePersistent(val key: NamespacedKey) : ItemChange {
        override val identity: Any = PersistentIdentity(key)
    }

    class Paper(override val identity: PaperIdentity, val mutation: (ItemStack) -> Unit) : ItemChange {
        override fun equals(other: Any?): Boolean = other is Paper && identity == other.identity
        override fun hashCode(): Int = identity.hashCode()
    }
}

private data class PersistentIdentity(val key: NamespacedKey)
internal data class PaperIdentity(val key: String)

@Suppress("UNCHECKED_CAST")
internal fun ItemChange.applyTo(stack: ItemStack) {
    when (this) {
        is ItemChange.Name -> stack.editMeta { it.displayName(component) }
        is ItemChange.Lore -> stack.editMeta { it.lore(lines) }
        is ItemChange.Glint -> stack.editMeta { it.setEnchantmentGlintOverride(enabled) }
        is ItemChange.Rarity -> stack.editMeta { it.setRarity(rarity) }
        is ItemChange.SetValued -> stack.setData(type as DataComponentType.Valued<Any>, value)
        is ItemChange.SetNonValued -> stack.setData(type)
        is ItemChange.Unset -> stack.unsetData(type)
        is ItemChange.Reset -> stack.resetData(type)
        is ItemChange.Persistent -> stack.editPersistentDataContainer {
            it.set(key, PersistentDataType.BYTE_ARRAY, bytes.copyOf())
        }
        is ItemChange.RemovePersistent -> stack.editPersistentDataContainer { it.remove(key) }
        is ItemChange.Paper -> mutation(stack)
    }
}

private const val NAME_IDENTITY: String = "framework:name"
private const val LORE_IDENTITY: String = "framework:lore"
private const val GLINT_IDENTITY: String = "framework:glint"
private const val RARITY_IDENTITY: String = "framework:rarity"
