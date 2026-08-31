package dev.placeholder.framework.menus

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import net.kyori.adventure.text.Component
import dev.placeholder.framework.menus.storage.MenuSlotAddress
import dev.placeholder.framework.menus.storage.MenuStorage
import dev.placeholder.framework.menus.storage.MenuStorageId
import dev.placeholder.framework.menus.storage.MenuTransferRoute
import dev.placeholder.framework.menus.storage.PlayerInventorySection
import dev.placeholder.framework.menus.storage.MenuStorageRules
import dev.placeholder.framework.menus.storage.MenuStorageReference
import dev.placeholder.framework.menus.storage.localMenuStorage
import dev.placeholder.framework.items.ItemSnapshot

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
    private val hostActions: MutableMap<MenuHostInputKind, MenuHostActionDeclaration> = linkedMapOf()
    private val effects: MutableMap<EffectIdentity, EffectDeclaration> = linkedMapOf()
    private val storages: MutableMap<MenuStorageId, MenuStorage> = linkedMapOf()
    private val playerInventory: MutableSet<PlayerInventorySection> = linkedSetOf()
    private val transferRoutes: MutableList<MenuTransferRoute> = mutableListOf()
    private val activeScopes: java.util.ArrayDeque<MenuScope> = java.util.ArrayDeque()
    private var hostDescriptor: RenderedHostDescriptor? = null
    private var nativeCloseNavigation: NavigationState<Any>? = null

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
        val owner = activeScopes.peekLast() ?: parent
        requireUsableKey(key)
        if (!childKeys.add(owner.identity to key)) {
            throw MenuValidationException(
                "Duplicate component key '${displayKey(key)}' below ${owner.identity.semantic()}",
            )
        }
        val child = DefaultMenuScope(
            this,
            owner.identity.child(key),
            owner.locals,
            owner.boundary,
        )
        components += child.identity
        withScope(child) { context(child) { content() } }
    }

    fun <T> stateBinding(
        component: ComponentIdentity,
        initial: () -> T,
    ): StateBinding<T> = stateStore.binding(component, initial) { name ->
        if (!stateNames.add(component to name)) {
            throw MenuValidationException("Duplicate state '$name' at ${component.semantic()}")
        }
    }

    fun rememberStorage(
        scope: MenuScope,
        id: MenuStorageId,
        initial: List<ItemSnapshot?>,
        rules: MenuStorageRules,
    ): MenuStorage {
        val owner = activeScopes.peekLast() ?: scope
        val name = "storage:$id"
        if (!stateNames.add(owner.identity to name)) {
            throw MenuValidationException("Duplicate remembered storage '$id' at ${owner.identity.semantic()}")
        }
        return stateStore.value(owner.identity, name) { localMenuStorage(id, initial, rules) }
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
        withScope(provided) { context(provided) { content() } }
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
        if (hostDescriptor != null) throw MenuValidationException("One render cannot declare two hosts")
        if (rows !in 1..6) throw MenuValidationException("Chest rows must be between 1 and 6")
        hostDescriptor = RenderedHostDescriptor.chest(title, rows).ownedBy(parent)
        val chest = DefaultChestScope(
            this,
            parent.identity,
            parent.locals,
            parent.boundary,
            rows,
        )
        withScope(chest) { context(chest) { content() } }
    }

    fun containerHost(
        parent: MenuScope,
        descriptor: RenderedHostDescriptor,
        content: context(ContainerHostScope) () -> Unit,
    ) {
        beginHost(descriptor.ownedBy(parent))
        val host = DefaultContainerHostScope(
            this,
            parent.identity,
            parent.locals,
            parent.boundary,
            descriptor.capacity,
        )
        withScope(host) { context(host) { content() } }
    }

    fun <R : Enum<R>> roleHost(
        parent: MenuScope,
        descriptor: RenderedHostDescriptor,
        index: (R) -> Int,
        content: context(RoleHostScope<R>) () -> Unit,
    ) {
        beginHost(descriptor.ownedBy(parent))
        val host = DefaultRoleHostScope(
            this,
            parent.identity,
            parent.locals,
            parent.boundary,
            descriptor.capacity,
            index,
        )
        withScope(host) { context(host) { content() } }
    }

    private fun beginHost(descriptor: RenderedHostDescriptor) {
        if (hostDescriptor != null) throw MenuValidationException("One render cannot declare two hosts")
        hostDescriptor = descriptor
    }

    fun slot(
        host: InventoryHostScope,
        index: Int,
        modifiers: List<SlotModifier>,
        content: ActionSlotScope.() -> Unit,
    ) {
        val owner = activeScopes.peekLast() ?: host
        val capacity = host.capacity
        if (index !in 0 until capacity) {
            throw MenuValidationException(
                "Slot $index at ${owner.identity.semantic()} lies outside this ${host.capacity}-slot host",
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
        host: InventoryHostScope,
        storage: MenuStorage,
        region: SlotRegion,
    ) {
        val owner = activeScopes.peekLast() ?: host
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
        val renderedRevision = snapshot.revision
        addEffect(
            EffectDeclaration.Collection(
                EffectIdentity(owner.identity, "storage", StorageEffectKey(storage.id, storage.snapshots)),
                owner.boundary,
                storage.snapshots,
                emit = { emitted ->
                    if (emitted.revision != renderedRevision) invalidate()
                },
                persistsWhilePresentationIsSuspended = true,
            ),
        )
        region.slots.forEachIndexed { storageIndex, physicalIndex ->
            if (physicalIndex !in 0 until host.capacity) {
                throw MenuValidationException("Storage slot $physicalIndex lies outside this host")
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

    fun <I : MenuHostInput> hostInput(
        scope: MenuScope,
        kind: MenuHostInputKind,
        concurrency: MenuActionConcurrency,
        action: suspend MenuActionScope.(I) -> Unit,
    ) {
        check(hostDescriptor != null) { "Host input handlers must be declared inside a host" }
        val owner = activeScopes.peekLast() ?: scope
        val declaration = MenuHostActionDeclaration(
            identity = MenuHostActionIdentity(owner.identity, kind),
            concurrency = concurrency,
            boundary = owner.boundary,
            feedbackTheme = owner.local(MenuFeedbackThemeLocal),
            handler = { input ->
                @Suppress("UNCHECKED_CAST")
                action(input as I)
            },
        )
        if (hostActions.putIfAbsent(kind, declaration) != null) {
            throw MenuValidationException("Duplicate $kind handler at ${owner.identity.semantic()}")
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
            withScope(child) { context(child) { fallback(captured, MenuRetry { clearBoundary(boundary) }) } }
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
            hostDescriptor,
            nativeCloseNavigation,
            hostActions.toMap(),
        )
        try {
            withScope(child) { context(child) { content() } }
        } catch (cause: Throwable) {
            restore(checkpoint)
            components += childIdentity
            val failure = MenuFailure(childIdentity.semantic(), cause)
            boundaryFailures[boundary] = failure
            withScope(child) { context(child) { fallback(failure, MenuRetry { clearBoundary(boundary) }) } }
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
        @Suppress("UNCHECKED_CAST")
        run { nativeCloseNavigation = navigation as NavigationState<Any> }
        val scope = DefaultNavigationScope(parent, navigation).apply(content)
        if (!scope.matched) {
            throw MenuValidationException(
                "No screen matched route ${navigation.current} at ${parent.identity.semantic()}",
            )
        }
    }

    fun build(): RenderTree {
        val host = hostDescriptor ?: throw MenuValidationException("A menu render must declare one concrete host")
        val duplicateSources = transferRoutes.groupBy(MenuTransferRoute::source).filterValues { it.size > 1 }.keys
        if (duplicateSources.isNotEmpty()) {
            throw MenuValidationException("Duplicate transfer routes for $duplicateSources")
        }
        transferRoutes.forEach(::validateTransferRoute)
        return RenderTree(
            RenderedChest(host.title, (host.capacity + 8) / 9, slots.toMap(), host),
            hostActions.toMap(),
            components.toSet(),
            effects.toMap(),
            storages.toMap(),
            playerInventory.toSet(),
            transferRoutes.toList(),
            nativeCloseNavigation,
        )
    }

    private fun validateTransferRoute(route: MenuTransferRoute) {
        val references = listOf(route.source) + route.destinations
        references.forEach { reference ->
            when (reference) {
                is MenuStorageReference.Storage -> if (reference.id !in storages) {
                    throw MenuValidationException("Transfer route references undeclared storage ${reference.id}")
                }
                is MenuStorageReference.Player -> if (reference.section !in playerInventory) {
                    throw MenuValidationException(
                        "Transfer route references undeclared player section ${reference.section}",
                    )
                }
            }
        }
        val persistent = references.mapNotNull { reference ->
            (reference as? MenuStorageReference.Storage)
                ?.let { storage -> storages.getValue(storage.id) }
                ?.takeIf { storage -> storage.transactionDomain != null }
        }.distinctBy(MenuStorage::id)
        val domains = persistent.mapNotNull(MenuStorage::transactionDomain).distinctBy { domain -> domain.id }
        if (domains.size > 1 || domains.singleOrNull()?.let { domain ->
                persistent.any { storage -> storage.id !in domain.storages }
            } == true
        ) {
            throw MenuValidationException(
                "Transfer route ${route.source} crosses persistent storage without one transaction domain",
            )
        }
    }

    private fun addEffect(effect: EffectDeclaration) {
        if (effects.putIfAbsent(effect.identity, effect) != null) {
            throw MenuValidationException("Duplicate effect ${effect.identity.key} at ${effect.identity.component.semantic()}")
        }
    }

    private inline fun <T> withScope(scope: MenuScope, block: () -> T): T {
        activeScopes.addLast(scope)
        return try {
            block()
        } finally {
            check(activeScopes.removeLast() === scope) { "Menu scope stack was corrupted" }
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
        hostDescriptor = checkpoint.hostDescriptor
        nativeCloseNavigation = checkpoint.nativeCloseNavigation
        hostActions.clear(); hostActions += checkpoint.hostActions
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
        val hostDescriptor: RenderedHostDescriptor?,
        val nativeCloseNavigation: NavigationState<Any>?,
        val hostActions: Map<MenuHostInputKind, MenuHostActionDeclaration>,
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

private class StorageEffectKey(
    private val id: MenuStorageId,
    private val snapshots: Any,
) {
    override fun equals(other: Any?): Boolean =
        other is StorageEffectKey && id == other.id && snapshots === other.snapshots

    override fun hashCode(): Int = 31 * id.hashCode() + System.identityHashCode(snapshots)

    override fun toString(): String = id.toString()
}

private fun requireUsableKey(key: Any) {
    if (key is String && key.isBlank()) {
        throw MenuValidationException("A menu key cannot be blank")
    }
    if (key is Float && key.isNaN() || key is Double && key.isNaN()) {
        throw MenuValidationException("NaN cannot be a stable menu key")
    }
}
