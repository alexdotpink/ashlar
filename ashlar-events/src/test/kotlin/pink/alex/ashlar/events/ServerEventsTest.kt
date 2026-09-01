package pink.alex.ashlar.events

import pink.alex.ashlar.di.DependencyGraph
import java.lang.reflect.Proxy
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.bukkit.Server
import org.bukkit.event.Cancellable
import org.bukkit.event.Event
import org.bukkit.event.HandlerList
import org.bukkit.plugin.EventExecutor
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.PluginManager
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ServerEventsTest {
    @Test
    fun `await skips unrelated events and returns one projected value`() = runTest {
        fixture().use { fixture ->
            val pending = async(start = CoroutineStart.UNDISPATCHED) {
                fixture.events.await<TestEvent, String> {
                    if (value != "answer") skip()
                    value.uppercase()
                }
            }

            fixture.fire(TestEvent("other"))
            assertFalse(pending.isCompleted)
            fixture.fire(TestEvent("answer"))

            assertEquals("ANSWER", pending.await())
        }
    }

    @Test
    fun `capture cancels retries and the accepted event`() = runTest {
        fixture().use { fixture ->
            var retries = 0
            val pending = async(start = CoroutineStart.UNDISPATCHED) {
                fixture.events.capture<TestEvent, Int> {
                    value.toIntOrNull() ?: retry { retries++ }
                }
            }

            val invalid = TestEvent("no")
            fixture.fire(invalid)
            yield()
            val accepted = TestEvent("7")
            fixture.fire(accepted)

            assertEquals(7, pending.await())
            assertTrue(invalid.isCancelled)
            assertTrue(accepted.isCancelled)
            assertEquals(1, retries)
        }
    }

    @Test
    fun `stream projects bounded event values`() = runTest {
        fixture().use { fixture ->
            val values = async(start = CoroutineStart.UNDISPATCHED) {
                fixture.events.stream<TestEvent, String>(
                    capacity = 2,
                    overflow = BufferOverflow.DROP_OLDEST,
                ) { value }.take(2).toList()
            }
            yield()

            fixture.fire(TestEvent("one"))
            fixture.fire(TestEvent("two"))

            assertEquals(listOf("one", "two"), values.await())
        }
    }

    private fun fixture(): Fixture {
        val manager = RecordingPluginManager()
        val pluginManager = proxy<PluginManager> { method, _, arguments ->
            when (method) {
                "registerEvent" -> {
                    manager.registrations += Registration(
                        arguments[3] as EventExecutor,
                        arguments[5] as Boolean,
                    )
                    null
                }
                else -> null
            }
        }
        val server = proxy<Server> { method, returnType, _ ->
            if (method == "getPluginManager") pluginManager else defaultValue(returnType)
        }
        val plugin = proxy<Plugin> { method, returnType, _ ->
            if (method == "getServer") server else defaultValue(returnType)
        }
        val graph = DependencyGraph(javaClass.classLoader)
        graph.bind(ServerEventFailureReporter {}, listOf(ServerEventFailureReporter::class))
        return Fixture(ServerEvents(plugin, graph), manager, graph)
    }

    private class Fixture(
        val events: ServerEvents,
        private val manager: RecordingPluginManager,
        private val graph: DependencyGraph,
    ) : AutoCloseable {
        fun fire(event: Event) {
            manager.registrations.toList().forEach { registration ->
                if (!registration.ignoreCancelled || (event as? Cancellable)?.isCancelled != true) {
                    registration.executor.execute(object : org.bukkit.event.Listener {}, event)
                }
            }
        }

        override fun close() {
            graph.close()
        }
    }

    private class RecordingPluginManager {
        val registrations = CopyOnWriteArrayList<Registration>()
    }

    private data class Registration(
        val executor: EventExecutor,
        val ignoreCancelled: Boolean,
    )

    private class TestEvent(val value: String) : Event(), Cancellable {
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

    @Suppress("UNCHECKED_CAST")
    private inline fun <reified T> proxy(
        crossinline invoke: (method: String, returnType: Class<*>, arguments: Array<out Any?>) -> Any?,
    ): T = Proxy.newProxyInstance(
        javaClass.classLoader,
        arrayOf(T::class.java),
    ) { _, method, arguments -> invoke(method.name, method.returnType, arguments.orEmpty()) } as T

    private fun defaultValue(type: Class<*>): Any? = when (type) {
        java.lang.Boolean.TYPE -> false
        java.lang.Byte.TYPE -> 0.toByte()
        java.lang.Short.TYPE -> 0.toShort()
        java.lang.Integer.TYPE -> 0
        java.lang.Long.TYPE -> 0L
        java.lang.Float.TYPE -> 0f
        java.lang.Double.TYPE -> 0.0
        java.lang.Character.TYPE -> '\u0000'
        else -> null
    }
}
