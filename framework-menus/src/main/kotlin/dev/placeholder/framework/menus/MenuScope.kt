package dev.placeholder.framework.menus

import dev.placeholder.framework.items.ItemSpec
import dev.placeholder.framework.menus.storage.MenuStorage
import dev.placeholder.framework.menus.storage.MenuTransferRoute
import dev.placeholder.framework.menus.storage.PlayerInventorySection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import net.kyori.adventure.text.Component

/** Compile-time capability for synchronous declarative menu composition. */
public abstract class MenuScope internal constructor() {
    internal abstract val builder: MenuTreeBuilder
    internal abstract val identity: ComponentIdentity
    internal abstract val locals: Map<MenuLocal<*>, Any?>
    internal abstract val boundary: BoundaryIdentity?

    /** Creates keyed state bound later by its delegated property name. */
    public fun <T> state(initial: () -> T): MenuState<T> =
        MenuState(builder.stateBinding(identity, initial))

    /** Collects [flow] only while this keyed component remains rendered. */
    public fun <T> collectAsState(
        flow: Flow<T>,
        initial: T,
    ): CollectedMenuState<T> = CollectedMenuState(
        CollectedStateBinding(identity, boundary, initial, flow, builder),
    )

    /** Reads a typed presentation local. */
    public fun <T> local(local: MenuLocal<T>): T {
        @Suppress("UNCHECKED_CAST")
        return if (locals.containsKey(local)) locals.getValue(local) as T else local.default()
    }

    /** Provides one local value to a nested immutable render context. */
    public fun <T> provide(
        local: MenuLocal<T>,
        value: T,
        content: context(MenuScope) () -> Unit,
    ) {
        builder.provide(this, local, value, content)
    }

    /** Declares a synchronous post-commit effect. */
    public fun effect(
        key: Any,
        block: MenuEffectScope.() -> Unit,
    ) {
        builder.effect(this, key, block)
    }

    /** Declares a suspending post-commit effect. */
    public fun launchedEffect(
        key: Any,
        block: suspend CoroutineScope.() -> Unit,
    ) {
        builder.launchedEffect(this, key, block)
    }
}

/** Menu capability inside a concrete chest declaration. */
public abstract class ChestScope internal constructor() : MenuScope() {
    /** The declared chest row count. */
    public abstract val rows: Int

    /** Declares one physical action slot. */
    public abstract fun slot(
        index: Int,
        modifiers: List<SlotModifier> = emptyList(),
        content: ActionSlotScope.() -> Unit,
    )
}

/** Mutable construction facade for one immutable action-slot declaration. */
public abstract class ActionSlotScope internal constructor() {
    /** The virtual item displayed in this action slot. */
    public abstract var item: ItemSpec?

    /** Registers a primary-click action. */
    public abstract fun onPrimary(
        concurrency: MenuActionConcurrency = MenuActionConcurrency.SINGLE_FLIGHT,
        action: suspend MenuActionScope.(MenuInteraction) -> Unit,
    )

    /** Registers a secondary-click action. */
    public abstract fun onSecondary(
        concurrency: MenuActionConcurrency = MenuActionConcurrency.SINGLE_FLIGHT,
        action: suspend MenuActionScope.(MenuInteraction) -> Unit,
    )

    /** Registers one specific gesture action. */
    public abstract fun on(
        kind: MenuGestureKind,
        concurrency: MenuActionConcurrency = MenuActionConcurrency.SINGLE_FLIGHT,
        action: suspend MenuActionScope.(MenuInteraction) -> Unit,
    )

    /** Handles gestures without a more specific registration. */
    public abstract fun onGesture(
        concurrency: MenuActionConcurrency = MenuActionConcurrency.SINGLE_FLIGHT,
        action: suspend MenuActionScope.(MenuInteraction) -> Unit,
    )
}

/** Behavior used when keyed flowing content exceeds its explicit region. */
public enum class RegionOverflow {
    ERROR,
    CLIP,
}

/** Retry capability supplied by a menu error boundary. */
public fun interface MenuRetry {
    /** Clears the captured failure and schedules another render. */
    public fun retry()
}

/** An unexpected descendant failure captured with its component path. */
public data class MenuFailure(
    public val path: MenuComponentPath,
    public val cause: Throwable,
)

/** Declares the one concrete chest host for this render. */
context(menu: MenuScope)
public fun chest(
    title: Component,
    rows: Int,
    content: context(ChestScope) () -> Unit,
) {
    menu.builder.chest(menu, title, rows, content)
}

/** Declares a plain-text chest host. */
context(menu: MenuScope)
public fun chest(
    title: String,
    rows: Int,
    content: context(ChestScope) () -> Unit,
) {
    chest(Component.text(title), rows, content)
}

/** Introduces stable keyed component identity. */
context(menu: MenuScope)
public fun component(
    key: Any,
    content: context(MenuScope) () -> Unit,
) {
    menu.builder.component(menu, key, content)
}

/** Creates keyed delegated state. */
context(menu: MenuScope)
public fun <T> state(initial: T): MenuState<T> = menu.state { initial }

/** Creates keyed delegated state lazily. */
context(menu: MenuScope)
public fun <T> state(initial: () -> T): MenuState<T> = menu.state(initial)

/** Collects external state for the lifetime of the keyed component. */
context(menu: MenuScope)
public fun <T> collectAsState(
    flow: Flow<T>,
    initial: T,
): CollectedMenuState<T> = menu.collectAsState(flow, initial)

/** Declares one chest slot by physical index. */
context(menu: MenuScope, chest: ChestScope)
public fun slot(
    index: Int,
    modifier: List<SlotModifier> = emptyList(),
    content: ActionSlotScope.() -> Unit,
) {
    chest.builder.slot(chest, menu, index, modifier, content)
}

/** Declares one chest slot by row and column. */
context(menu: MenuScope, chest: ChestScope)
public fun slot(
    row: Int,
    column: Int,
    modifier: List<SlotModifier> = emptyList(),
    content: ActionSlotScope.() -> Unit,
) {
    require(column in 0..8) { "Chest columns must be between 0 and 8" }
    chest.builder.slot(chest, menu, row * 9 + column, modifier, content)
}

/** Places keyed repeated children into an explicit ordered region. */
context(chest: ChestScope)
public fun <T, K : Any> flow(
    region: SlotRegion,
    items: Iterable<T>,
    key: (T) -> K,
    overflow: RegionOverflow = RegionOverflow.ERROR,
    content: context(MenuScope) (item: T, slot: Int) -> Unit,
) {
    val values = items.toList()
    val keys = values.map(key)
    if (keys.distinct().size != keys.size) {
        throw MenuValidationException("Flow item keys must be unique at ${chest.identity.semantic()}")
    }
    if (values.size > region.size && overflow == RegionOverflow.ERROR) {
        throw MenuValidationException(
            "Flow at ${chest.identity.semantic()} has ${values.size} items for ${region.size} slots",
        )
    }
    values.zip(region.slots).forEach { (item, index) ->
        component(key(item)) {
            content(item, index)
        }
    }
}

/** Contains descendant render, action, and effect failures. */
context(menu: MenuScope)
public fun errorBoundary(
    key: Any = "error-boundary",
    fallback: context(MenuScope) (MenuFailure, MenuRetry) -> Unit,
    content: context(MenuScope) () -> Unit,
) {
    menu.builder.errorBoundary(menu, key, fallback, content)
}

/** Creates an ordered row region. */
public fun rows(rows: IntRange): SlotRegion = SlotRegion.rows(rows)

/** Creates an ordered single-row region. */
public fun row(row: Int): SlotRegion = SlotRegion.row(row)

/** Creates an ordered rectangular chest region. */
public fun region(rows: IntRange, columns: IntRange): SlotRegion = SlotRegion.rectangle(rows, columns)

/** Binds every slot of [storage] to the ordered physical [region]. */
context(menu: MenuScope, chest: ChestScope)
public fun storage(
    storage: MenuStorage,
    region: SlotRegion,
) {
    chest.builder.storage(chest, menu, storage, region)
}

/** Declares player-inventory sections that may participate in storage gestures. */
context(menu: MenuScope)
public fun playerInventory(vararg sections: PlayerInventorySection) {
    menu.builder.playerInventory(sections.toSet())
}

/** Declares ordered automatic transfer routes for this render. */
context(menu: MenuScope)
public fun transfers(vararg routes: MenuTransferRoute) {
    menu.builder.transfers(routes.toList())
}

internal class DefaultMenuScope(
    override val builder: MenuTreeBuilder,
    override val identity: ComponentIdentity,
    override val locals: Map<MenuLocal<*>, Any?>,
    override val boundary: BoundaryIdentity?,
) : MenuScope()

internal class DefaultChestScope(
    override val builder: MenuTreeBuilder,
    override val identity: ComponentIdentity,
    override val locals: Map<MenuLocal<*>, Any?>,
    override val boundary: BoundaryIdentity?,
    override val rows: Int,
) : ChestScope() {
    override fun slot(index: Int, modifiers: List<SlotModifier>, content: ActionSlotScope.() -> Unit) {
        builder.slot(this, this, index, modifiers, content)
    }
}

internal class DefaultActionSlotScope(
    private val owner: MenuScope,
    private val index: Int,
) : ActionSlotScope() {
    override var item: ItemSpec? = null
    val actions: MutableMap<MenuGestureKind, MenuActionDeclaration> = linkedMapOf()
    var anyGesture: MenuActionDeclaration? = null

    override fun onPrimary(
        concurrency: MenuActionConcurrency,
        action: suspend MenuActionScope.(MenuInteraction) -> Unit,
    ) = on(MenuGestureKind.PRIMARY, concurrency, action)

    override fun onSecondary(
        concurrency: MenuActionConcurrency,
        action: suspend MenuActionScope.(MenuInteraction) -> Unit,
    ) = on(MenuGestureKind.SECONDARY, concurrency, action)

    override fun on(
        kind: MenuGestureKind,
        concurrency: MenuActionConcurrency,
        action: suspend MenuActionScope.(MenuInteraction) -> Unit,
    ) {
        val declaration = MenuActionDeclaration(
            MenuActionIdentity(owner.identity, index, kind.name.lowercase()),
            concurrency,
            owner.boundary,
            action,
        )
        if (actions.putIfAbsent(kind, declaration) != null) {
            throw MenuValidationException("Duplicate $kind action for slot $index at ${owner.identity.semantic()}")
        }
    }

    override fun onGesture(
        concurrency: MenuActionConcurrency,
        action: suspend MenuActionScope.(MenuInteraction) -> Unit,
    ) {
        if (anyGesture != null) {
            throw MenuValidationException("Duplicate general action for slot $index at ${owner.identity.semantic()}")
        }
        anyGesture = MenuActionDeclaration(
            MenuActionIdentity(owner.identity, index, "gesture"),
            concurrency,
            owner.boundary,
            action,
        )
    }
}
