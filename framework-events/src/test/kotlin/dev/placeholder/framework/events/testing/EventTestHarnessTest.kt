package dev.placeholder.framework.events.testing

import dev.placeholder.framework.di.DependencyGraph
import dev.placeholder.framework.events.await
import dev.placeholder.framework.events.codegen.GeneratedEventFixture
import dev.placeholder.framework.events.codegen.TestEvent
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.bukkit.event.Cancellable
import org.bukkit.event.Event
import org.bukkit.event.HandlerList
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class EventTestHarnessTest {
    @Test
    fun `dispatch runs generated handlers and observers`() = runTest {
        val graph = DependencyGraph(javaClass.classLoader)
        val harness = EventTestHarness(graph)

        val result = harness.dispatch(TestEvent())

        result.checkSuccessful()
        assertEquals(1, graph.get(GeneratedEventFixture::class).calls)
        assertEquals(1, graph.get(GeneratedEventFixture::class).observations)
        harness.close()
        graph.close()
    }

    @Test
    fun `temporal query uses the same in-memory dispatch`() = runTest {
        val graph = DependencyGraph(javaClass.classLoader)
        val harness = EventTestHarness(graph)
        val pending = async(start = CoroutineStart.UNDISPATCHED) {
            harness.serverEvents.await<HarnessEvent, String> { value }
        }

        harness.dispatch(HarnessEvent("answer"))

        assertEquals("answer", pending.await())
        harness.close()
        graph.close()
    }

    private class HarnessEvent(val value: String) : Event(), Cancellable {
        private var cancelled = false

        override fun isCancelled(): Boolean = cancelled

        override fun setCancelled(cancelled: Boolean) {
            this.cancelled = cancelled
        }

        override fun getHandlers(): HandlerList = HANDLERS

        companion object {
            private val HANDLERS = HandlerList()
        }
    }
}
