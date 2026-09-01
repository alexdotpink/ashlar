package pink.alex.ashlar.events.internal

import pink.alex.ashlar.events.ServerEventFailure
import pink.alex.ashlar.events.codegen.EventSetContribution
import pink.alex.ashlar.events.codegen.EventSetDefinition
import pink.alex.ashlar.events.codegen.ServerEventHandlerDefinition
import kotlin.reflect.KClass
import kotlin.test.assertEquals
import kotlin.test.assertSame
import org.bukkit.event.Event
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.junit.jupiter.api.Test

class ServerEventHandlerInvokerTest {
    @Test
    fun `reports a handler failure and returns normally`() {
        val cause = IllegalStateException("broken")
        val failures = mutableListOf<ServerEventFailure>()
        val invoker = ServerEventHandlerInvoker(failures::add)

        invoker.invoke(FailingContribution(cause), Target, 0, TestEvent())

        assertEquals(1, failures.size)
        assertEquals("fail", failures.single().handler)
        assertSame(cause, failures.single().cause)
    }

    private data object Target

    private class TestEvent : Event() {
        override fun getHandlers(): HandlerList = HANDLERS

        companion object {
            private val HANDLERS = HandlerList()
        }
    }

    private class FailingContribution(
        private val failure: Throwable,
    ) : EventSetContribution {
        override val targetType: KClass<*> = Target::class
        override val definition: EventSetDefinition = EventSetDefinition(
            listOf(
                ServerEventHandlerDefinition(
                    name = "fail",
                    eventType = TestEvent::class,
                    priority = EventPriority.NORMAL,
                    ignoreCancelled = false,
                ),
            ),
        )

        override fun invoke(target: Any, handler: Int, event: Event) {
            throw failure
        }
    }
}
