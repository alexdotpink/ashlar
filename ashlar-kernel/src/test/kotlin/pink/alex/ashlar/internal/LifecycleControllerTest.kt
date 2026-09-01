package pink.alex.ashlar.internal

import pink.alex.ashlar.ComponentContext
import pink.alex.ashlar.PluginComponent
import pink.alex.ashlar.TaskFailure
import pink.alex.ashlar.di.DependencyGraph
import pink.alex.ashlar.di.DependencyResolver
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import net.kyori.adventure.text.logger.slf4j.ComponentLogger
import org.bukkit.Server
import org.bukkit.plugin.Plugin
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class LifecycleControllerTest {
    @Test
    fun `constructs factories only when the tree starts`(): Unit {
        val constructions = AtomicInteger()
        val controller = controller("cache" to { constructions.incrementAndGet(); RecordingComponent("cache") })

        assertEquals(0, constructions.get())
        controller.startComponents()

        assertEquals(1, constructions.get())
        controller.shutdown(afterTaskDrain = {})
    }

    @Test
    fun `starts children first and stops parents first in reverse declaration order`(): Unit {
        val events: MutableList<String> = mutableListOf()
        val controller =
            controller(
                "first" to { ParentComponent("first", events) },
                "second" to { RecordingComponent("second", events) },
            )

        controller.startComponents()
        controller.shutdown(afterTaskDrain = {})

        assertEquals(
            listOf(
                "start:first.child",
                "start:first",
                "start:second",
                "stop:second",
                "stop:first",
                "stop:first.child",
            ),
            events,
        )
    }

    @Test
    fun `fully starts an earlier sibling before constructing a dependent sibling`(): Unit {
        val parent = DependentSiblingsComponent()
        val controller = controller("parent" to { parent })

        controller.startComponents()

        assertTrue(parent.databaseStartedWhenHomesConstructed)
        controller.shutdown(afterTaskDrain = {})
    }

    @Test
    fun `delegate is unavailable before start and after stop`(): Unit {
        val parent = ExposedChildComponent()
        val controller = controller("parent" to { parent })

        assertFailsWith<IllegalStateException> { parent.childValue() }
        controller.startComponents()
        assertEquals("child", parent.childValue().label)
        controller.shutdown(afterTaskDrain = {})
        assertFailsWith<IllegalStateException> { parent.childValue() }
    }

    @Test
    fun `rolls back started siblings and owned resources when startup fails`(): Unit {
        val events: MutableList<String> = mutableListOf()
        val controller =
            controller(
                "first" to { RecordingComponent("first", events, ownOnStart = true) },
                "failing" to { RecordingComponent("failing", events, failStart = true, ownOnStart = true) },
            )

        val failure = runCatching(controller::startComponents).exceptionOrNull()

        assertIs<IllegalStateException>(failure)
        assertEquals(
            listOf(
                "start:first",
                "start:failing",
                "stop:failing",
                "close:failing",
                "stop:first",
                "close:first",
            ),
            events,
        )
    }

    @Test
    fun `ordinary task failure is reported without cancelling sibling tasks`(): Unit = runBlocking {
        val reports: MutableList<TaskFailure> = CopyOnWriteArrayList()
        val controller = controller(reports = reports)
        controller.startComponents()
        val siblingStarted = CompletableDeferred<Unit>()
        val siblingCancelled = CompletableDeferred<Unit>()

        controller.rootBinding.launchTask("sibling", critical = false) {
            siblingStarted.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                siblingCancelled.complete(Unit)
            }
        }
        siblingStarted.await()
        controller.rootBinding.launchTask("failure", critical = false) { error("boom") }.join()

        assertEquals(1, reports.size)
        assertEquals("failure", reports.single().taskName)
        assertFalse(reports.single().critical)
        assertFalse(siblingCancelled.isCompleted)

        controller.shutdown(afterTaskDrain = {})
        siblingCancelled.await()
    }

    @Test
    fun `each critical task failure requests plug-in disable`(): Unit = runBlocking {
        val requests = AtomicInteger()
        val controller = controller(onCriticalFailure = { requests.incrementAndGet() })
        controller.startComponents()

        controller.rootBinding.launchTask("first", critical = true) { error("first") }.join()
        controller.rootBinding.launchTask("second", critical = true) { error("second") }.join()

        assertEquals(2, requests.get())
        controller.shutdown(afterTaskDrain = {})
    }

    @Test
    fun `explicit undispatched task enters before launch returns`(): Unit {
        val controller = controller()
        controller.startComponents()
        var entered = false

        val task = controller.rootBinding.context.task(
            name = "observer",
            start = CoroutineStart.UNDISPATCHED,
        ) {
            entered = true
            awaitCancellation()
        }

        assertTrue(entered)
        task.cancel()
        controller.shutdown(afterTaskDrain = {})
    }

    @Test
    fun `disable hook runs after tasks drain and before component stop`(): Unit = runBlocking {
        val events: MutableList<String> = CopyOnWriteArrayList()
        val controller = controller("component" to { RecordingComponent("component", events) })
        controller.startComponents()
        val taskStarted = CompletableDeferred<Unit>()
        controller.rootBinding.launchTask("worker", critical = false) {
            taskStarted.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                events += "task-drained"
            }
        }
        taskStarted.await()

        val result = controller.shutdown { events += "disable-hook" }

        assertTrue(result.drained)
        assertEquals(
            listOf("start:component", "task-drained", "disable-hook", "stop:component"),
            events,
        )
    }

    private fun controller(
        vararg components: Pair<String, () -> PluginComponent>,
        reports: MutableList<TaskFailure> = mutableListOf(),
        onCriticalFailure: (TaskFailure) -> Unit = {},
    ): LifecycleController =
        LifecycleController(
            rootName = "test",
            declarations =
                components.map { (name, factory) ->
                    ComponentDeclaration(name, factory, ComponentSlot())
                },
            contextFactory = { path, binding -> TestComponentContext(path, binding) },
            failureReporter = { reports += it },
            onCriticalFailure = onCriticalFailure,
            drainTimeout = 250.milliseconds,
        )

    private open class RecordingComponent(
        val label: String,
        private val events: MutableList<String> = mutableListOf(),
        private val failStart: Boolean = false,
        private val ownOnStart: Boolean = false,
    ) : PluginComponent() {
        var started: Boolean = false
            private set

        override fun ComponentContext.start(): Unit {
            events += "start:$label"
            if (ownOnStart) own(AutoCloseable { events += "close:$label" })
            if (failStart) error("start failed")
            started = true
        }

        override fun ComponentContext.stop(): Unit {
            events += "stop:$label"
            started = false
        }
    }

    private class ParentComponent(
        label: String,
        events: MutableList<String>,
    ) : RecordingComponent(label, events) {
        @Suppress("unused")
        private val child by component { RecordingComponent("first.child", events) }
    }

    private class DependentSiblingsComponent : PluginComponent() {
        private val database by component { RecordingComponent("database") }
        private val homes by component {
            databaseStartedWhenHomesConstructed = database.started
            RecordingComponent("homes")
        }

        var databaseStartedWhenHomesConstructed: Boolean = false
            private set

        @Suppress("unused")
        fun keepDelegatesReferenced(): RecordingComponent = homes
    }

    private class ExposedChildComponent : PluginComponent() {
        private val child by component { RecordingComponent("child") }

        fun childValue(): RecordingComponent = child
    }

    private class TestComponentContext(
        override val componentName: String,
        private val binding: LifecycleBinding,
    ) : ComponentContext {
        override val plugin: Plugin = proxy()
        override val server: Server = proxy()
        override val logger: ComponentLogger = proxy()
        override val dependencies: DependencyResolver = DependencyGraph(javaClass.classLoader)

        override fun task(
            name: String?,
            block: suspend CoroutineScope.() -> Unit,
        ): Job = binding.launchTask(name, critical = false, block = block)

        override fun task(
            name: String?,
            start: CoroutineStart,
            block: suspend CoroutineScope.() -> Unit,
        ): Job = binding.launchTask(name, critical = false, start = start, block = block)

        override fun criticalTask(
            name: String?,
            block: suspend CoroutineScope.() -> Unit,
        ): Job = binding.launchTask(name, critical = true, block = block)

        override fun <T : AutoCloseable> own(resource: T): T = binding.own(resource)
    }

    private companion object {
        @Suppress("UNCHECKED_CAST")
        private inline fun <reified T> proxy(): T =
            Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
                when (method.returnType) {
                    Boolean::class.javaPrimitiveType -> false
                    Byte::class.javaPrimitiveType -> 0.toByte()
                    Short::class.javaPrimitiveType -> 0.toShort()
                    Int::class.javaPrimitiveType -> 0
                    Long::class.javaPrimitiveType -> 0L
                    Float::class.javaPrimitiveType -> 0F
                    Double::class.javaPrimitiveType -> 0.0
                    Char::class.javaPrimitiveType -> '\u0000'
                    else -> null
                }
            } as T
    }
}
