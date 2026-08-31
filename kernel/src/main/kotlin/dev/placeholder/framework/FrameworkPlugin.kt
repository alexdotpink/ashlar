package dev.placeholder.framework

import dev.placeholder.framework.internal.ComponentDeclaration
import dev.placeholder.framework.internal.ComponentSlot
import dev.placeholder.framework.internal.LifecycleBinding
import dev.placeholder.framework.internal.LifecycleController
import dev.placeholder.framework.internal.ShutdownResult
import dev.placeholder.framework.di.DependencyGraph
import dev.placeholder.framework.di.DependencyResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import net.kyori.adventure.text.logger.slf4j.ComponentLogger
import org.bukkit.Server
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.java.JavaPlugin
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Level
import kotlin.properties.PropertyDelegateProvider
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KClass
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** JavaPlugin base class which owns the framework lifecycle. */
public abstract class FrameworkPlugin : JavaPlugin() {
    private val children: MutableList<ComponentDeclaration<out PluginComponent>> = mutableListOf()
    private val criticalDisableRequested: AtomicBoolean = AtomicBoolean()
    private var lifecycle: LifecycleController? = null
    private var dependencyGraph: DependencyGraph? = null
    private var loaded: Boolean = false

    /** Maximum time disable waits for cancelled component tasks to finish. */
    protected open val taskDrainTimeout: Duration = 2.seconds

    /** Runs from Paper's load callback. Runtime task and resource helpers are unavailable here. */
    protected open fun PluginLoadContext.load(): Unit = Unit

    /** Runs after every declared component has started. */
    protected open fun ComponentContext.enable(): Unit = Unit

    /** Runs after task cancellation and draining, but before components stop. */
    protected open fun ComponentContext.disable(): Unit = Unit

    /** Selects the reporter used for uncaught component task failures. */
    protected open fun taskFailureReporter(logger: ComponentLogger): TaskFailureReporter =
        TaskFailureReporter { failure ->
            val kind = if (failure.critical) "Critical task" else "Task"
            val taskName = failure.taskName?.let { " '$it'" }.orEmpty()
            logger.error("[${failure.componentName}] $kind$taskName failed", failure.cause)
        }

    /** Adds explicit instances or overrides after generated dependencies have loaded. */
    protected open fun DependencyGraph.configure(): Unit = Unit

    /** Declares a root component which the kernel constructs during enable. */
    protected fun <T : PluginComponent> component(
        factory: () -> T,
    ): PropertyDelegateProvider<Any?, ReadOnlyProperty<Any?, T>> {
        val slot = ComponentSlot<T>()
        return PropertyDelegateProvider { owner: Any?, property ->
            check(owner === this) { "A component delegate may only be used by its declaring plug-in" }
            check(children.none { it.name == property.name }) {
                "A component named '${property.name}' is already declared"
            }
            children += ComponentDeclaration(property.name, factory, slot)
            ReadOnlyProperty { _: Any?, _ -> slot.get(property.name) }
        }
    }

    /** Resolves one stable dependency while this plug-in is running. */
    protected inline fun <reified T : Any> inject(): ReadOnlyProperty<Any?, T> = inject(T::class)

    /** Resolves one stable dependency while this plug-in is running. */
    protected fun <T : Any> inject(type: KClass<T>): ReadOnlyProperty<Any?, T> =
        ReadOnlyProperty { _, _ -> requireDependencyGraph().get(type) }

    /** Launches an ordinary supervised task owned by the plug-in root. */
    protected fun task(
        name: String? = null,
        block: suspend CoroutineScope.() -> Unit,
    ): Job = requireBinding().context.task(name, block)

    /** Launches a supervised task whose uncaught failure disables this plug-in. */
    protected fun criticalTask(
        name: String? = null,
        block: suspend CoroutineScope.() -> Unit,
    ): Job = requireBinding().context.criticalTask(name, block)

    /** Registers a synchronous resource for reverse-order closure during shutdown. */
    protected fun <T : AutoCloseable> own(resource: T): T = requireBinding().context.own(resource)

    final override fun onLoad(): Unit {
        check(!loaded) { "Hot reload is unsupported; create a fresh server process" }
        loaded = true
        with(DefaultPluginLoadContext(this, componentLogger)) { load() }
    }

    final override fun onEnable(): Unit {
        check(lifecycle == null) { "Hot reload is unsupported; create a fresh server process" }
        val graph = DependencyGraph(javaClass.classLoader)
        dependencyGraph = graph
        graph.bind(graph)
        graph.bind(this, listOf(FrameworkPlugin::class, Plugin::class))
        graph.bind(server)
        with(graph) { configure() }
        val automaticDeclarations = automaticComponentDeclarations(graph)
        val reporter = taskFailureReporter(componentLogger)
        val controller =
            LifecycleController(
                rootName = name,
                declarations = children.toList() + automaticDeclarations,
                contextFactory = { path, binding ->
                    PluginComponentContext(this, componentLogger, path, binding, graph)
                },
                failureReporter = reporter,
                onCriticalFailure = { requestDisableAfterCriticalFailure() },
                drainTimeout = taskDrainTimeout,
                onComponentStarted = { component -> graph.bind(component) },
            )
        lifecycle = controller
        try {
            controller.startComponents()
            with(controller.rootBinding.context) { enable() }
        } catch (failure: Throwable) {
            reportLifecycleFailures("rolling back startup", controller.rollback())
            graph.close()
            throw failure
        }
    }

    final override fun onDisable(): Unit {
        val controller = lifecycle ?: return
        val result: ShutdownResult = controller.shutdown {
            with(controller.rootBinding.context) { disable() }
        }
        if (!result.drained) {
            logger.warning("Component tasks did not finish within $taskDrainTimeout; shutdown continued")
        }
        reportLifecycleFailures("disabling", result.failures)
        dependencyGraph?.close()
        dependencyGraph = null
    }

    private fun requireBinding(): LifecycleBinding =
        checkNotNull(lifecycle?.rootBinding) { "The plug-in runtime has not started" }

    private fun requireDependencyGraph(): DependencyGraph =
        checkNotNull(dependencyGraph) { "The plug-in dependency graph is not running" }

    private fun automaticComponentDeclarations(
        graph: DependencyGraph,
    ): List<ComponentDeclaration<out PluginComponent>> {
        val excluded = javaClass.getAnnotation(ExcludeContributions::class.java)
            ?.types
            ?.toSet()
            .orEmpty()
        val contributions = graph.rootComponents()
            .filterNot { contribution -> contribution.type in excluded }
        val providers = buildMap<KClass<*>, KClass<*>> {
            contributions.forEach { contribution ->
                putUnique(contribution.type, contribution.type)
                contribution.bindings.forEach { binding -> putUnique(binding, contribution.type) }
            }
        }
        val remaining = contributions.associateBy { contribution -> contribution.type }.toMutableMap()
        val ordered = mutableListOf<dev.placeholder.framework.di.RootComponentContribution>()
        while (remaining.isNotEmpty()) {
            val nextPhase = remaining.values.minOf { contribution -> contribution.phase }
            val ready = remaining.values
                .filter { contribution -> contribution.phase == nextPhase }
                .filter { contribution ->
                    graph.factory(contribution.type).dependencies.none { dependency ->
                        providers[dependency.type] in remaining.keys
                    }
                }
                .sortedBy { contribution -> contribution.type.qualifiedName }
            check(ready.isNotEmpty()) {
                "Automatic component dependency cycle: " +
                    remaining.keys.joinToString { type -> type.qualifiedName.orEmpty() }
            }
            ready.forEach { contribution ->
                ordered += contribution
                remaining.remove(contribution.type)
            }
        }

        val usedNames = children.mapTo(mutableSetOf(), ComponentDeclaration<*>::name)
        return ordered.map { contribution ->
            check(PluginComponent::class.java.isAssignableFrom(contribution.type.java)) {
                "${contribution.type.qualifiedName} is marked @FrameworkComponent but does not extend PluginComponent"
            }
            val name = contribution.name ?: contribution.type.simpleName.orEmpty().replaceFirstChar(Char::lowercase)
            check(usedNames.add(name)) { "A root component named '$name' is already declared" }
            @Suppress("UNCHECKED_CAST")
            val type = contribution.type as KClass<PluginComponent>
            ComponentDeclaration(
                name = name,
                factory = {
                    graph.get(type).also { component -> graph.bind(component, contribution.bindings) }
                },
                slot = ComponentSlot(),
            )
        }
    }

    private fun MutableMap<KClass<*>, KClass<*>>.putUnique(
        key: KClass<*>,
        provider: KClass<*>,
    ) {
        val previous = putIfAbsent(key, provider)
        check(previous == null || previous == provider) {
            "Dependency ${key.qualifiedName} is provided by both " +
                "${previous!!.qualifiedName} and ${provider.qualifiedName}"
        }
    }

    private fun requestDisableAfterCriticalFailure(): Unit {
        if (!criticalDisableRequested.compareAndSet(false, true)) return
        runCatching {
            server.globalRegionScheduler.execute(this) {
                if (isEnabled) server.pluginManager.disablePlugin(this)
            }
        }.onFailure { failure ->
            logger.log(Level.SEVERE, "Could not schedule disable after a critical task failure", failure)
        }
    }

    private fun reportLifecycleFailures(action: String, failures: List<Throwable>): Unit {
        failures.forEach { failure ->
            logger.log(Level.SEVERE, "A lifecycle action failed while $action", failure)
        }
    }
}

private data class DefaultPluginLoadContext(
    override val plugin: Plugin,
    override val logger: ComponentLogger,
) : PluginLoadContext {
    override val server: Server
        get() = plugin.server
}

private class PluginComponentContext(
    override val plugin: Plugin,
    override val logger: ComponentLogger,
    override val componentName: String,
    private val binding: LifecycleBinding,
    override val dependencies: DependencyResolver,
) : ComponentContext {
    override val server: Server
        get() = plugin.server

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
