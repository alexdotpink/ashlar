package dev.placeholder.framework

import dev.placeholder.framework.internal.LifecycleBinding
import dev.placeholder.framework.internal.ComponentDeclaration
import dev.placeholder.framework.internal.ComponentSlot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlin.properties.PropertyDelegateProvider
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KClass

/**
 * A stateful part of a plug-in with deterministic lifecycle and coroutine ownership.
 *
 * Child components are declared with [component]. The kernel starts children in declaration
 * order before their parent and stops the parent before its children in reverse order.
 */
public abstract class PluginComponent {
    private val children: MutableList<ComponentDeclaration<out PluginComponent>> = mutableListOf()
    private var binding: LifecycleBinding? = null

    /** Runs synchronously after all child components have started. */
    protected open fun ComponentContext.start(): Unit = Unit

    /** Runs synchronously before child components stop. */
    protected open fun ComponentContext.stop(): Unit = Unit

    /** Declares a child component. The delegated property's name becomes its lifecycle name. */
    protected fun <T : PluginComponent> component(
        factory: () -> T,
    ): PropertyDelegateProvider<Any?, ReadOnlyProperty<Any?, T>> {
        val slot = ComponentSlot<T>()
        return PropertyDelegateProvider { owner: Any?, property ->
            check(owner === this) { "A component delegate may only be used by its declaring component" }
            check(children.none { it.name == property.name }) {
                "A component named '${property.name}' is already declared"
            }
            children += ComponentDeclaration(property.name, factory, slot)
            ReadOnlyProperty { _: Any?, _ -> slot.get(property.name) }
        }
    }

    /** Resolves one stable dependency while this component is running. */
    protected inline fun <reified T : Any> inject(): ReadOnlyProperty<Any?, T> = inject(T::class)

    /** Resolves one stable dependency while this component is running. */
    protected fun <T : Any> inject(type: KClass<T>): ReadOnlyProperty<Any?, T> =
        ReadOnlyProperty { _, _ -> requireBinding().context.dependencies.get(type) }

    /** Launches an ordinary supervised task owned by this component. */
    protected fun task(
        name: String? = null,
        block: suspend CoroutineScope.() -> Unit,
    ): Job = requireBinding().context.task(name, block)

    /** Launches a supervised task whose uncaught failure disables the plug-in. */
    protected fun criticalTask(
        name: String? = null,
        block: suspend CoroutineScope.() -> Unit,
    ): Job = requireBinding().context.criticalTask(name, block)

    /** Registers a synchronous resource for reverse-order closure during shutdown. */
    protected fun <T : AutoCloseable> own(resource: T): T = requireBinding().context.own(resource)

    internal fun declaredChildren(): List<ComponentDeclaration<out PluginComponent>> = children.toList()

    internal fun attach(binding: LifecycleBinding): Unit {
        check(this.binding == null) { "A component instance cannot be attached more than once" }
        this.binding = binding
    }

    internal fun invokeStart(context: ComponentContext): Unit = with(context) { start() }

    internal fun invokeStop(context: ComponentContext): Unit = with(context) { stop() }

    private fun requireBinding(): LifecycleBinding =
        checkNotNull(binding) {
            "The component is not attached to a running FrameworkPlugin"
        }
}
