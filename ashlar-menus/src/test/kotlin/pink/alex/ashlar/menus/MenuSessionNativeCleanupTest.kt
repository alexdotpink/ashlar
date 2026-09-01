@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package pink.alex.ashlar.menus

import pink.alex.ashlar.execution.PlayerRef
import pink.alex.ashlar.menus.storage.MenuTransactionCoordinator
import pink.alex.ashlar.menus.storage.MenuDurableTransactionRuntime
import pink.alex.ashlar.menus.storage.MenuPlayerSettlement
import pink.alex.ashlar.menus.storage.MenuSettlementResult
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class MenuSessionNativeCleanupTest {
    @Test
    fun `native host cleanup can finish after its parent scope is cancelled`() = runTest {
        val owner = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val host = RecordingHost()
        val session = MenuSessionCore(
            player = PlayerRef(UUID.randomUUID()),
            nativeHost = host,
            parentScope = owner,
            transactions = MenuDurableTransactionRuntime(
                owner,
                MenuTransactionCoordinator(),
                MenuPlayerSettlement { MenuSettlementResult.Pending },
            ),
            choice = null,
            content = {
                chest("Cleanup", rows = 1) {}
            },
            onClosed = {},
        )
        session.start()

        owner.cancel()
        session.close(MenuClose.PluginStopped)
        session.closeNativeAndAwait()
        session.closeNativeAndAwait()

        assertEquals(1, host.closeCount)
    }

    @Test
    fun `closing during native reconciliation does not report cancellation as failure`() = runTest {
        val owner = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val host = BlockingReconciliationHost()
        val values = MutableStateFlow(0)
        val failures = mutableListOf<Throwable>()
        val session = MenuSessionCore(
            player = PlayerRef(UUID.randomUUID()),
            nativeHost = host,
            parentScope = owner,
            transactions = MenuDurableTransactionRuntime(
                owner,
                MenuTransactionCoordinator(),
                MenuPlayerSettlement { MenuSettlementResult.Pending },
            ),
            choice = null,
            content = {
                val value by collectAsState(values, initial = 0)
                chest("Frame $value", rows = 1) {}
            },
            reportFailure = failures::add,
            onClosed = {},
        )
        session.start()
        values.value = 1
        runCurrent()
        host.reconciliationStarted.await()

        session.close(MenuClose.PlayerClosed)
        runCurrent()

        assertEquals(emptyList(), failures)
    }

    private class RecordingHost : MenuNativeHost {
        var closeCount: Int = 0

        override suspend fun mount(render: MenuRenderSnapshot, callbacks: MenuNativeCallbacks) = Unit

        override suspend fun reconcile(render: MenuRenderSnapshot, change: MenuReconciliation) = Unit

        override suspend fun close() {
            closeCount++
        }
    }

    private class BlockingReconciliationHost : MenuNativeHost {
        val reconciliationStarted = CompletableDeferred<Unit>()

        override suspend fun mount(render: MenuRenderSnapshot, callbacks: MenuNativeCallbacks) = Unit

        override suspend fun reconcile(render: MenuRenderSnapshot, change: MenuReconciliation) {
            reconciliationStarted.complete(Unit)
            awaitCancellation()
        }

        override suspend fun close() = Unit
    }
}
