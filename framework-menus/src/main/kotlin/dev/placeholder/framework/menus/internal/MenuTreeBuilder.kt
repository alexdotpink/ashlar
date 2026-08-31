package dev.placeholder.framework.menus

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import net.kyori.adventure.text.Component
import dev.placeholder.framework.menus.storage.MenuSlotAddress
import dev.placeholder.framework.menus.storage.MenuStorage
import dev.placeholder.framework.menus.storage.MenuStorageId
import dev.placeholder.framework.menus.storage.MenuTransferRoute
import dev.placeholder.framework.menus.storage.PlayerInventorySection

internal class MenuTreeBuilder(
    private val stateStore: MenuStateStore,
    private val boundaryFailures: MutableMap<BoundaryIdentity, MenuFailure>,
    private val clearBoundary: (BoundaryIdentity) -> Unit,
    private val invalidate: () -> Unit,
    private val navigationStates: MutableMap<ComponentIdentity, NavigationState<Any>>,
    private val closeSession: () -> Unit,
) {
    private val components: MutableSet<ComponentIdentity> = linkedSetOf(ComponentIdentity(listOf("root")))
    private val childKeys: MutableSet<Pair<ComponentIdentity, Any>> = linkedSetOf()
    private val stateNames: MutableSet<Pair<ComponentIdentity, String>> = linkedSetOf()
    private val renderedNavigators: MutableSet<ComponentIdentity> = linkedSetOf()
    private val slots: MutableMap<Int, RenderedSlot> = linkedMapOf()
    private val effects: MutableMap<EffectIdentity, EffectDeclaration> = linkedMapOf()
    private val storages: MutableMap<MenuStorageId, MenuStorage> = linkedMapOf()
    private val playerInventory: MutableSet<PlayerInventorySection> = linkedSetOf()
    private val transferRoutes: MutableList<MenuTransferRoute> = mutableListOf()
    private var chestTitle: Component? = null
    private var chestRows: Int? = null

    fun root(): MenuScope = DefaultMenuScope(
        builder = this,
        identity = ComponentIdentity(listOf("root")),
        locals = emptyMap(),
        boundary = null,
    )

    fun component(
        parent: MenuScope,
        key: Any,
        content: context(MenuScope) () -> Unit,
    ) {
        requireUsableKey(key)
        if (!childKeys.add(parent.identity to key)) {
            throw MenuValidationException(
                "Duplicate component key '${displayKey(key)}' below ${parent.identity.semantic()}",
            )
        }
        val child = DefaultMenuScope(
            this,
            parent.identity.child(key),
            parent.locals,
            parent.boundary,
        )
        components += child.identity
        context(child) { content() }
    }

    fun <T> stateBinding(
        component: ComponentIdentity,
        initial: () -> T,
    ): StateBinding<T> = stateStore.binding(component, initial) { name ->
        if (!stateNames.add(component to name)) {
            throw MenuValidationException("Duplicate state '$name' at ${component.semantic()}")
        }
    }

    fun <T> collect(
        component: ComponentIdentity,
        boundary: BoundaryIdentity?,
        name: String,
        flow: Flow<T>,
        emit: (T) -> Unit,
    ) {
        val identity = EffectIdentity(component, "flow", CollectedEffectKey(name, flow))
        addEffect(
            EffectDeclaration.Collection(identity, boundary, flow, emit),
        )
    }

    fun <T> provide(
        parent: MenuScope,
        local: MenuLocal<T>,
        value: T,
        content: context(MenuScope) () -> Unit,
    ) {
        val provided = DefaultMenuScope(
            this,
            parent.identity,
            parent.locals + (local to value),
            parent.boundary,
        )
        context(provided) { content() }
    }

    fun effect(
        scope: MenuScope,
        key: Any,
        block: MenuEffectScope.() -> Unit,
    ) {
        requireUsableKey(key)
        addEffect(
            EffectDeclaration.Synchronous(
                EffectIdentity(scope.identity, "effect", key),
                scope.boundary,
                block,
            ),
        )
    }

    fun launchedEffect(
        scope: MenuScope,
        key: Any,
        block: suspend CoroutineScope.() -> Unit,
    ) {
        requireUsableKey(key)
        addEffect(
            EffectDeclaration.Launched(
                EffectIdentity(scope.identity, "launched", key),
                scope.boundary,
                block,
            ),
        )
    }

    fun chest(
        parent: MenuScope,
        title: Component,
        rows: Int,
        content: context(ChestScope) () -> Unit,
    ) {
        if (chestRows != null) throw MenuValidationException("One render cannot declare two hosts")
        if (rows !in 1..6) throw MenuValidationException("Chest rows must be between 1 and 6")
        chestTitle = title
        chestRows = rows
        val chest = DefaultChestScope(
            this,
            parent.identity,
            parent.locals,
            parent.boundary,
            rows,
        )
        context(chest) { content() }
    }

    fun slot(
        host: ChestScope,
        owner: MenuScope,
        index: Int,
        modifiers: List<SlotModifier>,
        content: ActionSlotScope.() -> Unit,
    ) {
        val capacity = host.rows * 9
        if (index !in 0 until capacity) {
            throw MenuValidationException(
                "Slot $index at ${owner.identity.semantic()} lies outside a ${host.rows}-row chest",
            )
        }
        if (modifiers.map(SlotModifier::key).distinct().size != modifiers.size) {
            throw MenuValidationException("Slot $index repeats a modifier key at ${owner.identity.semantic()}")
        }
        val declaration = DefaultActionSlotScope(owner, index).apply(content)
        val rendered = RenderedSlot(
            index,
            owner.identity,
            declaration.item,
            null,
            null,
            declaration.actions.toMap(),
            declaration.anyGesture,
            modifiers.toList(),
            owner.locals.toMap(),
        )
        val previous = slots.putIfAbsent(index, rendered)
        if (previous != null) {
            throw MenuValidationException(
                "Slot $index is owned by both ${previous.owner.semantic()} and ${owner.identity.semantic()}",
            )
        }
    }

    fun storage(
        host: ChestScope,
        owner: MenuScope,
        storage: MenuStorage,
        region: SlotRegion,
    ) {
        val snapshot = storage.snapshots.value
        if (snapshot.size != region.size) {
            throw MenuValidationException(
                "Storage ${storage.id} has ${snapshot.size} slots for a ${region.size}-slot region",
            )
        }
        val previousStorage = storages.putIfAbsent(storage.id, storage)
        if (previousStorage != null && previousStorage !== storage) {
            throw MenuValidationException("Two storage instances use identity ${storage.id}")
        }
        var initialEmission = true
        addEffect(
            EffectDeclaration.Collection(
                EffectIdentity(owner.identity, "storage", storage.id),
                owner.boundary,
                storage.snapshots,
            ) {
                if (initialEmission) initialEmission = false else invalidate()
            },
        )
        region.slots.forEachIndexed { storageIndex, physicalIndex ->
            if (physicalIndex !in 0 until host.rows * 9) {
                throw MenuValidationException("Storage slot $physicalIndex lies outside this chest")
            }
            val rendered = RenderedSlot(
                physicalIndex,
                owner.identity,
                null,
                snapshot[storageIndex],
                MenuSlotAddress(storage.id, storageIndex),
                emptyMap(),
                null,
                emptyList(),
                owner.locals.toMap(),
            )
            val previous = slots.putIfAbsent(physicalIndex, rendered)
            if (previous != null) {
                throw MenuValidationException(
                    "Slot $physicalIndex is owned by both ${previous.owner.semantic()} and ${owner.identity.semantic()}",
                )
            }
        }
    }

    fun playerInventory(sections: Set<PlayerInventorySection>) {
        playerInventory += sections
    }

    fun transfers(routes: List<MenuTransferRoute>) {
        transferRoutes += routes
    }

    fun errorBoundary(
        parent: MenuScope,
        key: Any,
        fallback: context(MenuScope) (MenuFailure, MenuRetry) -> Unit,
        content: context(MenuScope) () -> Unit,
    ) {
        requireUsableKey(key)
        if (!childKeys.add(parent.identity to key)) {
            throw MenuValidationException("Duplicate error-boundary key '${displayKey(key)}'")
        }
        val childIdentity = parent.identity.child(key)
        val boundary = BoundaryIdentity(childIdentity, key)
        val child = DefaultMenuScope(this, childIdentity, parent.locals, boundary)
        components += childIdentity
        val captured = boundaryFailures[boundary]
        if (captured != null) {
            context(child) { fallback(captured, MenuRetry { clearBoundary(boundary) }) }
            return
        }

        val checkpoint = Checkpoint(
            components.toSet(),
            childKeys.toSet(),
            stateNames.toSet(),
            renderedNavigators.toSet(),
            slots.toMap(),
            effects.toMap(),
            storages.toMap(),
            playerInventory.toSet(),
            transferRoutes.toList(),
            chestTitle,
            chestRows,
        )
        try {
            context(child) { content() }
        } catch (cause: Throwable) {
            restore(checkpoint)
            components += childIdentity
            val failure = MenuFailure(childIdentity.semantic(), cause)
            boundaryFailures[boundary] = failure
            context(child) { fallback(failure, MenuRetry { clearBoundary(boundary) }) }
        }
    }

    fun <R : Any> navigator(
        parent: MenuScope,
        initial: R,
        content: MenuNavigationScope<R>.() -> Unit,
    ) {
        if (!renderedNavigators.add(parent.identity)) {
            throw MenuValidationException("Duplicate navigator at ${parent.identity.semantic()}")
        }
        @Suppress("UNCHECKED_CAST")
        val navigation = navigationStates.getOrPut(parent.identity) {
            NavigationState(initial as Any).apply {
                invalidate = this@MenuTreeBuilder.invalidate
                closeSession = this@MenuTreeBuilder.closeSession
                discard = { entry -> stateStore.removeUnder(parent.identity.child(ScreenIdentity(entry))) }
            }
        } as NavigationState<R>
        navigation.invalidate = invalidate
        navigation.closeSession = closeSession
        navigation.discard = { entry -> stateStore.removeUnder(parent.identity.child(ScreenIdentity(entry))) }
        val scope = DefaultNavigationScope(parent, navigation).apply(content)
        if (!scope.matched) {
            throw MenuValidationException(
                "No screen matched route ${navigation.current} at ${parent.identity.semantic()}",
            )
        }
    }

    fun build(): RenderTree {
        val title = chestTitle ?: throw MenuValidationException("A menu render must declare one concrete host")
        val rows = chestRows ?: throw MenuValidationException("A menu render must declare one concrete host")
        val duplicateSources = transferRoutes.groupBy(MenuTransferRoute::source).filterValues { it.size > 1 }.keys
        if (duplicateSources.isNotEmpty()) {
            throw MenuValidationException("Duplicate transfer routes for $duplicateSources")
        }
        return RenderTree(
            RenderedChest(title, rows, slots.toMap()),
            components.toSet(),
            effects.toMap(),
            storages.toMap(),
            playerInventory.toSet(),
            transferRoutes.toList(),
        )
    }

    private fun addEffect(effect: EffectDeclaration) {
        if (effects.putIfAbsent(effect.identity, effect) != null) {
            throw MenuValidationException("Duplicate effect ${effect.identity.key} at ${effect.identity.component.semantic()}")
        }
    }

    private fun restore(checkpoint: Checkpoint) {
        components.clear(); components += checkpoint.components
        childKeys.clear(); childKeys += checkpoint.childKeys
        stateNames.clear(); stateNames += checkpoint.stateNames
        renderedNavigators.clear(); renderedNavigators += checkpoint.renderedNavigators
        slots.clear(); slots += checkpoint.slots
        effects.clear(); effects += checkpoint.effects
        storages.clear(); storages += checkpoint.storages
        playerInventory.clear(); playerInventory += checkpoint.playerInventory
        transferRoutes.clear(); transferRoutes += checkpoint.transferRoutes
        chestTitle = checkpoint.chestTitle
        chestRows = checkpoint.chestRows
    }

    private data class Checkpoint(
        val components: Set<ComponentIdentity>,
        val childKeys: Set<Pair<ComponentIdentity, Any>>,
        val stateNames: Set<Pair<ComponentIdentity, String>>,
        val renderedNavigators: Set<ComponentIdentity>,
        val slots: Map<Int, RenderedSlot>,
        val effects: Map<EffectIdentity, EffectDeclaration>,
        val storages: Map<MenuStorageId, MenuStorage>,
        val playerInventory: Set<PlayerInventorySection>,
        val transferRoutes: List<MenuTransferRoute>,
        val chestTitle: Component?,
        val chestRows: Int?,
    )
}

private class CollectedEffectKey(
    private val name: String,
    private val flow: Any,
) {
    override fun equals(other: Any?): Boolean =
        other is CollectedEffectKey && name == other.name && flow === other.flow

    override fun hashCode(): Int = 31 * name.hashCode() + System.identityHashCode(flow)

    override fun toString(): String = name
}

private fun requireUsableKey(key: Any) {
    if (key is String && key.isBlank()) {
        throw MenuValidationException("A menu key cannot be blank")
    }
    if (key is Float && key.isNaN() || key is Double && key.isNaN()) {
        throw MenuValidationException("NaN cannot be a stable menu key")
    }
}
