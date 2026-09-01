package pink.alex.ashlar.internal.testing

import pink.alex.ashlar.ComponentContext
import pink.alex.ashlar.PluginComponent
import pink.alex.ashlar.TaskFailure
import pink.alex.ashlar.internal.ComponentDeclaration
import pink.alex.ashlar.internal.ComponentSlot
import pink.alex.ashlar.internal.LifecycleBinding
import pink.alex.ashlar.internal.LifecycleController
import pink.alex.ashlar.internal.ShutdownResult
import pink.alex.ashlar.di.DependencyGraph
import pink.alex.ashlar.di.DependencyResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import net.kyori.adventure.text.logger.slf4j.ComponentLogger
import org.bukkit.Server
import org.bukkit.plugin.Plugin
import kotlin.time.Duration

/** Version-coupled bridge used by ashlar-testkit. It is not a framework API. */
internal class ComponentTestBridge<T : PluginComponent>(
    rootName: String,
    componentName: String,
    factory: () -> T,
    drainTimeout: Duration,
) {
    private val slot = ComponentSlot<T>()
    private val taskFailureLog: MutableList<TaskFailure> = mutableListOf()
    private val criticalFailureLog: MutableList<TaskFailure> = mutableListOf()
    private val controller =
        LifecycleController(
            rootName = rootName,
            declarations = listOf(ComponentDeclaration(componentName, factory, slot)),
            contextFactory = ::TestComponentContext,
            failureReporter = { failure -> synchronized(taskFailureLog) { taskFailureLog += failure } },
            onCriticalFailure = { failure ->
                synchronized(criticalFailureLog) { criticalFailureLog += failure }
            },
            drainTimeout = drainTimeout,
        )

    fun start(): T {
        controller.startComponents()
        return slot.get("component under test")
    }

    fun shutdown(): BridgeShutdownResult {
        val result: ShutdownResult = controller.shutdown(afterTaskDrain = {})
        return BridgeShutdownResult(
            drained = result.drained,
            lifecycleFailures = result.failures,
            taskFailures = synchronized(taskFailureLog) { taskFailureLog.toList() },
            criticalFailures = synchronized(criticalFailureLog) { criticalFailureLog.toList() },
        )
    }

    private class TestComponentContext(
        override val componentName: String,
        private val binding: LifecycleBinding,
    ) : ComponentContext {
        override val plugin: Plugin
            get() = unsupportedBukkitState()

        override val server: Server
            get() = unsupportedBukkitState()

        override val logger: ComponentLogger = ComponentLogger.logger("ashlar-testkit")
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

        override fun <R : AutoCloseable> own(resource: R): R = binding.own(resource)

        private fun unsupportedBukkitState(): Nothing =
            throw UnsupportedOperationException(
                "Component tests do not provide Bukkit objects; use a Paper or Folia integration test",
            )
    }
}

internal data class BridgeShutdownResult(
    val drained: Boolean,
    val lifecycleFailures: List<Throwable>,
    val taskFailures: List<TaskFailure>,
    val criticalFailures: List<TaskFailure>,
)
