package pink.alex.ashlar.di

import java.util.ServiceLoader
import kotlin.reflect.KClass

/** One typed dependency graph owned by a framework plug-in. */
public class DependencyGraph(
    private val classLoader: ClassLoader,
) : DependencyResolver, AutoCloseable {
    private val bindings: MutableMap<DependencyKey<*>, Any> = linkedMapOf()
    private val factories: MutableMap<KClass<*>, DependencyFactory<*>> = linkedMapOf()
    private val resolving: ArrayDeque<DependencyKey<*>> = ArrayDeque()
    private var closed: Boolean = false

    override fun <T : Any> get(type: KClass<T>): T =
        get(DependencyKey(type))

    override fun <T : Any> get(
        type: KClass<T>,
        qualifier: KClass<out Annotation>,
    ): T =
        get(DependencyKey(type, qualifier))

    override fun <T : Any> get(key: DependencyKey<T>): T = synchronized(this) {
        check(!closed) { "The dependency graph is closed" }
        val type = key.type
        bindings[key]?.let { value -> return type.java.cast(value) }

        check(key !in resolving) {
            val cycle = (resolving + key).joinToString(" -> ")
            "Dependency cycle detected: $cycle"
        }

        val factory = factory(key)
        check(factory.lifetime != DependencyLifetime.INVOCATION) {
            "Invocation-scoped dependency $key was requested outside an invocation"
        }
        resolving.addLast(key)
        try {
            val value = factory.create(this)
            if (factory.lifetime != DependencyLifetime.FACTORY) bindValue(key, value)
            return type.java.cast(value)
        } finally {
            check(resolving.removeLast() == key)
        }
    }

    /** Creates one isolated invocation resolver backed by this plug-in graph. */
    public fun invocation(vararg instances: Any): InvocationDependencies = synchronized(this) {
        check(!closed) { "The dependency graph is closed" }
        InvocationDependencies(this, instances.asList())
    }

    /** Binds an externally constructed plug-in-scoped instance to its concrete type. */
    public fun bind(instance: Any): Unit = bind(instance, emptyList())

    /** Binds an instance to its concrete type and explicitly declared additional types. */
    public fun bind(
        instance: Any,
        additionalTypes: List<KClass<*>>,
    ): Unit = synchronized(this) {
        check(!closed) { "The dependency graph is closed" }
        bindValue(DependencyKey(instance::class), instance)
        additionalTypes.forEach { type ->
            require(type.java.isInstance(instance)) {
                "${instance::class.qualifiedName} does not implement ${type.qualifiedName}"
            }
            @Suppress("UNCHECKED_CAST")
            bindValue(DependencyKey(type as KClass<Any>), instance)
        }
    }

    /** Binds an externally constructed instance to one exact structural key. */
    public fun <T : Any> bind(
        key: DependencyKey<T>,
        instance: T,
    ): Unit = synchronized(this) {
        check(!closed) { "The dependency graph is closed" }
        bindValue(key, instance)
    }

    /** Binds one qualified view of an externally constructed instance. */
    public fun <T : Any> bind(
        type: KClass<T>,
        qualifier: KClass<out Annotation>,
        instance: T,
    ): Unit = synchronized(this) {
        check(!closed) { "The dependency graph is closed" }
        bindValue(DependencyKey(type, qualifier), instance)
    }

    /** Installs a framework default only when the plug-in has not already bound [key]. */
    public fun <T : Any> bindDefault(
        key: DependencyKey<T>,
        instance: T,
    ): Boolean = synchronized(this) {
        check(!closed) { "The dependency graph is closed" }
        bindings.putIfAbsent(key, instance) == null
    }

    /** Installs a framework default only when the plug-in has not already bound [type]. */
    public fun <T : Any> bindDefault(
        type: KClass<T>,
        instance: T,
    ): Boolean = synchronized(this) {
        check(!closed) { "The dependency graph is closed" }
        bindings.putIfAbsent(DependencyKey(type), instance) == null
    }

    /** Loads generated root-component indexes visible to this plug-in classloader. */
    public fun rootComponents(): List<RootComponentContribution> =
        contributionModules()
            .flatMap { module -> module.rootComponents }

    /** Resolves every generated contribution implementing [type] in deterministic order. */
    override fun <T : Any> contributions(type: KClass<T>): List<T> =
        contributionModules()
            .flatMap { module -> module.contributions }
            .filter { contribution -> type.java.isAssignableFrom(contribution.java) }
            .distinct()
            .sortedBy { contribution -> contribution.qualifiedName }
            .map { contribution ->
                @Suppress("UNCHECKED_CAST")
                get(contribution as KClass<T>)
            }

    /** Returns the generated factory used for dependency ordering and construction. */
    public fun factory(type: KClass<*>): DependencyFactory<*> = factory(DependencyKey(type))

    internal fun factory(key: DependencyKey<*>): DependencyFactory<*> = synchronized(this) {
        factories.getOrPut(key.type) { loadFactory(key.type, key) }
    }

    internal fun <T : Any> bound(key: DependencyKey<T>): T? = synchronized(this) {
        check(!closed) { "The dependency graph is closed" }
        bindings[key]?.let(key.type.java::cast)
    }

    override fun close(): Unit = synchronized(this) {
        closed = true
        resolving.clear()
        factories.clear()
        bindings.clear()
    }

    private fun bindValue(
        key: DependencyKey<*>,
        value: Any,
    ) {
        val previous = bindings.putIfAbsent(key, value)
        check(previous == null || previous === value) {
            "Dependency $key is already bound by ${previous!!::class.qualifiedName}"
        }
    }

    private fun loadFactory(
        type: KClass<*>,
        requestedKey: DependencyKey<*> = DependencyKey(type),
    ): DependencyFactory<*> {
        val factoryName = generatedFactoryName(type)
        val factoryClass = runCatching { Class.forName(factoryName, true, classLoader) }
            .getOrElse { cause ->
                throw IllegalStateException(
                    "No generated dependency factory for $requestedKey; " +
                        "annotate its constructor with @Inject and enable framework DI processing",
                    cause,
                )
            }
        val factory = factoryClass.getDeclaredConstructor().newInstance()
        check(factory is DependencyFactory<*>) {
            "$factoryName does not implement DependencyFactory"
        }
        check(factory.type == type) {
            "$factoryName declares ${factory.type.qualifiedName}, expected ${type.qualifiedName}"
        }
        return factory
    }

    private fun contributionModules(): List<DependencyContributionModule> =
        ServiceLoader.load(DependencyContributionModule::class.java, classLoader).toList()
}

/** Dependency cache whose invocation-scoped values are discarded after one command finishes. */
public class InvocationDependencies internal constructor(
    private val parent: DependencyGraph,
    instances: List<Any>,
) : DependencyResolver, AutoCloseable {
    private val bindings: MutableMap<DependencyKey<*>, Any> = linkedMapOf()
    private val resolving: ArrayDeque<DependencyKey<*>> = ArrayDeque()
    private var closed: Boolean = false

    init {
        instances.forEach(::bind)
    }

    override fun <T : Any> get(type: KClass<T>): T =
        get(DependencyKey(type))

    override fun <T : Any> get(
        type: KClass<T>,
        qualifier: KClass<out Annotation>,
    ): T =
        get(DependencyKey(type, qualifier))

    override fun <T : Any> get(key: DependencyKey<T>): T = synchronized(this) {
        check(!closed) { "The invocation dependency scope is closed" }
        val type = key.type
        bindings[key]?.let { value -> return type.java.cast(value) }
        parent.bound(key)?.let { value -> return value }

        val factory = parent.factory(key)
        if (factory.lifetime == DependencyLifetime.PLUGIN) {
            return parent.get(key)
        }
        check(key !in resolving) {
            val cycle = (resolving + key).joinToString(" -> ")
            "Dependency cycle detected: $cycle"
        }
        resolving.addLast(key)
        try {
            val value = factory.create(this)
            if (factory.lifetime == DependencyLifetime.INVOCATION) bindValue(key, value)
            return type.java.cast(value)
        } finally {
            check(resolving.removeLast() == key)
        }
    }

    override fun <T : Any> contributions(type: KClass<T>): List<T> = parent.contributions(type)

    /** Adds an invocation-owned value under its concrete type. */
    public fun bind(instance: Any): Unit = synchronized(this) {
        check(!closed) { "The invocation dependency scope is closed" }
        bindValue(DependencyKey(instance::class), instance)
    }

    /** Adds an invocation-owned value under an explicit API type. */
    public fun <T : Any> bind(
        type: KClass<T>,
        instance: T,
    ): Unit = synchronized(this) {
        check(!closed) { "The invocation dependency scope is closed" }
        bindValue(DependencyKey(type), instance)
    }

    /** Adds an invocation-owned value under one exact structural key. */
    public fun <T : Any> bind(
        key: DependencyKey<T>,
        instance: T,
    ): Unit = synchronized(this) {
        check(!closed) { "The invocation dependency scope is closed" }
        bindValue(key, instance)
    }

    override fun close(): Unit = synchronized(this) {
        closed = true
        resolving.clear()
        bindings.clear()
    }

    private fun bindValue(
        key: DependencyKey<*>,
        value: Any,
    ) {
        val previous = bindings.putIfAbsent(key, value)
        check(previous == null || previous === value) {
            "Invocation dependency $key is already bound by ${previous!!::class.qualifiedName}"
        }
    }
}

/** Deterministic linkage name shared by generated factories and runtime lookup. */
public fun generatedFactoryName(type: KClass<*>): String {
    val javaName = type.java.name
    val packageName = type.java.packageName
    val relativeName = javaName.removePrefix("$packageName.").replace('$', '_')
    return "$packageName.${relativeName}__AshlarFactory"
}
