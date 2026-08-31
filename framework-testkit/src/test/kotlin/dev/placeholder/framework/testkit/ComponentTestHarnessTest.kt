package dev.placeholder.framework.testkit

import dev.placeholder.framework.ComponentContext
import dev.placeholder.framework.PluginComponent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ComponentTestHarnessTest {
    @Test
    fun `starts dependencies before consumers and stops in reverse order`(): Unit {
        val events = mutableListOf<String>()
        val harness = componentTest { ParentComponent(events) }

        val component = harness.start()

        assertSame(component, harness.component)
        assertEquals(listOf("database:start", "homes:database-ready=true", "parent:start"), events)

        harness.stop().checkSuccessful()

        assertEquals(
            listOf(
                "database:start",
                "homes:database-ready=true",
                "parent:start",
                "parent:stop",
                "homes:stop",
                "database:stop",
            ),
            events,
        )
        assertFailsWith<IllegalStateException> { harness.component }
    }

    @Test
    fun `cancels component tasks before stop`(): Unit = runBlocking {
        val events = CopyOnWriteArrayList<String>()
        val harness = componentTest { CancellableComponent(events) }
        val component = harness.start()
        component.started.await()

        val result = harness.stop()

        assertTrue(result.tasksDrained)
        assertEquals(listOf("task:cancelled", "component:stop"), events)
        assertTrue(component.job.isCancelled)
    }

    @Test
    fun `reports failures without cancelling an ordinary task sibling`(): Unit = runBlocking {
        val harness = componentTest { FailingTasksComponent() }
        val component = harness.start()
        component.siblingStarted.await()

        component.failure.join()

        assertFalse(component.siblingCancelled.isCompleted)
        val result = harness.stop()
        component.siblingCancelled.await()

        assertEquals("ordinary", result.taskFailures.single().taskName)
        assertFalse(result.taskFailures.single().critical)
        assertTrue(result.criticalFailures.isEmpty())
        assertFailsWith<AssertionError> { result.checkSuccessful() }
    }

    @Test
    fun `records a critical task separately`(): Unit = runBlocking {
        val harness = componentTest { CriticalTaskComponent() }
        val component = harness.start()

        component.failure.join()
        val result = harness.stop()

        assertEquals(1, result.taskFailures.size)
        assertTrue(result.taskFailures.single().critical)
        assertEquals(result.taskFailures, result.criticalFailures)
    }

    @Test
    fun `closes resources after stop in reverse ownership order`(): Unit {
        val events = mutableListOf<String>()
        val harness = componentTest { ResourceComponent(events) }
        harness.start()

        harness.stop().checkSuccessful()

        assertEquals(listOf("stop", "close:second", "close:first"), events)
    }

    @Test
    fun `does not invent Bukkit objects`(): Unit {
        val harness = componentTest { BukkitAccessComponent() }

        val failure = assertFailsWith<UnsupportedOperationException> { harness.start() }

        assertTrue(failure.message.orEmpty().contains("Paper or Folia integration test"))
    }

    private class ParentComponent(
        private val events: MutableList<String>,
    ) : PluginComponent() {
        private val database by component { DatabaseComponent(events) }
        @Suppress("unused")
        private val homes by component { HomesComponent(events, database) }

        override fun ComponentContext.start(): Unit {
            events += "parent:start"
        }

        override fun ComponentContext.stop(): Unit {
            events += "parent:stop"
        }
    }

    private class DatabaseComponent(
        private val events: MutableList<String>,
    ) : PluginComponent() {
        var ready: Boolean = false
            private set

        override fun ComponentContext.start(): Unit {
            ready = true
            events += "database:start"
        }

        override fun ComponentContext.stop(): Unit {
            events += "database:stop"
        }
    }

    private class HomesComponent(
        private val events: MutableList<String>,
        database: DatabaseComponent,
    ) : PluginComponent() {
        init {
            events += "homes:database-ready=${database.ready}"
        }

        override fun ComponentContext.stop(): Unit {
            events += "homes:stop"
        }
    }

    private class CancellableComponent(
        private val events: MutableList<String>,
    ) : PluginComponent() {
        val started = CompletableDeferred<Unit>()
        lateinit var job: Job
            private set

        override fun ComponentContext.start(): Unit {
            job = task("worker") {
                started.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    events += "task:cancelled"
                }
            }
        }

        override fun ComponentContext.stop(): Unit {
            events += "component:stop"
        }
    }

    private class FailingTasksComponent : PluginComponent() {
        val siblingStarted = CompletableDeferred<Unit>()
        val siblingCancelled = CompletableDeferred<Unit>()
        lateinit var failure: Job
            private set

        override fun ComponentContext.start(): Unit {
            task("sibling") {
                siblingStarted.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    siblingCancelled.complete(Unit)
                }
            }
            failure = task("ordinary") { error("boom") }
        }
    }

    private class CriticalTaskComponent : PluginComponent() {
        lateinit var failure: Job
            private set

        override fun ComponentContext.start(): Unit {
            failure = criticalTask("database-loop") { error("connection lost") }
        }
    }

    private class ResourceComponent(
        private val events: MutableList<String>,
    ) : PluginComponent() {
        override fun ComponentContext.start(): Unit {
            own(AutoCloseable { events += "close:first" })
            own(AutoCloseable { events += "close:second" })
        }

        override fun ComponentContext.stop(): Unit {
            events += "stop"
        }
    }

    private class BukkitAccessComponent : PluginComponent() {
        override fun ComponentContext.start(): Unit {
            server.name
        }
    }
}
