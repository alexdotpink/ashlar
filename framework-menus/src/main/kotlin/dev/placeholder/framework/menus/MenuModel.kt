package dev.placeholder.framework.menus

import dev.placeholder.framework.items.ItemSnapshot
import dev.placeholder.framework.items.ItemSpec
import dev.placeholder.framework.menus.storage.MenuSlotAddress
import dev.placeholder.framework.menus.storage.MenuStorage
import dev.placeholder.framework.menus.storage.MenuStorageId
import dev.placeholder.framework.menus.storage.MenuStorageReference
import dev.placeholder.framework.menus.storage.MenuTransferRoute
import dev.placeholder.framework.menus.storage.PlayerInventorySection
import net.kyori.adventure.text.Component

/** A stable, human-readable path through keyed menu components. */
public data class MenuComponentPath(public val segments: List<String>) {
    public constructor(vararg segments: String) : this(segments.toList())

    /** Returns this path followed by [segment]. */
    public operator fun plus(segment: String): MenuComponentPath = MenuComponentPath(segments + segment)

    override fun toString(): String = segments.joinToString(separator = "/", prefix = "/")
}

/** One immutable slot in a committed semantic menu tree. */
public data class MenuSlotSnapshot(
    public val index: Int,
    public val owner: MenuComponentPath,
    public val item: ItemSpec?,
    public val storedItem: ItemSnapshot? = null,
    public val storage: MenuSlotAddress? = null,
    public val actions: Set<MenuGestureKind>,
    public val modifiers: List<SlotModifier> = emptyList(),
    public val locals: Map<String, String> = emptyMap(),
)

/** A committed action-only chest host. */
public data class ChestHostSnapshot(
    public val title: Component,
    public val rows: Int,
    public val slots: List<MenuSlotSnapshot>,
) {
    init {
        require(rows in 1..6) { "Chest rows must be between 1 and 6" }
        require(slots.all { it.index in 0 until rows * 9 }) { "A slot lies outside this chest" }
    }

    /** Finds a declared slot by its physical index. */
    public operator fun get(index: Int): MenuSlotSnapshot? = slots.firstOrNull { it.index == index }
}

/** The concrete host rendered by one menu revision. */
public sealed interface MenuHostSnapshot {
    /** Adventure title shown by the native host. */
    public val title: Component

    /** The number of physical slots exposed by this host. */
    public val capacity: Int

    /** Physical slots in native top-inventory order. */
    public val slots: List<MenuSlotSnapshot>

    /** Chest-host semantics. */
    public data class Chest(public val chest: ChestHostSnapshot) : MenuHostSnapshot {
        override val title: Component get() = chest.title
        override val capacity: Int = chest.rows * 9
        override val slots: List<MenuSlotSnapshot> get() = chest.slots
    }
}

/** A validated immutable render ready for reconciliation or inspection. */
public data class MenuRenderSnapshot(
    public val revision: Long,
    public val host: MenuHostSnapshot,
    public val components: Set<MenuComponentPath>,
    public val stateCells: Map<String, String>,
    public val navigation: List<String>,
    public val storageParticipants: Set<MenuStorageReference> = emptySet(),
    public val storages: Map<MenuStorageId, MenuStorage> = emptyMap(),
    public val transferRoutes: List<MenuTransferRoute> = emptyList(),
)

/** One semantic change between committed renders. */
public sealed interface MenuReconciliation {
    /** A host was mounted or its kind or capacity changed. */
    public data class Remount(
        public val before: MenuHostSnapshot?,
        public val after: MenuHostSnapshot,
    ) : MenuReconciliation

    /** A host stayed mounted and only the listed slots or title changed. */
    public data class Update(
        public val titleChanged: Boolean,
        public val changedSlots: Set<Int>,
    ) : MenuReconciliation
}

/** A bounded rectangular or sparse ordered set of chest slots. */
public class SlotRegion private constructor(public val slots: List<Int>) {
    init {
        require(slots.distinct().size == slots.size) { "A slot region cannot contain duplicates" }
        require(slots.all { it >= 0 }) { "Slot indexes cannot be negative" }
    }

    /** The number of positions in this region. */
    public val size: Int
        get() = slots.size

    public companion object {
        /** Creates a region preserving the supplied slot order. */
        public fun of(slots: Iterable<Int>): SlotRegion = SlotRegion(slots.toList())

        /** Creates a complete range of nine-column chest rows. */
        public fun rows(rows: IntRange): SlotRegion = SlotRegion(rows.flatMap { row ->
            require(row >= 0) { "Row indexes cannot be negative" }
            (row * 9 until row * 9 + 9).toList()
        })

        /** Creates one complete nine-column chest row. */
        public fun row(row: Int): SlotRegion = rows(row..row)

        /** Creates a rectangular region with row-major ordering. */
        public fun rectangle(
            rows: IntRange,
            columns: IntRange,
        ): SlotRegion {
            require(columns.all { it in 0..8 }) { "Chest columns must be between 0 and 8" }
            return SlotRegion(rows.flatMap { row -> columns.map { column -> row * 9 + column } })
        }
    }

    override fun equals(other: Any?): Boolean = other is SlotRegion && slots == other.slots

    override fun hashCode(): Int = slots.hashCode()

    override fun toString(): String = slots.toString()
}

/** Explicit, composable decoration applied by the owner of a slot. */
public data class SlotModifier(
    public val key: String,
    public val value: String,
) {
    /** Combines two modifiers without changing slot ownership. */
    public operator fun plus(other: SlotModifier): List<SlotModifier> = listOf(this, other)
}

/** Reports invalid declarative menu structure before it reaches a native host. */
public class MenuValidationException(public val problem: String) :
    IllegalArgumentException(problem)

internal data class ComponentIdentity(val keys: List<Any>) {
    fun child(key: Any): ComponentIdentity = ComponentIdentity(keys + key)

    fun semantic(): MenuComponentPath = MenuComponentPath(keys.map(::displayKey))
}

internal fun displayKey(key: Any): String = when (key) {
    is String -> key
    is Enum<*> -> key.name
    else -> key.toString()
}

internal data class RenderedSlot(
    val index: Int,
    val owner: ComponentIdentity,
    val item: ItemSpec?,
    val storedItem: ItemSnapshot?,
    val storage: MenuSlotAddress?,
    val actions: Map<MenuGestureKind, MenuActionDeclaration>,
    val anyGesture: MenuActionDeclaration?,
    val modifiers: List<SlotModifier>,
    val locals: Map<MenuLocal<*>, Any?>,
)

internal data class RenderedChest(
    val title: Component,
    val rows: Int,
    val slots: Map<Int, RenderedSlot>,
    val host: RenderedHostDescriptor = RenderedHostDescriptor.chest(title, rows),
)

internal data class RenderTree(
    val host: RenderedChest,
    val components: Set<ComponentIdentity>,
    val effects: Map<EffectIdentity, EffectDeclaration>,
    val storages: Map<MenuStorageId, MenuStorage>,
    val playerInventory: Set<PlayerInventorySection>,
    val transferRoutes: List<MenuTransferRoute>,
)
