package dev.placeholder.framework.menus

import dev.placeholder.framework.ComponentContext
import dev.placeholder.framework.ComponentPhase
import dev.placeholder.framework.FrameworkComponent
import dev.placeholder.framework.PluginComponent
import dev.placeholder.framework.di.Inject
import dev.placeholder.framework.execution.PlayerRef
import dev.placeholder.framework.menus.internal.paper.PaperMenuNativeHostFactory
import dev.placeholder.framework.menus.storage.FileMenuTransactionJournal
import dev.placeholder.framework.menus.storage.MenuNativeTransaction
import dev.placeholder.framework.menus.storage.MenuSlotAddress
import dev.placeholder.framework.menus.storage.MenuStorageGesture
import dev.placeholder.framework.menus.storage.MenuStorageId
import dev.placeholder.framework.menus.storage.MenuStorageReference
import dev.placeholder.framework.menus.storage.MenuStorageRules
import dev.placeholder.framework.menus.storage.MenuTransactionCoordinator
import dev.placeholder.framework.menus.storage.MenuTransactionEngine
import dev.placeholder.framework.menus.storage.MenuTransactionPlan
import dev.placeholder.framework.menus.storage.MenuTransactionSubmission
import dev.placeholder.framework.menus.storage.MenuTransactionState
import dev.placeholder.framework.menus.storage.PlayerInventorySection
import dev.placeholder.framework.menus.storage.localMenuStorage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.bukkit.plugin.Plugin

/** Why a logical menu session ended. */
public sealed interface MenuClose {
    public data object PlayerClosed : MenuClose
    public data object ExternalInventory : MenuClose
    public data object Explicit : MenuClose
    public data object Replaced : MenuClose
    public data object Disconnected : MenuClose
    public data object Died : MenuClose
    public data object Kicked : MenuClose
    public data object CallerCancelled : MenuClose
    public data object PluginStopped : MenuClose
    public data class Failed(public val cause: Throwable) : MenuClose
}

/** Behavior when opening for a player who already has an active session. */
public enum class MenuOpenConflict {
    REPLACE,
    REJECT,
}

/** Result of attempting to open a logical menu session. */
public sealed interface MenuOpen {
    public data class Closed(public val reason: MenuClose) : MenuOpen
    public data object Rejected : MenuOpen
}

/** Result of a typed menu choice. */
public sealed interface MenuChoice<out T> {
    public data class Selected<T>(public val value: T) : MenuChoice<T>
    public data class Closed(public val reason: MenuClose) : MenuChoice<Nothing>
}

/** Native close input projected without exposing a Paper event. */
public enum class MenuNativeClose {
    PLAYER,
    EXTERNAL_INVENTORY,
    DISCONNECT,
    DEATH,
    KICK,
}

/** Callbacks installed by the semantic runtime on one native host. */
public interface MenuNativeCallbacks {
    /** Dispatches one immutable projected gesture. */
    public fun dispatch(interaction: MenuInteraction): MenuDispatch

    /** Reports that the native presentation ended. */
    public fun closed(reason: MenuNativeClose)
}

/** Adapter seam implemented by native Paper/Folia and deterministic test hosts. */
public interface MenuNativeHost {
    /** Mounts the first validated semantic render. */
    public suspend fun mount(
        render: MenuRenderSnapshot,
        callbacks: MenuNativeCallbacks,
    )

    /** Applies one validated semantic reconciliation. */
    public suspend fun reconcile(
        render: MenuRenderSnapshot,
        change: MenuReconciliation,
    )

    /** Removes native presentation without reporting a player close. */
    public suspend fun close()

    /** Presents typed action feedback using the active theme. */
    public suspend fun feedback(value: MenuFeedback) {}

    /** Applies one accepted player inventory, cursor, and emission transaction. */
    public suspend fun commitTransaction(transaction: MenuNativeTransaction) {}
}

/** Creates one native presentation for a stable player reference. */
public fun interface MenuNativeHostFactory {
    /** Creates a fresh host adapter for [player]. */
    public fun create(player: PlayerRef): MenuNativeHost
}

/** Player-scoped capability for suspending menu sessions and typed choices. */
@FrameworkComponent(name = "menus", phase = ComponentPhase.FRAMEWORK)
public class PlayerMenus private constructor(
    initialScope: CoroutineScope?,
    initialHosts: MenuNativeHostFactory?,
    private val plugin: Plugin?,
) : PluginComponent(), AutoCloseable {
    private val sessions: ConcurrentHashMap<PlayerRef, MenuSessionCore> = ConcurrentHashMap()
    private var runtimeScope: CoroutineScope? = initialScope
    private var hosts: MenuNativeHostFactory? = initialHosts
    private val transactions: MenuTransactionCoordinator = MenuTransactionCoordinator(
        plugin?.let { owner ->
            FileMenuTransactionJournal(owner.dataFolder.toPath().resolve("framework/menus/transactions"))
        },
    )

    /** Creates the automatically installed Paper/Folia menu runtime. */
    @Inject
    public constructor(plugin: Plugin) : this(null, null, plugin)

    /** Creates a runtime over an explicit host factory, primarily for deterministic tests. */
    public constructor(scope: CoroutineScope, hosts: MenuNativeHostFactory) : this(scope, hosts, null)

    override fun ComponentContext.start() {
        check(runtimeScope == null && hosts == null) { "This PlayerMenus instance is already configured" }
        val nativeHosts = own(PaperMenuNativeHostFactory(requireNotNull(plugin)))
        hosts = nativeHosts
        task("menu-session-owner", CoroutineStart.UNDISPATCHED) {
            runtimeScope = this
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) {
                    val active = sessions.values.toList()
                    active.forEach { session -> session.close(MenuClose.PluginStopped) }
                    active.forEach { session -> session.closeNativeAndAwait() }
                    sessions.clear()
                }
            }
        }
        check(runtimeScope != null) { "The menu session owner did not start synchronously" }
    }

    /** Opens one logical session and suspends until it closes. */
    public suspend fun open(
        player: PlayerRef,
        conflict: MenuOpenConflict = MenuOpenConflict.REPLACE,
        content: context(MenuScope) () -> Unit,
    ): MenuOpen = openSession(player, conflict, null, content)

    /** Opens a typed choice and suspends until selection or close. */
    public suspend fun <T> choose(
        player: PlayerRef,
        conflict: MenuOpenConflict = MenuOpenConflict.REPLACE,
        content: context(MenuScope) () -> Unit,
    ): MenuChoice<T> {
        val selection = CompletableDeferred<Any?>()
        val opened = openSession(player, conflict, selection, content)
        if (selection.isCompleted) {
            @Suppress("UNCHECKED_CAST")
            return MenuChoice.Selected(selection.await() as T)
        }
        return when (opened) {
            is MenuOpen.Closed -> MenuChoice.Closed(opened.reason)
            MenuOpen.Rejected -> MenuChoice.Closed(MenuClose.Replaced)
        }
    }

    /** Atomically closes the active session for [player], if present. */
    public fun close(
        player: PlayerRef,
        reason: MenuClose = MenuClose.Explicit,
    ): Boolean = sessions[player]?.close(reason) ?: false

    /** Returns a redacted semantic inspection of an active session. */
    public fun inspect(player: PlayerRef): MenuInspection? = sessions[player]?.inspection()

    override fun close() {
        sessions.values.forEach { it.close(MenuClose.PluginStopped) }
        sessions.clear()
    }

    internal suspend fun startTesting(
        player: PlayerRef,
        host: MenuNativeHost,
        choice: CompletableDeferred<Any?>? = null,
        content: context(MenuScope) () -> Unit,
    ): MenuSessionCore {
        val session = MenuSessionCore(player, host, requireScope(), transactions, choice, content) {
            sessions.remove(player)
        }
        sessions[player]?.close(MenuClose.Replaced)
        sessions[player] = session
        session.start()
        return session
    }

    private suspend fun openSession(
        player: PlayerRef,
        conflict: MenuOpenConflict,
        choice: CompletableDeferred<Any?>?,
        content: context(MenuScope) () -> Unit,
    ): MenuOpen {
        val existing = sessions[player]
        if (existing != null && conflict == MenuOpenConflict.REJECT) return MenuOpen.Rejected
        existing?.close(MenuClose.Replaced)
        val session = MenuSessionCore(
            player,
            requireHosts().create(player),
            requireScope(),
            transactions,
            choice,
            content,
        ) {
            sessions.remove(player)
        }
        sessions[player] = session
        return try {
            session.start()
            MenuOpen.Closed(session.awaitClose())
        } catch (cancelled: CancellationException) {
            session.close(MenuClose.CallerCancelled)
            withContext(NonCancellable) { session.closeNativeAndAwait() }
            throw cancelled
        }
    }

    private fun requireScope(): CoroutineScope = checkNotNull(runtimeScope) { "PlayerMenus is not running" }

    private fun requireHosts(): MenuNativeHostFactory = checkNotNull(hosts) { "PlayerMenus is not running" }
}

internal class MenuSessionCore(
    private val player: PlayerRef,
    private val nativeHost: MenuNativeHost,
    parentScope: CoroutineScope,
    private val transactions: MenuTransactionCoordinator,
    private val choice: CompletableDeferred<Any?>?,
    private val content: context(MenuScope) () -> Unit,
    private val onClosed: () -> Unit,
) {
    private val ownerScope: CoroutineScope = parentScope
    private val job = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + job)
    private val invalidations = Channel<Unit>(Channel.CONFLATED)
    private val invalidationQueued = AtomicBoolean(false)
    private val invalidationVersion = AtomicLong(0)
    private val renderMutex = Mutex()
    private val nativeCloseMutex = Mutex()
    private var nativeClosed: Boolean = false
    private val closed = CompletableDeferred<MenuClose>()
    private val boundaryFailures: MutableMap<BoundaryIdentity, MenuFailure> = linkedMapOf()
    private val navigationStates: MutableMap<ComponentIdentity, NavigationState<Any>> = linkedMapOf()
    private val stateStore = MenuStateStore(::invalidate)
    private val actionJobs: MutableMap<MenuActionIdentity, MutableSet<Job>> = linkedMapOf()
    private val activeEffects: MutableMap<EffectIdentity, ActiveEffect> = linkedMapOf()
    private val trace = MenuTraceBuffer()
    private var renderLoop: Job? = null
    private var committed: RenderTree? = null
    private var snapshot: MenuRenderSnapshot? = null

    suspend fun start() {
        renderNow()
        if (closed.isCompleted) return
        renderLoop = scope.launch {
            for (ignored in invalidations) {
                val version = invalidationVersion.get()
                renderNow()
                invalidationQueued.set(false)
                if (invalidationVersion.get() != version && invalidationQueued.compareAndSet(false, true)) {
                    invalidations.trySend(Unit)
                }
            }
        }
    }

    suspend fun awaitClose(): MenuClose {
        val reason = closed.await()
        withContext(NonCancellable) { closeNativeAndAwait() }
        return reason
    }

    fun dispatch(interaction: MenuInteraction): MenuDispatch {
        if (closed.isCompleted) return MenuDispatch.Closed
        val currentSnapshot = snapshot ?: return MenuDispatch.Closed
        if (interaction.revision != currentSnapshot.revision) return MenuDispatch.StaleRevision
        val tree = committed ?: return MenuDispatch.Closed
        val slot = interaction.slot?.let(tree.host.slots::get)
        val action = slot?.actions?.get(interaction.gesture.kind) ?: slot?.anyGesture
        if (action != null) return dispatchAction(action, interaction)
        return dispatchStorage(tree, interaction)
    }

    private fun dispatchAction(
        action: MenuActionDeclaration,
        interaction: MenuInteraction,
    ): MenuDispatch {
        val running = actionJobs[action.identity].orEmpty().filter(Job::isActive)
        when (action.concurrency) {
            MenuActionConcurrency.SINGLE_FLIGHT -> if (running.isNotEmpty()) return MenuDispatch.AlreadyRunning
            MenuActionConcurrency.RESTART_LATEST -> running.forEach(Job::cancel)
            MenuActionConcurrency.PARALLEL -> Unit
        }
        trace.add(MenuTrace.ActionStarted(action.identity.toString()))
        val actionJob = scope.launch {
            try {
                action.handler(ActionScope(), interaction)
                trace.add(MenuTrace.ActionCompleted(action.identity.toString()))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (cause: Throwable) {
                containFailure(action.boundary, action.identity.component, cause)
            }
        }
        actionJobs.getOrPut(action.identity, ::linkedSetOf).add(actionJob)
        actionJob.invokeOnCompletion { actionJobs[action.identity]?.remove(actionJob) }
        return MenuDispatch.Accepted
    }

    private fun dispatchStorage(
        tree: RenderTree,
        interaction: MenuInteraction,
    ): MenuDispatch {
        val playerIds = tree.playerInventory.associateWith(::playerStorageId)
        val participantIds: Map<MenuStorageReference, MenuStorageId> =
            playerIds.mapKeys { (section, _) -> MenuStorageReference.Player(section) }
        val storageValues = linkedMapOf<MenuStorageId, dev.placeholder.framework.menus.storage.MenuStorageSnapshot>()
        val storageRules = linkedMapOf<MenuStorageId, MenuStorageRules>()
        val participants = tree.storages.toMutableMap()
        for ((id, storage) in tree.storages) {
            storageValues[id] = storage.snapshots.value
            storageRules[id] = storage.rules
        }
        for ((section, id) in playerIds) {
            val items = interaction.playerInventory[section] ?: return MenuDispatch.StaleRevision
            if (items.size != section.size) return MenuDispatch.StaleRevision
            val storage = localMenuStorage(id, items)
            participants[id] = storage
            storageValues[id] = storage.snapshots.value
            storageRules[id] = storage.rules
        }

        fun hostAddress(index: Int): MenuSlotAddress? = tree.host.slots[index]?.storage
        fun playerAddress(slot: PlayerInventorySlot): MenuSlotAddress? =
            playerIds[slot.section]?.let { id -> MenuSlotAddress(id, slot.index) }
        val source = interaction.slot?.let(::hostAddress) ?: interaction.playerSlot?.let(::playerAddress)
        if (source != null && storageValues[source.storage]?.get(source.index) != interaction.clicked) {
            return MenuDispatch.StaleRevision
        }
        val gesture = when (val input = interaction.gesture) {
            MenuGesture.Primary -> source?.let(MenuStorageGesture::Primary)
            MenuGesture.Secondary -> source?.let(MenuStorageGesture::Secondary)
            MenuGesture.ShiftPrimary,
            MenuGesture.ShiftSecondary,
            -> source?.let(MenuStorageGesture::ShiftTransfer)
            is MenuGesture.NumberKey -> {
                val hotbar = playerIds[PlayerInventorySection.HOTBAR]
                    ?.let { id -> MenuSlotAddress(id, input.index) }
                if (source == null || hotbar == null) null else MenuStorageGesture.HotbarSwap(source, hotbar)
            }
            MenuGesture.SwapOffhand -> {
                val offhand = playerIds[PlayerInventorySection.OFFHAND]
                    ?.let { id -> MenuSlotAddress(id, 0) }
                if (source == null || offhand == null) null else MenuStorageGesture.OffhandSwap(source, offhand)
            }
            MenuGesture.DropOne -> source?.let(MenuStorageGesture::DropOne)
            MenuGesture.DropStack -> source?.let(MenuStorageGesture::DropStack)
            MenuGesture.DoubleClick -> MenuStorageGesture.DoubleCollect(
                tree.host.slots.values.sortedBy(RenderedSlot::index).mapNotNull(RenderedSlot::storage) +
                    playerAddresses(playerIds),
            )
            is MenuGesture.Drag -> {
                val targets = interaction.hostSlots.mapNotNull(::hostAddress) +
                    interaction.playerSlots.mapNotNull(::playerAddress)
                targets.takeIf(List<MenuSlotAddress>::isNotEmpty)?.let { addresses ->
                    MenuStorageGesture.Drag(addresses, input.mode)
                }
            }
            is MenuGesture.Outside -> MenuStorageGesture.DropCursor(input.button == MenuOutsideButton.SECONDARY)
            MenuGesture.Middle,
            MenuGesture.Creative,
            -> null
        } ?: return if (source == null) MenuDispatch.EmptySlot else MenuDispatch.UnsupportedGesture

        val engine = MenuTransactionEngine(storageRules, tree.transferRoutes, participantIds)
        return when (
            val plan = engine.plan(
                MenuTransactionState(storageValues, interaction.cursor),
                gesture,
                playerId = player.uniqueId,
            )
        ) {
            MenuTransactionPlan.NoChange -> MenuDispatch.Accepted
            is MenuTransactionPlan.Rejected -> MenuDispatch.TransactionRejected(plan.failure)
            is MenuTransactionPlan.Proposed -> {
                scope.launch {
                    when (val submission = transactions.submit(plan.proposal, participants, this@MenuSessionCore)) {
                        is MenuTransactionSubmission.Committed -> {
                            nativeHost.commitTransaction(
                                MenuNativeTransaction(submission.transaction, playerIds),
                            )
                            if (submission.transaction.requiresAcknowledgement) {
                                transactions.acknowledge(submission.transaction.id)
                            }
                            trace.add(MenuTrace.TransactionCommitted(submission.transaction.id.toString()))
                            invalidate()
                        }
                        is MenuTransactionSubmission.Rejected -> nativeHost.feedback(
                            MenuFeedback(submission.message, MenuFeedbackSeverity.REJECTION),
                        )
                        is MenuTransactionSubmission.Failed -> trace.add(
                            MenuTrace.TransactionRejected(submission.failure.toString()),
                        )
                    }
                }
                MenuDispatch.Accepted
            }
        }
    }

    private fun playerStorageId(section: PlayerInventorySection): MenuStorageId =
        MenuStorageId("framework-player", "${player.uniqueId}.${section.name.lowercase()}")

    private fun playerAddresses(ids: Map<PlayerInventorySection, MenuStorageId>): List<MenuSlotAddress> =
        listOf(
            PlayerInventorySection.MAIN,
            PlayerInventorySection.HOTBAR,
            PlayerInventorySection.OFFHAND,
            PlayerInventorySection.ARMOR,
        ).flatMap { section ->
            ids[section]?.let { id -> List(section.size) { index -> MenuSlotAddress(id, index) } }.orEmpty()
        }

    fun close(reason: MenuClose): Boolean {
        if (!closed.complete(reason)) return false
        trace.add(MenuTrace.Closed(reason))
        onClosed()
        invalidations.close()
        activeEffects.values.forEach { effect -> runCatching(effect::dispose) }
        activeEffects.clear()
        actionJobs.values.flatten().forEach(Job::cancel)
        renderLoop?.cancel()
        ownerScope.launch { closeNativeAndAwait() }
        job.cancel()
        stateStore.clear()
        return true
    }

    suspend fun closeNativeAndAwait() = nativeCloseMutex.withLock {
        if (nativeClosed) return@withLock
        nativeHost.close()
        nativeClosed = true
    }

    fun inspection(): MenuInspection? = snapshot?.let { render ->
        MenuInspection(
            render,
            trace.snapshot(),
            actionJobs.filterValues { jobs -> jobs.any(Job::isActive) }.keys.map(MenuActionIdentity::toString),
            activeEffects.keys.map { it.key.toString() },
        )
    }

    private fun invalidate() {
        invalidationVersion.incrementAndGet()
        if (!closed.isCompleted && invalidationQueued.compareAndSet(false, true)) {
            invalidations.trySend(Unit)
        }
    }

    private suspend fun renderNow() = renderMutex.withLock {
        if (closed.isCompleted) return@withLock
        val builder = MenuTreeBuilder(
            stateStore,
            boundaryFailures,
            clearBoundary = { boundary -> boundaryFailures.remove(boundary); invalidate() },
            invalidate = ::invalidate,
            navigationStates,
            closeSession = { close(MenuClose.Explicit) },
        )
        val tree = try {
            val root = builder.root()
            context(root) { content() }
            builder.build()
        } catch (cause: Throwable) {
            close(MenuClose.Failed(cause))
            return@withLock
        }
        val next = tree.snapshot((snapshot?.revision ?: 0L) + 1L, stateStore, navigationStates)
        val change = reconcile(snapshot, next)
        try {
            if (snapshot == null) {
                nativeHost.mount(next, NativeCallbacks())
            } else {
                nativeHost.reconcile(next, change)
            }
        } catch (cause: Throwable) {
            close(MenuClose.Failed(cause))
            return@withLock
        }
        committed = tree
        snapshot = next
        trace.add(MenuTrace.RenderCommitted(next.revision, change))
        reconcileEffects(tree.effects)
    }

    private fun reconcileEffects(next: Map<EffectIdentity, EffectDeclaration>) {
        (activeEffects.keys - next.keys).forEach { identity ->
            activeEffects.remove(identity)?.let(::disposeEffect)
        }
        next.forEach { (identity, declaration) ->
            if (identity !in activeEffects) activeEffects[identity] = startEffect(declaration)
        }
    }

    private fun startEffect(declaration: EffectDeclaration): ActiveEffect = when (declaration) {
        is EffectDeclaration.Synchronous -> {
            val disposals = mutableListOf<() -> Unit>()
            try {
                declaration.block(object : MenuEffectScope {
                    override fun onDispose(dispose: () -> Unit) { disposals += dispose }
                })
            } catch (cause: Throwable) {
                containFailure(declaration.boundary, declaration.identity.component, cause)
            }
            ActiveEffect(
                identity = declaration.identity,
                boundary = declaration.boundary,
                cleanup = { disposals.asReversed().forEach { it() } },
            )
        }
        is EffectDeclaration.Launched -> ActiveEffect(declaration.identity, declaration.boundary, job = scope.launch {
            try {
                declaration.block(this)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (cause: Throwable) {
                containFailure(declaration.boundary, declaration.identity.component, cause)
            }
        })
        is EffectDeclaration.Collection<*> -> ActiveEffect(declaration.identity, declaration.boundary, job = scope.launch {
            try {
                declaration.flow.collect { value ->
                    @Suppress("UNCHECKED_CAST")
                    (declaration.emit as (Any?) -> Unit)(value)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (cause: Throwable) {
                containFailure(declaration.boundary, declaration.identity.component, cause)
            }
        })
    }

    private fun disposeEffect(effect: ActiveEffect) {
        try {
            effect.dispose()
        } catch (cause: Throwable) {
            containFailure(effect.boundary, effect.identity.component, cause)
        }
    }

    private fun containFailure(
        boundary: BoundaryIdentity?,
        component: ComponentIdentity,
        cause: Throwable,
    ) {
        if (boundary == null) {
            close(MenuClose.Failed(cause))
            return
        }
        boundaryFailures[boundary] = MenuFailure(component.semantic(), cause)
        invalidate()
    }

    private inner class ActionScope : MenuActionScope {
        override fun feedback(value: MenuFeedback) {
            scope.launch { nativeHost.feedback(value) }
            trace.add(MenuTrace.Feedback(value.severity, value.targetSlot))
        }

        override fun close(reason: MenuClose) {
            this@MenuSessionCore.close(reason)
        }

        override fun finish(value: Any) {
            val destination = choice ?: error("finish(value) requires PlayerMenus.choose")
            if (destination.complete(value)) close(MenuClose.Explicit)
        }
    }

    private inner class NativeCallbacks : MenuNativeCallbacks {
        override fun dispatch(interaction: MenuInteraction): MenuDispatch = this@MenuSessionCore.dispatch(interaction)

        override fun closed(reason: MenuNativeClose) {
            val navigation = navigationStates.values.lastOrNull()
            if (reason == MenuNativeClose.PLAYER && navigation?.nativeClose == NativeClose.BACK && navigation.back()) {
                return
            }
            close(
                when (reason) {
                    MenuNativeClose.PLAYER -> MenuClose.PlayerClosed
                    MenuNativeClose.EXTERNAL_INVENTORY -> MenuClose.ExternalInventory
                    MenuNativeClose.DISCONNECT -> MenuClose.Disconnected
                    MenuNativeClose.DEATH -> MenuClose.Died
                    MenuNativeClose.KICK -> MenuClose.Kicked
                },
            )
        }
    }
}

private class ActiveEffect(
    val identity: EffectIdentity,
    val boundary: BoundaryIdentity?,
    private val cleanup: () -> Unit = {},
    private val job: Job? = null,
) {
    fun dispose() {
        job?.cancel()
        cleanup()
    }
}

private fun RenderTree.snapshot(
    revision: Long,
    states: MenuStateStore,
    navigation: Map<ComponentIdentity, NavigationState<Any>>,
): MenuRenderSnapshot = MenuRenderSnapshot(
    revision,
    MenuHostSnapshot.Chest(
        ChestHostSnapshot(
            host.title,
            host.rows,
            host.slots.values.sortedBy(RenderedSlot::index).map { slot ->
                MenuSlotSnapshot(
                    slot.index,
                    slot.owner.semantic(),
                    slot.item,
                    slot.storedItem,
                    slot.storage,
                    slot.actions.keys + if (slot.anyGesture == null) emptySet() else MenuGestureKind.entries.toSet(),
                    slot.modifiers,
                    slot.locals.entries.associate { (local, value) -> local.name to summarizeMenuValue(value) },
                )
            },
        ),
    ),
    components.map(ComponentIdentity::semantic).toSet(),
    states.snapshot(),
    navigation.values.flatMap { state -> state.routes.map(::displayKey) },
    storages.keys.mapTo(linkedSetOf(), dev.placeholder.framework.menus.storage.MenuStorageReference::Storage) +
        playerInventory.mapTo(linkedSetOf(), dev.placeholder.framework.menus.storage.MenuStorageReference::Player),
    storages,
    transferRoutes,
)

private fun reconcile(
    before: MenuRenderSnapshot?,
    after: MenuRenderSnapshot,
): MenuReconciliation {
    if (before == null || before.host::class != after.host::class || before.host.capacity != after.host.capacity) {
        return MenuReconciliation.Remount(before?.host, after.host)
    }
    val oldChest = (before.host as MenuHostSnapshot.Chest).chest
    val newChest = (after.host as MenuHostSnapshot.Chest).chest
    val oldSlots = oldChest.slots.associateBy(MenuSlotSnapshot::index)
    val newSlots = newChest.slots.associateBy(MenuSlotSnapshot::index)
    val changed = (oldSlots.keys + newSlots.keys).filterTo(linkedSetOf()) { oldSlots[it] != newSlots[it] }
    return MenuReconciliation.Update(oldChest.title != newChest.title, changed)
}
