package dev.placeholder.framework.internal

import dev.placeholder.framework.ComponentContext
import dev.placeholder.framework.PluginComponent
import dev.placeholder.framework.TaskFailure
import dev.placeholder.framework.TaskFailureReporter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration

internal data class ComponentDeclaration<T : PluginComponent>(
    val name: String,
    val factory: () -> T,
    val slot: ComponentSlot<T>,
)

internal class ComponentSlot<T : PluginComponent> {
    private val value: AtomicReference<Any> = AtomicReference(Unavailable)

    fun publish(component: T): Unit {
        check(value.compareAndSet(Unavailable, component)) { "The component was already started" }
    }

    fun clear(): Unit {
        value.set(Unavailable)
    }

    fun get(name: String): T {
        val current: Any = value.get()
        check(current !== Unavailable) {
            "Component '$name' is only available after it starts and before it stops"
        }
        @Suppress("UNCHECKED_CAST")
        return current as T
    }

    private data object Unavailable
}

internal class LifecycleBinding(
    val path: String,
    private val scope: CoroutineScope,
    private val failureReporter: TaskFailureReporter,
    private val onCriticalFailure: (TaskFailure) -> Unit,
) {
    private val resources: MutableList<AutoCloseable> = mutableListOf()
    private var state: State = State.INACTIVE

    lateinit var context: ComponentContext

    fun activate(): Unit = synchronized(this) {
        check(state == State.INACTIVE) { "Component '$path' cannot start from state $state" }
        state = State.ACTIVE
    }

    fun deactivate(): Unit = synchronized(this) {
        if (state == State.ACTIVE) state = State.STOPPING
    }

    fun close(): Unit = synchronized(this) {
        state = State.CLOSED
    }

    fun launchTask(
        name: String?,
        critical: Boolean,
        block: suspend CoroutineScope.() -> Unit,
    ): Job {
        requireActive("launch tasks")
        val failureHandler: CoroutineExceptionHandler =
            CoroutineExceptionHandler { _, cause ->
                if (cause is CancellationException) return@CoroutineExceptionHandler

                val failure = TaskFailure(path, name, critical, cause)
                runCatching { failureReporter.report(failure) }
                    .onFailure { reporterFailure ->
                        context.logger.error("[$path] The task failure reporter failed", reporterFailure)
                    }
                if (critical) {
                    runCatching { onCriticalFailure(failure) }
                        .onFailure { callbackFailure ->
                            context.logger.error(
                                "[$path] Could not react to a critical task failure",
                                callbackFailure,
                            )
                        }
                }
            }
        val coroutineName = CoroutineName(name?.let { "$path/$it" } ?: path)
        return scope.launch(coroutineName + failureHandler, start = CoroutineStart.DEFAULT, block = block)
    }

    fun <T : AutoCloseable> own(resource: T): T {
        synchronized(this) {
            checkActive("own resources")
            resources += resource
        }
        return resource
    }

    fun closeResources(failures: MutableList<Throwable>): Unit {
        val resourcesToClose: List<AutoCloseable> =
            synchronized(this) {
                resources.asReversed().toList().also { resources.clear() }
            }
        resourcesToClose.forEach { resource ->
            runCatching(resource::close).onFailure(failures::add)
        }
    }

    private fun requireActive(operation: String): Unit = synchronized(this) {
        checkActive(operation)
    }

    private fun checkActive(operation: String): Unit {
        check(state == State.ACTIVE) { "Component '$path' cannot $operation while it is $state" }
    }

    private enum class State {
        INACTIVE,
        ACTIVE,
        STOPPING,
        CLOSED,
    }
}

internal class LifecycleController(
    private val rootName: String,
    private val declarations: List<ComponentDeclaration<out PluginComponent>>,
    private val contextFactory: (String, LifecycleBinding) -> ComponentContext,
    private val failureReporter: TaskFailureReporter,
    private val onCriticalFailure: (TaskFailure) -> Unit,
    private val drainTimeout: Duration,
    private val onComponentStarted: (PluginComponent) -> Unit = {},
) {
    private val rootJob: Job = SupervisorJob()
    private val rootScope: CoroutineScope = CoroutineScope(Dispatchers.Default + rootJob + CoroutineName(rootName))
    val rootBinding: LifecycleBinding = binding(rootName, rootScope)
    private val children: MutableList<LifecycleNode> = mutableListOf()
    private val attachedComponents: MutableSet<PluginComponent> =
        java.util.Collections.newSetFromMap(java.util.IdentityHashMap<PluginComponent, Boolean>())
    private var state: State = State.CREATED

    init {
        require(drainTimeout.isPositive()) { "Task drain timeout must be positive" }
    }

    fun startComponents(): Unit {
        check(state == State.CREATED) { "The component tree cannot be started from state $state" }
        state = State.STARTING
        rootBinding.activate()
        try {
            declarations.forEach { declaration ->
                val child = createNode(rootName, declaration, rootScope)
                children += child
                child.startTree()
            }
            state = State.STARTED
        } catch (failure: Throwable) {
            rollback().forEach(failure::addSuppressed)
            throw failure
        }
    }

    fun rollback(): List<Throwable> {
        if (state == State.CLOSED) return emptyList()
        beginStopping("Plug-in startup failed")
        drainTasks()
        val failures: MutableList<Throwable> = mutableListOf()
        children.asReversed().forEach { child -> child.stopTree(failures) }
        rootBinding.closeResources(failures)
        rootBinding.close()
        state = State.CLOSED
        return failures
    }

    fun shutdown(afterTaskDrain: () -> Unit): ShutdownResult {
        if (state == State.CLOSED) return ShutdownResult(drained = true, failures = emptyList())
        beginStopping("Plug-in disabled")
        val drained: Boolean = drainTasks()
        val failures: MutableList<Throwable> = mutableListOf()
        runCatching(afterTaskDrain).onFailure(failures::add)
        children.asReversed().forEach { child -> child.stopTree(failures) }
        rootBinding.closeResources(failures)
        rootBinding.close()
        state = State.CLOSED
        return ShutdownResult(drained, failures)
    }

    private fun beginStopping(reason: String): Unit {
        state = State.STOPPING
        rootBinding.deactivate()
        children.forEach(LifecycleNode::deactivateTree)
        rootJob.cancel(CancellationException(reason))
    }

    private fun drainTasks(): Boolean =
        runBlocking {
            withTimeoutOrNull(drainTimeout.inWholeMilliseconds) {
                rootJob.children.toList().joinAll()
                rootJob.join()
                true
            } ?: false
        }

    private fun createNode(
        parentPath: String,
        declaration: ComponentDeclaration<out PluginComponent>,
        parentScope: CoroutineScope,
    ): LifecycleNode {
        val component: PluginComponent = declaration.factory()
        check(attachedComponents.add(component)) { "The same component instance cannot be declared twice" }
        val path = "$parentPath/${declaration.name}"
        val node = LifecycleNode(path, component, declaration.slot, parentScope)
        component.attach(node.binding)
        return node
    }

    private fun binding(path: String, scope: CoroutineScope): LifecycleBinding =
        LifecycleBinding(path, scope, failureReporter, onCriticalFailure).also { binding ->
            binding.context = contextFactory(path, binding)
        }

    private inner class LifecycleNode(
        private val path: String,
        private val component: PluginComponent,
        private val slot: ComponentSlot<out PluginComponent>,
        parentScope: CoroutineScope,
    ) {
        private val job: Job = SupervisorJob(parentScope.coroutineContext[Job])
        private val scope: CoroutineScope = CoroutineScope(parentScope.coroutineContext + job + CoroutineName(path))
        val binding: LifecycleBinding = binding(path, scope)
        private val children: MutableList<LifecycleNode> = mutableListOf()
        private var needsStop: Boolean = false

        fun startTree(): Unit {
            binding.activate()
            component.declaredChildren().forEach { declaration ->
                val child = createNode(path, declaration, scope)
                children += child
                child.startTree()
            }
            needsStop = true
            component.invokeStart(binding.context)
            onComponentStarted(component)
            publish()
        }

        fun deactivateTree(): Unit {
            binding.deactivate()
            children.forEach(LifecycleNode::deactivateTree)
        }

        fun stopTree(failures: MutableList<Throwable>): Unit {
            if (needsStop) {
                runCatching { component.invokeStop(binding.context) }.onFailure(failures::add)
                needsStop = false
            }
            children.asReversed().forEach { child -> child.stopTree(failures) }
            binding.closeResources(failures)
            binding.close()
            slot.clear()
        }

        @Suppress("UNCHECKED_CAST")
        private fun publish(): Unit {
            (slot as ComponentSlot<PluginComponent>).publish(component)
        }
    }

    private enum class State {
        CREATED,
        STARTING,
        STARTED,
        STOPPING,
        CLOSED,
    }
}

internal data class ShutdownResult(
    val drained: Boolean,
    val failures: List<Throwable>,
)
