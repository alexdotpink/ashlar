@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.placeholder.framework.menus.testing

import dev.placeholder.framework.execution.PlayerRef
import dev.placeholder.framework.items.ItemSnapshot
import dev.placeholder.framework.menus.ChestHostSnapshot
import dev.placeholder.framework.menus.MenuClose
import dev.placeholder.framework.menus.MenuDispatch
import dev.placeholder.framework.menus.MenuFeedback
import dev.placeholder.framework.menus.MenuGesture
import dev.placeholder.framework.menus.MenuHostInput
import dev.placeholder.framework.menus.MenuHostSnapshot
import dev.placeholder.framework.menus.MenuInspection
import dev.placeholder.framework.menus.MenuInteraction
import dev.placeholder.framework.menus.MenuNativeCallbacks
import dev.placeholder.framework.menus.MenuNativeClose
import dev.placeholder.framework.menus.MenuNativeHost
import dev.placeholder.framework.menus.MenuInterceptor
import dev.placeholder.framework.menus.MenuObserver
import dev.placeholder.framework.menus.MenuRegistration
import dev.placeholder.framework.menus.PlayerInventorySlot
import dev.placeholder.framework.menus.MenuOpenConflict
import dev.placeholder.framework.menus.MenuReconciliation
import dev.placeholder.framework.menus.MenuRenderSnapshot
import dev.placeholder.framework.menus.MenuScope
import dev.placeholder.framework.menus.PlayerMenus
import dev.placeholder.framework.menus.storage.MenuDragMode
import dev.placeholder.framework.menus.storage.MenuNativeCommit
import dev.placeholder.framework.menus.storage.MenuNativeTransaction
import dev.placeholder.framework.menus.storage.MenuStorageId
import dev.placeholder.framework.menus.storage.PlayerInventorySection
import java.nio.charset.StandardCharsets
import java.util.UUID
import kotlin.time.Duration
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CompletableDeferred

/** Runs one deterministic server-free menu scenario with virtual time. */
public fun menuTest(block: suspend MenuTestScope.() -> Unit): TestResult = runTest {
    val menus = MenuTestScope(this)
    try {
        menus.block()
        runCurrent()
        menus.assertNoPendingWork()
    } finally {
        menus.close()
    }
}

/** Production menu runtime backed by deterministic semantic hosts. */
public class MenuTestScope internal constructor(
    private val testScope: TestScope,
) : AutoCloseable {
    private val hosts: MutableMap<PlayerRef, TestNativeHost> = linkedMapOf()
    private val sessions: MutableList<MenuTestSession> = mutableListOf()
    private val menus = PlayerMenus(testScope) { player ->
        TestNativeHost().also { hosts[player] = it }
    }

    /** Creates a stable player reference for a readable test name. */
    public fun player(name: String): PlayerRef {
        require(name.isNotBlank()) { "A test player name cannot be blank" }
        return PlayerRef(
            UUID.nameUUIDFromBytes("framework-menu:$name".toByteArray(StandardCharsets.UTF_8)),
        )
    }

    /** Starts a menu without waiting for its logical session to close. */
    public suspend fun open(
        player: PlayerRef = player("Alex"),
        playerInventory: Map<PlayerInventorySection, List<ItemSnapshot?>> = emptyMap(),
        cursor: ItemSnapshot? = null,
        content: context(MenuScope) () -> Unit,
    ): MenuTestSession {
        val host = TestNativeHost(playerInventory, cursor)
        hosts[player] = host
        val core = menus.startTesting(player, host, content = content)
        testScope.runCurrent()
        return MenuTestSession(player, core, host, testScope).also(sessions::add)
    }

    /** Starts a typed choice without waiting, so tests can drive its gestures. */
    public suspend fun <T> choose(
        player: PlayerRef = player("Alex"),
        playerInventory: Map<PlayerInventorySection, List<ItemSnapshot?>> = emptyMap(),
        cursor: ItemSnapshot? = null,
        content: context(MenuScope) () -> Unit,
    ): MenuTestChoice<T> {
        val host = TestNativeHost(playerInventory, cursor)
        val selected = CompletableDeferred<Any?>()
        hosts[player] = host
        val core = menus.startTesting(player, host, selected, content)
        testScope.runCurrent()
        val session = MenuTestSession(player, core, host, testScope).also(sessions::add)
        return MenuTestChoice(session, selected)
    }

    /** Runs all work currently ready on the virtual scheduler. */
    public fun runCurrent() {
        testScope.runCurrent()
    }

    /** Registers a production observer for sessions opened by this harness. */
    public fun observe(observer: MenuObserver): MenuRegistration = menus.observe(observer)

    /** Registers a production interaction interceptor for sessions opened by this harness. */
    public fun intercept(interceptor: MenuInterceptor): MenuRegistration = menus.intercept(interceptor)

    /** Advances virtual time and runs work made ready by that interval. */
    public fun advanceTimeBy(duration: Duration) {
        testScope.advanceTimeBy(duration.inWholeMilliseconds)
        testScope.runCurrent()
    }

    /** Fails if any test session still has pending work. */
    public fun assertNoPendingWork(includeEffects: Boolean = false) {
        sessions.forEach { session -> session.assertNoPendingWork(includeEffects) }
    }

    override fun close() {
        menus.close()
        testScope.runCurrent()
    }
}

/** One controllable logical session driven through immutable interactions. */
public class MenuTestSession internal constructor(
    public val player: PlayerRef,
    private val core: dev.placeholder.framework.menus.MenuSessionCore,
    private val host: TestNativeHost,
    private val testScope: TestScope,
) {
    /** The newest committed semantic render. */
    public val render: MenuRenderSnapshot
        get() = core.inspection()?.snapshot
            ?: requireNotNull(host.render) { "The test menu has no committed render" }

    /** The current chest model, failing when another host kind is active. */
    public val chest: ChestHostSnapshot
        get() = (render.host as? MenuHostSnapshot.Chest)?.chest
            ?: error("The active test host is not a chest")

    /** Dispatches a gesture against [slot] and runs ready action work. */
    public fun gesture(
        slot: Int,
        gesture: MenuGesture,
    ): MenuDispatch {
        val result = host.dispatch(
            MenuInteraction(
                player = player,
                revision = render.revision,
                slot = slot,
                playerInventory = host.playerInventorySnapshot(),
                gesture = gesture,
                clicked = render.host.slots.firstOrNull { candidate -> candidate.index == slot }?.storedItem,
                cursor = host.cursor,
            ),
        )
        testScope.runCurrent()
        return result
    }

    /** Dispatches a primary click by physical slot. */
    public fun primaryClick(slot: Int): MenuDispatch = gesture(slot, MenuGesture.Primary)

    /** Dispatches a primary click by chest row and column. */
    public fun primaryClick(row: Int, column: Int): MenuDispatch = primaryClick(row * 9 + column)

    /** Dispatches a secondary click by physical slot. */
    public fun secondaryClick(slot: Int): MenuDispatch = gesture(slot, MenuGesture.Secondary)

    /** Dispatches a shift-primary transfer from one host slot. */
    public fun shiftClick(slot: Int): MenuDispatch = gesture(slot, MenuGesture.ShiftPrimary)

    /** Dispatches a gesture from one symbolic player-inventory slot. */
    public fun playerGesture(
        section: PlayerInventorySection,
        index: Int,
        gesture: MenuGesture,
    ): MenuDispatch {
        val playerSlot = PlayerInventorySlot(section, index)
        val result = host.dispatch(
            MenuInteraction(
                player = player,
                revision = render.revision,
                slot = null,
                playerSlot = playerSlot,
                playerInventory = host.playerInventorySnapshot(),
                gesture = gesture,
                clicked = host.playerItem(section, index),
                cursor = host.cursor,
            ),
        )
        testScope.runCurrent()
        return result
    }

    /** Dispatches a shift-primary transfer from one player-inventory slot. */
    public fun shiftClick(section: PlayerInventorySection, index: Int): MenuDispatch =
        playerGesture(section, index, MenuGesture.ShiftPrimary)

    /** Dispatches one ordered drag across host and player slots. */
    public fun drag(
        hostSlots: List<Int> = emptyList(),
        playerSlots: List<PlayerInventorySlot> = emptyList(),
        mode: MenuDragMode = MenuDragMode.EVEN,
    ): MenuDispatch {
        val result = host.dispatch(
            MenuInteraction(
                player = player,
                revision = render.revision,
                slot = null,
                hostSlots = hostSlots,
                playerSlots = playerSlots,
                playerInventory = host.playerInventorySnapshot(),
                gesture = MenuGesture.Drag(mode),
                cursor = host.cursor,
            ),
        )
        testScope.runCurrent()
        return result
    }

    /** Dispatches one typed non-slot input through the production native callback seam. */
    public fun hostInput(input: MenuHostInput): MenuDispatch {
        val result = host.dispatch(input)
        testScope.runCurrent()
        return result
    }

    /** Reports a native close through the production callback seam. */
    public fun nativeClose(reason: MenuNativeClose = MenuNativeClose.PLAYER) {
        host.nativeClose(reason)
        testScope.runCurrent()
    }

    /** Explicitly closes the logical test session. */
    public fun close(reason: MenuClose = MenuClose.Explicit) {
        core.close(reason)
        testScope.runCurrent()
    }

    /** Returns the production redacted semantic inspection. */
    public fun inspect(): MenuInspection = requireNotNull(core.inspection())

    /** Returns every reconciliation after the initial mount. */
    public fun reconciliations(): List<MenuReconciliation> = host.reconciliations.toList()

    /** Returns typed feedback in delivery order. */
    public fun feedback(): List<MenuFeedback> = host.feedback.toList()

    /** Number of native close operations completed by the fake host. */
    public fun nativeCloseCalls(): Int = host.closeCalls

    /** Number of focused-input presentation suspensions completed by the fake host. */
    public fun presentationSuspensions(): Int = host.suspensionCalls

    /** Whether the fake native presentation is currently mounted. */
    public val isPresented: Boolean
        get() = host.mounted

    /** Current logical cursor after accepted storage gestures. */
    public val cursor: ItemSnapshot?
        get() = host.cursor

    /** Returns one fake player-inventory item. */
    public fun playerItem(section: PlayerInventorySection, index: Int): ItemSnapshot? =
        host.playerItem(section, index)

    /** Verifies one declared menu storage value. */
    public fun assertStorageItem(
        storage: MenuStorageId,
        index: Int,
        expected: ItemSnapshot?,
    ) {
        val actual = render.storages.getValue(storage)[index]
        if (actual != expected) throw AssertionError("Expected $storage[$index] to be $expected but was $actual")
    }

    /** Verifies exact item quantity conservation across storage, player inventory, cursor, and emissions. */
    public fun assertNoItemCreationOrLoss() {
        val before = requireNotNull(host.initialItems) { "The host has not mounted its first render" }
        val after = host.currentItems()
        if (before != after) throw AssertionError("Item quantities changed: before=$before after=$after")
    }

    /** Verifies chest title and dimensions through a caller assertion. */
    public fun assertChest(assertion: ChestHostSnapshot.() -> Unit) {
        chest.assertion()
    }

    /** Verifies the concrete semantic host type and exposes it to [assertion]. */
    public inline fun <reified H : MenuHostSnapshot> assertHost(assertion: H.() -> Unit) {
        val actual = render.host
        if (actual !is H) {
            throw AssertionError("Expected host ${H::class.simpleName} but was ${actual::class.simpleName}")
        }
        actual.assertion()
    }

    /** Verifies the newest committed revision. */
    public fun assertRevision(expected: Long) {
        if (render.revision != expected) {
            throw AssertionError("Expected revision $expected but was ${render.revision}")
        }
    }

    /** Verifies that no action or transaction remains pending. */
    public fun assertNoPendingWork(includeEffects: Boolean = false) {
        val inspection = core.inspection() ?: return
        if (inspection.pendingActions.isNotEmpty()) {
            throw AssertionError("Menu still has pending actions: ${inspection.pendingActions}")
        }
        if (inspection.pendingTransactions.isNotEmpty()) {
            throw AssertionError("Menu still has pending transactions: ${inspection.pendingTransactions}")
        }
        if (includeEffects && inspection.activeEffects.isNotEmpty()) {
            throw AssertionError("Menu still has active effects: ${inspection.activeEffects}")
        }
    }

    /** Produces a stable semantic text snapshot without item payloads. */
    public fun semanticSnapshot(): String = buildString {
        append("revision=").append(render.revision).append('\n')
        append("host=").append(render.host::class.simpleName)
            .append(" capacity=").append(render.host.capacity).append(" slots=")
        append(render.host.slots.joinToString { "${it.index}@${it.owner}" }).append('\n')
        append("hostInputs=").append(render.hostInputs).append('\n')
        append("states=").append(render.stateCells).append('\n')
        append("navigation=").append(render.navigation)
    }

    /** Waits for the production session's typed close winner. */
    public suspend fun awaitClose(): MenuClose = core.awaitClose()
}

/** Controllable typed-choice session and its eventual selected value. */
public class MenuTestChoice<T> internal constructor(
    public val menu: MenuTestSession,
    private val selected: CompletableDeferred<Any?>,
) {
    /** Waits until a menu action calls `finish(value)`. */
    public suspend fun awaitSelected(): T {
        @Suppress("UNCHECKED_CAST")
        return selected.await() as T
    }
}

internal class TestNativeHost(
    playerInventory: Map<PlayerInventorySection, List<ItemSnapshot?>> = emptyMap(),
    initialCursor: ItemSnapshot? = null,
) : MenuNativeHost {
    var render: MenuRenderSnapshot? = null
    var callbacks: MenuNativeCallbacks? = null
    val reconciliations: MutableList<MenuReconciliation> = mutableListOf()
    val feedback: MutableList<MenuFeedback> = mutableListOf()
    var closeCalls: Int = 0
    var suspensionCalls: Int = 0
    var mounted: Boolean = false
    var cursor: ItemSnapshot? = initialCursor
    var initialItems: Map<String, Int>? = null
    private val playerInventory: MutableMap<PlayerInventorySection, MutableList<ItemSnapshot?>> =
        playerInventory.mapValuesTo(linkedMapOf()) { (section, items) ->
            require(items.size == section.size) { "$section needs ${section.size} slots" }
            items.toMutableList()
        }
    private val emissions: MutableList<ItemSnapshot> = mutableListOf()

    override suspend fun mount(render: MenuRenderSnapshot, callbacks: MenuNativeCallbacks) {
        this.render = render
        this.callbacks = callbacks
        mounted = true
        if (initialItems == null) initialItems = currentItems()
    }

    override suspend fun reconcile(render: MenuRenderSnapshot, change: MenuReconciliation) {
        this.render = render
        reconciliations += change
        mounted = true
    }

    override suspend fun close() {
        closeCalls++
        mounted = false
    }

    override suspend fun suspendPresentation() {
        suspensionCalls++
        mounted = false
    }

    override suspend fun feedback(value: MenuFeedback) {
        feedback += value
    }

    override suspend fun commitTransaction(transaction: MenuNativeTransaction): MenuNativeCommit {
        if (!mounted) return MenuNativeCommit.Unavailable
        transaction.playerStorages.forEach { (section, id) ->
            transaction.committed.snapshots[id]?.slots?.let { slots ->
                playerInventory[section] = slots.toMutableList()
            }
        }
        cursor = transaction.committed.cursor
        transaction.committed.emissions.forEach { emission ->
            when (emission) {
                is dev.placeholder.framework.menus.storage.MenuTransactionEmission.Drop -> emissions += emission.item
            }
        }
        return MenuNativeCommit.Applied
    }

    fun playerInventorySnapshot(): Map<PlayerInventorySection, List<ItemSnapshot?>> =
        playerInventory.mapValues { (_, items) -> items.toList() }

    fun playerItem(section: PlayerInventorySection, index: Int): ItemSnapshot? =
        playerInventory[section]?.get(index)

    fun currentItems(): Map<String, Int> {
        val menuItems = render?.storages?.values.orEmpty()
            .flatMap { storage -> storage.slots }
        return itemQuantities(
            menuItems + playerInventory.values.flatten() + listOf(cursor) + emissions,
        )
    }

    fun dispatch(interaction: MenuInteraction): MenuDispatch =
        requireNotNull(callbacks).dispatch(interaction)

    fun dispatch(input: MenuHostInput): MenuDispatch =
        requireNotNull(callbacks).dispatch(input)

    fun nativeClose(reason: MenuNativeClose) {
        requireNotNull(callbacks).closed(reason)
    }
}

private fun itemQuantities(items: Iterable<ItemSnapshot?>): Map<String, Int> = buildMap {
    items.filterNotNull().forEach { item ->
        val identity = item.withAmount(1).fingerprint()
        put(identity, getOrDefault(identity, 0) + item.amount)
    }
}
