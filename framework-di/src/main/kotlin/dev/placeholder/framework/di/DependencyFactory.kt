package dev.placeholder.framework.di

import kotlin.reflect.KClass

/** Supported dependency lifetimes. */
public enum class DependencyLifetime {
    PLUGIN,
    INVOCATION,
    FACTORY,
}

/** One dependency requested by a generated constructor factory. */
public class DependencyKey<T : Any>(
    public val type: KClass<T>,
    public val qualifier: KClass<out Annotation>? = null,
) {
    override fun equals(other: Any?): Boolean =
        other is DependencyKey<*> && type == other.type && qualifier == other.qualifier

    override fun hashCode(): Int = 31 * type.hashCode() + qualifier.hashCode()

    override fun toString(): String = buildString {
        qualifier?.qualifiedName?.let { append('@').append(it).append(' ') }
        append(type.qualifiedName ?: type.toString())
    }
}

/** Runtime lookup available only to generated factories and graph configuration. */
public interface DependencyResolver {
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
