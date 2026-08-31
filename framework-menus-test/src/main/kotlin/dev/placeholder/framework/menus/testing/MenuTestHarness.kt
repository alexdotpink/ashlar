@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.placeholder.framework.menus.testing

import dev.placeholder.framework.execution.PlayerRef
import dev.placeholder.framework.menus.ChestHostSnapshot
import dev.placeholder.framework.menus.MenuClose
import dev.placeholder.framework.menus.MenuDispatch
import dev.placeholder.framework.menus.MenuFeedback
import dev.placeholder.framework.menus.MenuGesture
import dev.placeholder.framework.menus.MenuHostSnapshot
import dev.placeholder.framework.menus.MenuInspection
import dev.placeholder.framework.menus.MenuInteraction
import dev.placeholder.framework.menus.MenuNativeCallbacks
import dev.placeholder.framework.menus.MenuNativeClose
import dev.placeholder.framework.menus.MenuNativeHost
import dev.placeholder.framework.menus.MenuOpenConflict
import dev.placeholder.framework.menus.MenuReconciliation
import dev.placeholder.framework.menus.MenuRenderSnapshot
import dev.placeholder.framework.menus.MenuScope
import dev.placeholder.framework.menus.PlayerMenus
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
        content: context(MenuScope) () -> Unit,
    ): MenuTestSession {
        val host = TestNativeHost()
        hosts[player] = host
        val core = menus.startTesting(player, host, content = content)
        testScope.runCurrent()
        return MenuTestSession(player, core, host, testScope).also(sessions::add)
    }

    /** Starts a typed choice without waiting, so tests can drive its gestures. */
    public suspend fun <T> choose(
        player: PlayerRef = player("Alex"),
        content: context(MenuScope) () -> Unit,
    ): MenuTestChoice<T> {
        val host = TestNativeHost()
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

    /** Advances virtual time and runs work made ready by that interval. */
    public fun advanceTimeBy(duration: Duration) {
        testScope.advanceTimeBy(duration.inWholeMilliseconds)
        testScope.runCurrent()
    }

    /** Fails if any test session still has running actions or effects. */
    public fun assertNoPendingWork() {
        sessions.forEach(MenuTestSession::assertNoPendingWork)
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
        get() = requireNotNull(host.render) { "The test menu has no committed render" }

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
                gesture = gesture,
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

    /** Verifies chest title and dimensions through a caller assertion. */
    public fun assertChest(assertion: ChestHostSnapshot.() -> Unit) {
        chest.assertion()
    }

    /** Verifies the newest committed revision. */
    public fun assertRevision(expected: Long) {
        if (render.revision != expected) {
            throw AssertionError("Expected revision $expected but was ${render.revision}")
        }
    }

    /** Verifies that no action or effect remains active. */
    public fun assertNoPendingWork() {
        val inspection = core.inspection() ?: return
        if (inspection.pendingActions.isNotEmpty()) {
            throw AssertionError("Menu still has pending actions: ${inspection.pendingActions}")
        }
    }

    /** Produces a stable semantic text snapshot without item payloads. */
    public fun semanticSnapshot(): String = buildString {
        append("revision=").append(render.revision).append('\n')
        append("chest rows=").append(chest.rows).append(" slots=")
        append(chest.slots.joinToString { "${it.index}@${it.owner}" }).append('\n')
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

internal class TestNativeHost : MenuNativeHost {
    var render: MenuRenderSnapshot? = null
    var callbacks: MenuNativeCallbacks? = null
    val reconciliations: MutableList<MenuReconciliation> = mutableListOf()
    val feedback: MutableList<MenuFeedback> = mutableListOf()
    var closeCalls: Int = 0

    override suspend fun mount(render: MenuRenderSnapshot, callbacks: MenuNativeCallbacks) {
        this.render = render
        this.callbacks = callbacks
    }

    override suspend fun reconcile(render: MenuRenderSnapshot, change: MenuReconciliation) {
        this.render = render
        reconciliations += change
    }

    override suspend fun close() {
        closeCalls++
    }

    override suspend fun feedback(value: MenuFeedback) {
        feedback += value
    }

    fun dispatch(interaction: MenuInteraction): MenuDispatch =
        requireNotNull(callbacks).dispatch(interaction)

    fun nativeClose(reason: MenuNativeClose) {
        requireNotNull(callbacks).closed(reason)
    }
}
