package dev.placeholder.framework.testkit

import dev.placeholder.framework.PluginComponent
import dev.placeholder.framework.TaskFailure
import dev.placeholder.framework.internal.testing.ComponentTestBridge
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Runs one component tree through the kernel lifecycle without starting a Minecraft server.
 *
 * Bukkit services are intentionally absent. Tests which need a world, scheduler, entity, or
 * plug-in instance belong in an integration fixture running on Paper and Folia.
 */
public class ComponentTestHarness<T : PluginComponent> internal constructor(
    name: String,
    factory: () -> T,
    drainTimeout: Duration,
) : AutoCloseable {
    private val bridge =
        ComponentTestBridge(
            rootName = "test",
            componentName = name,
            factory = factory,
            drainTimeout = drainTimeout,
        )
    private var state: State = State.CREATED
    private var startedComponent: T? = null
    private var shutdownResult: ComponentTestResult? = null

    /** The component instance. Available after [start] and before [stop]. */
    public val component: T
        get() = checkNotNull(startedComponent) { "The component is not running" }

    /** Starts the complete child-first component tree and returns its root. */
    public fun start(): T {
        check(state == State.CREATED) { "The component test cannot start from state $state" }
        return bridge.start().also {
            startedComponent = it
            state = State.STARTED
        }
    }

    /** Cancels tasks, drains them, stops the component tree, and closes owned resources. */
    public fun stop(): ComponentTestResult {
        shutdownResult?.let { return it }
        check(state != State.CREATED) { "Start the component test before stopping it" }
        val result = bridge.shutdown().let {
            ComponentTestResult(
                tasksDrained = it.drained,
                lifecycleFailures = it.lifecycleFailures,
                taskFailures = it.taskFailures,
                criticalFailures = it.criticalFailures,
            )
        }
        startedComponent = null
        shutdownResult = result
        state = State.STOPPED
        return result
    }

    /** Stops a running harness. Calling close again has no effect. */
    override fun close(): Unit {
        if (state == State.STARTED) stop()
    }

    private enum class State {
        CREATED,
        STARTED,
        STOPPED,
    }
}

/** Captures everything the kernel observed while stopping a component test. */
public data class ComponentTestResult(
    public val tasksDrained: Boolean,
    public val lifecycleFailures: List<Throwable>,
    public val taskFailures: List<TaskFailure>,
    public val criticalFailures: List<TaskFailure>,
) {
    /** Fails if shutdown, a lifecycle hook, or an owned resource reported a problem. */
    public fun checkSuccessful(): Unit {
        check(tasksDrained) { "Component tasks did not drain before the test timeout" }
        lifecycleFailures.firstOrNull()?.let { throw AssertionError("Component shutdown failed", it) }
        taskFailures.firstOrNull()?.let { failure ->
            throw AssertionError(
                "Task '${failure.taskName ?: "<unnamed>"}' in ${failure.componentName} failed",
                failure.cause,
            )
        }
    }
}

/** Creates a component harness. Call [ComponentTestHarness.start] before reading the component. */
public fun <T : PluginComponent> componentTest(
    name: String = "component",
    drainTimeout: Duration = 2.seconds,
    factory: () -> T,
): ComponentTestHarness<T> {
    require(name.isNotBlank()) { "The component name must not be blank" }
    require('/' !in name) { "The component name must be one path segment" }
    require(drainTimeout.isPositive()) { "The task drain timeout must be positive" }
    return ComponentTestHarness(name, factory, drainTimeout)
}
