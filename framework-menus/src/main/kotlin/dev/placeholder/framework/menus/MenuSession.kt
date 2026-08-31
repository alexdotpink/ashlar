package dev.placeholder.framework.menus

import dev.placeholder.framework.ComponentContext
import dev.placeholder.framework.ComponentPhase
import dev.placeholder.framework.FrameworkComponent
import dev.placeholder.framework.PluginComponent
import dev.placeholder.framework.di.Inject
import dev.placeholder.framework.execution.PlayerRef
import dev.placeholder.framework.menus.internal.paper.PaperMenuNativeHostFactory
import dev.placeholder.framework.menus.internal.paper.PaperMenuPlayerSettlement
import dev.placeholder.framework.menus.storage.FileMenuTransactionJournal
import dev.placeholder.framework.menus.storage.MenuDurableTransactionRuntime
import dev.placeholder.framework.menus.storage.MenuNativeCommit
import dev.placeholder.framework.menus.storage.MenuNativeTransaction
import dev.placeholder.framework.menus.storage.MenuPlayerSettlement
import dev.placeholder.framework.menus.storage.MenuSettlementResult
import dev.placeholder.framework.menus.storage.MenuSlotAddress
import dev.placeholder.framework.menus.storage.MenuStorageGesture
import dev.placeholder.framework.menus.storage.MenuStorageId
import dev.placeholder.framework.menus.storage.MenuStorageReference
import dev.placeholder.framework.menus.storage.MenuStorageRules
import dev.placeholder.framework.menus.storage.MenuTransactionCoordinator
import dev.placeholder.framework.menus.storage.MenuTransactionDomain
import dev.placeholder.framework.menus.storage.MenuTransactionEngine
import dev.placeholder.framework.menus.storage.MenuTransactionPlan
import dev.placeholder.framework.menus.storage.MenuTransactionSubmission
import dev.placeholder.framework.menus.storage.MenuTransactionState
import dev.placeholder.framework.menus.storage.MenuStorageSnapshot
import dev.placeholder.framework.menus.storage.PlayerInventorySection
import dev.placeholder.framework.menus.storage.localMenuStorage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.logging.Level
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
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.bukkit.plugin.Plugin

/** Why a logical menu session ended. */
public sealed interface MenuClose {
    /** The player closed the native inventory. */
    public data object PlayerClosed : MenuClose
    /** Another native inventory replaced the menu. */
    public data object ExternalInventory : MenuClose
    /** Plug-in code explicitly closed the session. */
    public data object Explicit : MenuClose
    /** A newer menu session replaced this session. */
    public data object Replaced : MenuClose
    /** The player disconnected. */
    public data object Disconnected : MenuClose
    /** The player died while viewing the menu. */
    public data object Died : MenuClose
    /** The server kicked the player. */
    public data object Kicked : MenuClose
    /** Cancellation of the suspending caller ended the session. */
    public data object CallerCancelled : MenuClose
    /** Plug-in shutdown ended the session. */
    public data object PluginStopped : MenuClose
    /** An uncontained runtime failure ended the session. */
    public data class Failed(public val cause: Throwable) : MenuClose
}

/** Behavior when opening for a player who already has an active session. */
public enum class MenuOpenConflict {
    REPLACE,
    REJECT,
}

/** Result of attempting to open a logical menu session. */
public sealed interface MenuOpen {
    /** The opened session eventually ended with [reason]. */
    public data class Closed(public val reason: MenuClose) : MenuOpen
    /** Conflict policy refused to replace an existing session. */
    public data object Rejected : MenuOpen
}

/** Result of a typed menu choice. */
public sealed interface MenuChoice<out T> {
    /** A menu action completed the choice with [value]. */
    public data class Selected<T>(public val value: T) : MenuChoice<T>
    /** The menu closed before choosing a value. */
    public data class Closed(public val reason: MenuClose) : MenuChoice<Nothing>
    /** Conflict policy prevented the choice menu from opening. */
    public data object NotOpened : MenuChoice<Nothing>
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

    /** Dispatches one immutable non-slot input from a specialized host. */
    public fun dispatch(input: MenuHostInput): MenuDispatch

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

    /** Hides native presentation while retaining session cursor and recoverable native state. */
    public suspend fun suspendPresentation() {
        close()
    }

    /** Presents typed action feedback using the active theme. */
    public suspend fun feedback(value: MenuFeedback) {}

    /** Presents already themed feedback. Adapters may support action bar, sound, and target emphasis. */
    public suspend fun feedback(value: MenuFeedback, presentation: MenuFeedbackPresentation) {
        feedback(value)
    }

    /** Applies one accepted player inventory, cursor, and emission transaction. */
    public suspend fun commitTransaction(transaction: MenuNativeTransaction): MenuNativeCommit =
        MenuNativeCommit.Applied
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
    private var durableRuntime: MenuDurableTransactionRuntime? = initialScope?.let { scope ->
        MenuDurableTransactionRuntime(
            scope,
            transactions,
            MenuPlayerSettlement { MenuSettlementResult.Pending },
        )
    }
    private val observers: CopyOnWriteArrayList<MenuObserver> = CopyOnWriteArrayList()
    private val interceptors: CopyOnWriteArrayList<MenuInterceptor> = CopyOnWriteArrayList()

    /** Creates the automatically installed Paper/Folia menu runtime. */
    @Inject
    public constructor(plugin: Plugin) : this(null, null, plugin)

    /** Creates a runtime over an explicit host factory, primarily for deterministic tests. */
    public constructor(scope: CoroutineScope, hosts: MenuNativeHostFactory) : this(scope, hosts, null)

    override fun ComponentContext.start() {
        check(runtimeScope == null && hosts == null) { "This PlayerMenus instance is already configured" }
        task("menu-session-owner", CoroutineStart.UNDISPATCHED) {
            runtimeScope = this
            val owner = requireNotNull(plugin)
            val settlement = own(PaperMenuPlayerSettlement(owner, this))
            val runtime = MenuDurableTransactionRuntime(this, transactions, settlement)
            durableRuntime = runtime
            settlement.retryWith(runtime::retry)
            hosts = own(PaperMenuNativeHostFactory(owner, settlement))
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
            MenuOpen.Rejected -> MenuChoice.NotOpened
        }
    }

    /** Atomically closes the active session for [player], if present. */
    public fun close(
        player: PlayerRef,
        reason: MenuClose = MenuClose.Explicit,
    ): Boolean = sessions[player]?.close(reason) ?: false

    /** Returns a redacted semantic inspection of an active session. */
    public fun inspect(player: PlayerRef): MenuInspection? = sessions[player]?.inspection()

    /** Registers a redacted observer for future events from every menu session. */
    public fun observe(observer: MenuObserver): MenuRegistration {
        observers += observer
        return MenuRegistration { observers.remove(observer) }
    }

    /** Registers a synchronous policy applied to future current-revision interactions. */
    public fun intercept(interceptor: MenuInterceptor): MenuRegistration {
        interceptors += interceptor
        return MenuRegistration { interceptors.remove(interceptor) }
    }

    /** Registers one durable transaction owner and starts resolution of its pending journal entries. */
    public fun registerTransactionDomain(domain: MenuTransactionDomain): MenuRegistration {
        val registration = requireDurableRuntime().register(domain)
        return MenuRegistration(registration::close)
    }

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
        lateinit var session: MenuSessionCore
        session = MenuSessionCore(player, host, requireScope(), requireDurableRuntime(), choice, content,
            observers = { observers.toList() },
            interceptors = { interceptors.toList() },
            reportFailure = ::reportFailure,
        ) {
            sessions.remove(player, session)
        }
        sessions[player]?.let { existing ->
            existing.close(MenuClose.Replaced)
            existing.closeNativeAndAwait()
        }
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
        if (existing != null) {
            existing.close(MenuClose.Replaced)
            existing.closeNativeAndAwait()
        }
        lateinit var session: MenuSessionCore
        session = MenuSessionCore(
            player,
            requireHosts().create(player),
            requireScope(),
            requireDurableRuntime(),
            choice,
            content,
            observers = { observers.toList() },
            interceptors = { interceptors.toList() },
            reportFailure = ::reportFailure,
        ) {
            sessions.remove(player, session)
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

    private fun requireDurableRuntime(): MenuDurableTransactionRuntime =
        checkNotNull(durableRuntime) { "PlayerMenus is not running" }

    private fun reportFailure(cause: Throwable) {
        plugin?.logger?.log(Level.SEVERE, "A menu session failed", cause)
    }
}

internal class MenuSessionCore(
    private val player: PlayerRef,
    private val nativeHost: MenuNativeHost,
    parentScope: CoroutineScope,
    private val transactions: MenuDurableTransactionRuntime,
    private val choice: CompletableDeferred<Any?>?,
    private val content: context(MenuScope) () -> Unit,
    private val observers: () -> List<MenuObserver> = { emptyList() },
    private val interceptors: () -> List<MenuInterceptor> = { emptyList() },
    private val reportFailure: (Throwable) -> Unit = {},
    private val onClosed: () -> Unit,
) {
    private val ownerScope: CoroutineScope = parentScope
    private val job = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + job)
    private val invalidations = Channel<Unit>(Channel.CONFLATED)
    private val invalidationQueued = AtomicBoolean(false)
    private val invalidationVersion = AtomicLong(0)
    private val renderMutex = Mutex()
    private val presentationMutex = Mutex()
    private val nativeCloseMutex = Mutex()
    private var nativeClosed: Boolean = false
    private val closed = CompletableDeferred<MenuClose>()
    private val boundaryFailures: MutableMap<BoundaryIdentity, MenuFailure> =
        Collections.synchronizedMap(linkedMapOf())
    private val navigationStates: MutableMap<ComponentIdentity, NavigationState<Any>> =
        ConcurrentHashMap()
    private val stateStore = MenuStateStore(::invalidate)
    private val actionJobs: ConcurrentHashMap<MenuActionJobIdentity, MutableSet<Job>> = ConcurrentHashMap()
    private val transactionJobs: ConcurrentHashMap<String, Job> = ConcurrentHashMap()
    private val activeEffects: ConcurrentHashMap<EffectIdentity, ActiveEffect> = ConcurrentHashMap()
    private val trace = MenuTraceBuffer()
    private var renderLoop: Job? = null
    private var committed: RenderTree? = null
    private var snapshot: MenuRenderSnapshot? = null
    private var presentationSuspended: Boolean = false

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
        record(
            MenuTrace.GestureReceived(
                interaction.revision,
                interaction.gesture.kind,
                interaction.hostSlots,
                interaction.playerSlots,
            ),
        )
        for (interceptor in interceptors()) {
            when (val decision = runCatching { interceptor.intercept(interaction) }
                .getOrElse { cause ->
                    containFailure(null, ComponentIdentity(listOf("interceptor")), cause)
                    return MenuDispatch.Closed
                }) {
                MenuInterception.Allow -> Unit
                is MenuInterception.Reject -> {
                    decision.feedback?.let { value ->
                        val theme = interaction.slot
                            ?.let { slot -> committed?.host?.slots?.get(slot)?.locals?.get(MenuFeedbackThemeLocal) }
                            as? MenuFeedbackTheme ?: DefaultMenuFeedbackTheme
                        scope.launch { nativeHost.feedback(value, theme.present(value)) }
                        record(MenuTrace.Feedback(value.severity, value.targetSlot))
                    }
                    record(MenuTrace.GestureIntercepted(interaction.gesture.kind))
                    return MenuDispatch.Intercepted
                }
            }
        }
        val tree = committed ?: return MenuDispatch.Closed
        val slot = interaction.slot?.let(tree.host.slots::get)
        val action = slot?.actions?.get(interaction.gesture.kind) ?: slot?.anyGesture
        if (action != null) return dispatchAction(action, interaction)
        return dispatchStorage(tree, interaction)
    }

    fun dispatch(input: MenuHostInput): MenuDispatch {
        if (closed.isCompleted) return MenuDispatch.Closed
        val currentSnapshot = snapshot ?: return MenuDispatch.Closed
        if (input.revision != currentSnapshot.revision) return MenuDispatch.StaleRevision
        val action = committed?.hostActions?.get(input.kind) ?: return MenuDispatch.UnsupportedHostInput
        return dispatchAction(
            identity = action.identity,
            concurrency = action.concurrency,
            boundary = action.boundary,
            feedbackTheme = action.feedbackTheme,
        ) {
            action.handler(this, input)
        }
    }

    private fun dispatchAction(
        action: MenuActionDeclaration,
        interaction: MenuInteraction,
    ): MenuDispatch = dispatchAction(
        identity = action.identity,
        concurrency = action.concurrency,
        boundary = action.boundary,
        feedbackTheme = action.feedbackTheme,
    ) {
        action.handler(this, interaction)
    }

    private fun dispatchAction(
        identity: MenuActionJobIdentity,
        concurrency: MenuActionConcurrency,
        boundary: BoundaryIdentity?,
        feedbackTheme: MenuFeedbackTheme,
        handler: suspend MenuActionScope.() -> Unit,
    ): MenuDispatch {
        val running = actionJobs[identity].orEmpty().filter(Job::isActive)
        when (concurrency) {
            MenuActionConcurrency.SINGLE_FLIGHT -> if (running.isNotEmpty()) return MenuDispatch.AlreadyRunning
            MenuActionConcurrency.RESTART_LATEST -> running.forEach(Job::cancel)
            MenuActionConcurrency.PARALLEL -> Unit
        }
        record(MenuTrace.ActionStarted(identity.toString()))
        val actionJob = scope.launch {
            try {
                handler(ActionScope(feedbackTheme))
                record(MenuTrace.ActionCompleted(identity.toString()))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (cause: Throwable) {
                containFailure(boundary, identity.component, cause)
            }
        }
        actionJobs.computeIfAbsent(identity) { ConcurrentHashMap.newKeySet() }.add(actionJob)
        actionJob.invokeOnCompletion {
            actionJobs[identity]?.let { jobs ->
                jobs.remove(actionJob)
                if (jobs.isEmpty()) actionJobs.remove(identity, jobs)
            }
        }
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
                val transactionId = plan.proposal.id.toString()
                val work = transactions.submit(
                    sessionScope = scope,
                    session = this@MenuSessionCore,
                    proposal = plan.proposal,
                    storages = participants,
                    nativeCommit = { transaction -> nativeHost.commitTransaction(transaction) },
                ) { submission ->
                    when (submission) {
                        is MenuTransactionSubmission.Committed -> {
                            record(MenuTrace.TransactionCommitted(submission.transaction.id.toString()))
                            invalidate()
                        }
                        is MenuTransactionSubmission.Rejected -> {
                            val feedback = MenuFeedback(submission.message, MenuFeedbackSeverity.REJECTION)
                            val theme = interaction.slot
                                ?.let { slot -> tree.host.slots[slot]?.locals?.get(MenuFeedbackThemeLocal) }
                                as? MenuFeedbackTheme ?: DefaultMenuFeedbackTheme
                            nativeHost.feedback(feedback, theme.present(feedback))
                            record(MenuTrace.TransactionRejected("domain-rejected"))
                            record(MenuTrace.Feedback(feedback.severity, feedback.targetSlot))
                        }
                        is MenuTransactionSubmission.Failed -> record(
                            MenuTrace.TransactionRejected(submission.failure.toString()),
                        )
                    }
                }
                val transactionJob = work.job
                transactionJobs[transactionId] = transactionJob
                transactionJob.invokeOnCompletion { transactionJobs.remove(transactionId, transactionJob) }
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
        record(MenuTrace.Closed(reason))
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
            actionJobs.filterValues { jobs -> jobs.any(Job::isActive) }.keys.map(Any::toString),
            transactionJobs.filterValues(Job::isActive).keys.toList(),
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
        val startingInvalidation = invalidationVersion.get()
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
            fail(cause)
            return@withLock
        }
        val next = tree.snapshot((snapshot?.revision ?: 0L) + 1L, stateStore, navigationStates)
        val change = reconcile(snapshot, next)
        if (change is MenuReconciliation.Remount) {
            transactionJobs.values.filter(Job::isActive).joinAll()
            if (closed.isCompleted || invalidationVersion.get() != startingInvalidation) return@withLock
        }
        if (!presentationSuspended) {
            try {
                if (snapshot == null) {
                    nativeHost.mount(next, NativeCallbacks())
                } else {
                    nativeHost.reconcile(next, change)
                }
            } catch (cause: Throwable) {
                val boundary = tree.host.host.boundary
                if (boundary != null && boundary !in boundaryFailures) {
                    containFailure(boundary, tree.host.host.owner, cause)
                } else {
                    fail(cause)
                }
                return@withLock
            }
        }
        committed = tree
        val navigationChanged = snapshot?.navigation != next.navigation
        snapshot = next
        record(MenuTrace.RenderCommitted(next.revision, change))
        if (navigationChanged) record(MenuTrace.NavigationChanged(next.navigation))
        if (presentationSuspended) {
            reconcilePersistentEffects(tree.effects)
        } else {
            reconcileEffects(tree.effects)
        }
    }

    private fun reconcileEffects(next: Map<EffectIdentity, EffectDeclaration>) {
        (activeEffects.keys - next.keys).forEach { identity ->
            activeEffects.remove(identity)?.let(::disposeEffect)
        }
        next.forEach { (identity, declaration) ->
            if (!activeEffects.containsKey(identity)) activeEffects[identity] = startEffect(declaration)
        }
    }

    private fun reconcilePersistentEffects(next: Map<EffectIdentity, EffectDeclaration>) {
        val persistent = next.filterValues(EffectDeclaration::persistsWhilePresentationIsSuspended)
        activeEffects.filterValues(ActiveEffect::persistentWhileSuspended).keys
            .minus(persistent.keys)
            .forEach { identity -> activeEffects.remove(identity)?.let(::disposeEffect) }
        persistent.forEach { (identity, declaration) ->
            if (!activeEffects.containsKey(identity)) activeEffects[identity] = startEffect(declaration)
        }
    }

    private fun startEffect(declaration: EffectDeclaration): ActiveEffect {
        record(MenuTrace.EffectStarted(declaration.identity.toString()))
        return when (declaration) {
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
        is EffectDeclaration.Collection<*> -> ActiveEffect(
            declaration.identity,
            declaration.boundary,
            declaration.persistsWhilePresentationIsSuspended,
            job = scope.launch {
                try {
                    declaration.flow.collect { value ->
                        @Suppress("UNCHECKED_CAST")
                        (declaration.emit as (Any?) -> Unit)(value)
                        if (value is MenuStorageSnapshot) {
                            record(MenuTrace.StorageChanged(value.id.toString(), value.revision))
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (cause: Throwable) {
                    containFailure(declaration.boundary, declaration.identity.component, cause)
                }
            },
        )
        }
    }
    private fun disposeEffect(effect: ActiveEffect) {
        try {
            effect.dispose()
            record(MenuTrace.EffectDisposed(effect.identity.toString()))
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
            fail(cause)
            return
        }
        boundaryFailures[boundary] = MenuFailure(component.semantic(), cause)
        invalidate()
    }

    private inner class ActionScope(private val feedbackTheme: MenuFeedbackTheme) : MenuActionScope {
        override fun feedback(value: MenuFeedback) {
            scope.launch { nativeHost.feedback(value, feedbackTheme.present(value)) }
            record(MenuTrace.Feedback(value.severity, value.targetSlot))
        }

        override fun close(reason: MenuClose) {
            this@MenuSessionCore.close(reason)
        }

        override fun finish(value: Any) {
            val destination = choice ?: error("finish(value) requires PlayerMenus.choose")
            if (destination.complete(value)) close(MenuClose.Explicit)
        }

        override suspend fun <T> withFocusedInput(block: suspend () -> T): T =
            this@MenuSessionCore.withFocusedInput(block)
    }

    private suspend fun <T> withFocusedInput(block: suspend () -> T): T {
        presentationMutex.lock()
        try {
            renderMutex.withLock {
                check(!closed.isCompleted) { "The menu session is closed" }
                presentationSuspended = true
                val visibilityOwned = activeEffects.filterValues { effect -> !effect.persistentWhileSuspended }
                visibilityOwned.values.forEach(::disposeEffect)
                activeEffects.keys.removeAll(visibilityOwned.keys)
                nativeCloseMutex.withLock { nativeHost.suspendPresentation() }
                record(MenuTrace.PresentationSuspended)
            }
            return block()
        } finally {
            try {
                if (!closed.isCompleted) {
                    renderNow()
                    renderMutex.withLock {
                        if (!closed.isCompleted) {
                            val current = requireNotNull(snapshot)
                            nativeHost.mount(current, NativeCallbacks())
                            presentationSuspended = false
                            committed?.effects?.let(::reconcileEffects)
                            record(MenuTrace.PresentationRestored)
                        }
                    }
                }
            } catch (cause: Throwable) {
                fail(cause)
                throw cause
            } finally {
                presentationMutex.unlock()
            }
        }
    }

    private inner class NativeCallbacks : MenuNativeCallbacks {
        override fun dispatch(interaction: MenuInteraction): MenuDispatch = this@MenuSessionCore.dispatch(interaction)

        override fun dispatch(input: MenuHostInput): MenuDispatch = this@MenuSessionCore.dispatch(input)

        override fun closed(reason: MenuNativeClose) {
            val navigation = committed?.nativeCloseNavigation
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

    private fun record(event: MenuTrace) {
        trace.add(event)
        val observation = MenuObservation(player, event)
        observers().forEach { observer -> runCatching { observer.observe(observation) } }
    }

    private fun fail(cause: Throwable) {
        runCatching { reportFailure(cause) }
        close(MenuClose.Failed(cause))
    }
}

private class ActiveEffect(
    val identity: EffectIdentity,
    val boundary: BoundaryIdentity?,
    val persistentWhileSuspended: Boolean = false,
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
): MenuRenderSnapshot {
    val slots = host.slots.values.sortedBy(RenderedSlot::index).map { slot ->
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
    }
    return MenuRenderSnapshot(
        revision,
        host.host.snapshot(slots),
        components.map(ComponentIdentity::semantic).toSet(),
        states.snapshot(),
        navigation.values.flatMap { state -> state.routes.map(::displayKey) },
        storages.keys.mapTo(linkedSetOf(), dev.placeholder.framework.menus.storage.MenuStorageReference::Storage) +
            playerInventory.mapTo(linkedSetOf(), dev.placeholder.framework.menus.storage.MenuStorageReference::Player),
        storages.mapValues { (_, storage) -> storage.snapshots.value },
        transferRoutes,
        hostActions.keys,
    )
}

private fun reconcile(
    before: MenuRenderSnapshot?,
    after: MenuRenderSnapshot,
): MenuReconciliation {
    if (before == null || before.host::class != after.host::class || before.host.capacity != after.host.capacity) {
        return MenuReconciliation.Remount(before?.host, after.host)
    }
    val oldSlots = before.host.slots.associateBy(MenuSlotSnapshot::index)
    val newSlots = after.host.slots.associateBy(MenuSlotSnapshot::index)
    val changed = (oldSlots.keys + newSlots.keys).filterTo(linkedSetOf()) { oldSlots[it] != newSlots[it] }
    return MenuReconciliation.Update(
        titleChanged = before.host.title != after.host.title,
        changedSlots = changed,
        propertiesChanged = before.host != after.host && before.host.title == after.host.title && changed.isEmpty(),
    )
}
