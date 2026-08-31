package dev.placeholder.framework.menus

import kotlin.properties.ReadOnlyProperty
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty
import kotlinx.coroutines.flow.Flow

/** A keyed local state value whose identity comes from its delegated property name. */
public class MenuState<T> internal constructor(
    private val binding: StateBinding<T>,
) : ReadWriteProperty<Any?, T> {
    /** Binds this value to [property]'s stable name in its component. */
    public operator fun provideDelegate(
        thisRef: Any?,
        property: KProperty<*>,
    ): MenuState<T> {
        binding.bind(property.name)
        return this
    }

    override fun getValue(thisRef: Any?, property: KProperty<*>): T = binding.get()

    override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
        binding.set(value)
    }
}

/** A lifecycle-owned external value exposed as a read-only delegated property. */
public class CollectedMenuState<T> internal constructor(
    private val binding: CollectedStateBinding<T>,
) : ReadOnlyProperty<Any?, T> {
    /** Binds collection ownership to [property]'s stable component-local name. */
    public operator fun provideDelegate(
        thisRef: Any?,
        property: KProperty<*>,
    ): CollectedMenuState<T> {
        binding.bind(property.name)
        return this
    }

    override fun getValue(thisRef: Any?, property: KProperty<*>): T = binding.get()
}

internal class StateCell(var value: Any?)

internal class MenuStateStore(private val invalidate: () -> Unit) {
    private val cells: MutableMap<Pair<ComponentIdentity, String>, StateCell> = linkedMapOf()

    fun <T> binding(
        component: ComponentIdentity,
        initial: () -> T,
        register: (String) -> Unit,
    ): StateBinding<T> = StateBinding(component, initial, register, this)

    @Suppress("UNCHECKED_CAST")
    fun <T> value(
        component: ComponentIdentity,
        name: String,
        initial: () -> T,
    ): T = cells.getOrPut(component to name) { StateCell(initial()) }.value as T

    fun set(component: ComponentIdentity, name: String, value: Any?) {
        val cell = cells[component to name] ?: error("Menu state '$name' was not bound")
        if (cell.value != value) {
            cell.value = value
            invalidate()
        }
    }

    fun snapshot(): Map<String, String> = cells.entries.associate { (identity, cell) ->
        "${identity.first.semantic()}:${identity.second}" to summarizeMenuValue(cell.value)
    }

    fun clear() {
        cells.clear()
    }

    fun removeUnder(component: ComponentIdentity) {
        cells.keys.removeIf { (identity, _) -> identity.keys.take(component.keys.size) == component.keys }
    }
}

internal class StateBinding<T>(
    private val component: ComponentIdentity,
    private val initial: () -> T,
    private val register: (String) -> Unit,
    private val store: MenuStateStore,
) {
    private var name: String? = null

    fun bind(name: String) {
        check(this.name == null) { "A menu state delegate cannot be bound twice" }
        register(name)
        this.name = name
        store.value(component, name, initial)
    }

    fun get(): T = store.value(component, requireNotNull(name), initial)

    fun set(value: T) {
        store.set(component, requireNotNull(name), value)
    }
}

internal class CollectedStateBinding<T>(
    private val component: ComponentIdentity,
    private val boundary: BoundaryIdentity?,
    private val initial: T,
    private val flow: Flow<T>,
    private val builder: MenuTreeBuilder,
) {
    private var state: StateBinding<T>? = null

    fun bind(name: String) {
        check(state == null) { "A collected state delegate cannot be bound twice" }
        state = builder.stateBinding(component) { initial }.also { it.bind(name) }
        builder.collect(component, boundary, name, flow) { value -> state?.set(value) }
    }

    fun get(): T = requireNotNull(state) { "Collected menu state has not been bound" }.get()
}

internal fun summarizeMenuValue(value: Any?): String = when (value) {
    null -> "null"
    is CharSequence, is Number, is Boolean, is Enum<*> -> value.toString()
    is Collection<*> -> "${value::class.simpleName}(size=${value.size})"
    else -> value::class.simpleName ?: "value"
}
