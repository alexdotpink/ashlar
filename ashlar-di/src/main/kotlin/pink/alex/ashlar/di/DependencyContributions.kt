package pink.alex.ashlar.di

import kotlin.reflect.KClass

/** One automatically installed root component and its additional bindings. */
public class RootComponentContribution(
    public val type: KClass<*>,
    public val bindings: List<KClass<*>>,
    public val name: String?,
    /** Ordinal of the kernel component phase, kept generic to avoid a DI-to-kernel dependency. */
    public val phase: Int = 0,
)

/** Generated per-module index loaded through the plug-in classloader. */
public interface DependencyContributionModule {
    public val rootComponents: List<RootComponentContribution>

    /** Injectable extension implementations contributed by this compiled module. */
    public val contributions: List<KClass<*>>
        get() = emptyList()
}

/**
 * Performs generated framework setup after plug-in graph overrides and before any
 * automatic component is constructed.
 *
 * The returned resource is closed in reverse initializer order during rollback or
 * plug-in shutdown.
 */
public fun interface DependencyGraphInitializer {
    public fun initialize(graph: DependencyGraph): AutoCloseable?
}
