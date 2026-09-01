package pink.alex.ashlar.di

import kotlin.reflect.KClass

/** Supported dependency lifetimes. */
public enum class DependencyLifetime {
    PLUGIN,
    INVOCATION,
    FACTORY,
}

/** One dependency requested by a generated constructor factory. */
public class DependencyKey<T : Any>(
    public val dependencyType: DependencyType<T>,
    public val qualifier: KClass<out Annotation>? = null,
) {
    /** Creates a key for a non-parameterized dependency type. */
    public constructor(
        type: KClass<T>,
        qualifier: KClass<out Annotation>? = null,
    ) : this(DependencyType(type), qualifier)

    /** Raw class convenience for factory discovery and existing non-parameterized integrations. */
    @Suppress("UNCHECKED_CAST")
    public val type: KClass<T>
        get() = dependencyType.rawType as KClass<T>

    override fun equals(other: Any?): Boolean =
        other is DependencyKey<*> && dependencyType == other.dependencyType && qualifier == other.qualifier

    override fun hashCode(): Int = 31 * dependencyType.hashCode() + qualifier.hashCode()

    override fun toString(): String = buildString {
        qualifier?.qualifiedName?.let { append('@').append(it).append(' ') }
        append(dependencyType)
    }
}

/** Immutable recursive identity of one closed invariant Kotlin dependency type. */
public class DependencyType<T : Any>(
    public val rawType: KClass<*>,
    arguments: List<DependencyType<*>> = emptyList(),
) {
    public val arguments: List<DependencyType<*>> = arguments.toList()

    override fun equals(other: Any?): Boolean =
        other is DependencyType<*> && rawType == other.rawType && arguments == other.arguments

    override fun hashCode(): Int = 31 * rawType.hashCode() + arguments.hashCode()

    override fun toString(): String = buildString {
        append(rawType.qualifiedName ?: rawType.toString())
        if (arguments.isNotEmpty()) {
            arguments.joinTo(this, prefix = "<", postfix = ">")
        }
    }
}

/** Runtime lookup available only to generated factories and graph configuration. */
public interface DependencyResolver {
    /** Resolves one exact dependency key, including recursive type arguments and its qualifier. */
    public fun <T : Any> get(key: DependencyKey<T>): T

    public fun <T : Any> get(type: KClass<T>): T

    public fun <T : Any> get(
        type: KClass<T>,
        qualifier: KClass<out Annotation>,
    ): T

    /** Resolves every generated multi-binding implementing [type]. */
    public fun <T : Any> contributions(type: KClass<T>): List<T>
}

/** Direct constructor linkage emitted by the DI processor. */
public interface DependencyFactory<T : Any> {
    public val type: KClass<T>

    public val lifetime: DependencyLifetime

    public val dependencies: List<DependencyKey<*>>

    public fun create(resolver: DependencyResolver): T
}

/** Retrieves a typed dependency. */
public inline fun <reified T : Any> DependencyResolver.get(): T = get(T::class)

/** Retrieves all generated implementations of one extension contract. */
public inline fun <reified T : Any> DependencyResolver.contributions(): List<T> = contributions(T::class)
