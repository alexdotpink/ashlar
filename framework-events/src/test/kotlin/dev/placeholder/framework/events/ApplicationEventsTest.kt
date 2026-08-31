package dev.placeholder.framework.events

import dev.placeholder.framework.di.DependencyGraph
import java.lang.reflect.Proxy
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.bukkit.plugin.Plugin
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ApplicationEventsTest {
    @Test
    fun `publish invokes ordinary suspend and polymorphic handlers`() = runTest {
        fixture().use { fixture ->
            fixture.events.publish(ConcreteEvent("home"))

            assertEquals(setOf("base:home", "concrete:home"), fixture.target.calls.toSet())
        }
    }

    @Test
    fun `publish waits for siblings and aggregates handler failures`() = runTest {
        fixture().use { fixture ->
            val failure = assertFailsWith<ApplicationEventException> {
                fixture.events.publish(ConcreteEvent("fail"))
            }

            assertEquals(1, failure.failures.size)
            assertTrue("base:fail" in fixture.target.calls)
            assertTrue("concrete:fail" in fixture.target.calls)
        }
    }

    @Test
    fun `application stream accepts polymorphic events without replay`() = runTest {
        fixture().use { fixture ->
            val received = async(start = CoroutineStart.UNDISPATCHED) {
                fixture.events.stream<BaseEvent>(
                    capacity = 1,
                    overflow = BufferOverflow.SUSPEND,
                ).first()
            }
            yield()

            fixture.events.publish(ConcreteEvent("stream"))

            assertEquals("stream", (received.await() as ConcreteEvent).value)
        }
    }

    private fun fixture(): Fixture {
        val plugin = Proxy.newProxyInstance(
            javaClass.classLoader,
            arrayOf(Plugin::class.java),
        ) { _, method, _ -> defaultValue(method.returnType) } as Plugin
        val graph = DependencyGraph(javaClass.classLoader)
        val events = ApplicationEvents(plugin, graph)
        return Fixture(events, graph.get(ApplicationEventFixture::class), graph)
    }

    private class Fixture(
        val events: ApplicationEvents,
        val target: ApplicationEventFixture,
        private val graph: DependencyGraph,
    ) : AutoCloseable {
        override fun close() {
            events.close()
            graph.close()
        }
    }

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

internal interface BaseEvent : ApplicationEvent {
    val value: String
}

internal data class ConcreteEvent(override val value: String) : BaseEvent

@Events
internal class ApplicationEventFixture {
    val calls = CopyOnWriteArrayList<String>()

    @OnApplication
    internal fun BaseEvent.recordBase() {
        calls += "base:$value"
    }

    @OnApplication
    internal suspend fun ConcreteEvent.recordConcrete() {
        calls += "concrete:$value"
        if (value == "fail") error("expected")
    }
}
