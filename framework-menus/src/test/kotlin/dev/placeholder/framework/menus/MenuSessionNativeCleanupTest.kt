package dev.placeholder.framework.menus

import dev.placeholder.framework.execution.PlayerRef
import dev.placeholder.framework.menus.storage.MenuTransactionCoordinator
import dev.placeholder.framework.menus.storage.MenuDurableTransactionRuntime
import dev.placeholder.framework.menus.storage.MenuPlayerSettlement
import dev.placeholder.framework.menus.storage.MenuSettlementResult
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
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

    private class RecordingHost : MenuNativeHost {
        var closeCount: Int = 0

        override suspend fun mount(render: MenuRenderSnapshot, callbacks: MenuNativeCallbacks) = Unit

        override suspend fun reconcile(render: MenuRenderSnapshot, change: MenuReconciliation) = Unit

        override suspend fun close() {
            closeCount++
        }
    }
}
