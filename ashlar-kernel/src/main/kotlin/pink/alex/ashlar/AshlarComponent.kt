package pink.alex.ashlar

import kotlin.reflect.KClass

/** Startup phases for generated root components. */
public enum class ComponentPhase {
    /** Plug-in-owned application components. */
    APPLICATION,

    /** Ashlar adapters which consume the complete application graph. */
    FRAMEWORK,
}

/** Automatically installs a generated root component in the plug-in lifecycle tree. */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
public annotation class AshlarComponent(
    public val name: String = "",
    public val phase: ComponentPhase = ComponentPhase.APPLICATION,
)

/** Removes deliberately unwanted generated contributions from one plug-in. */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
public annotation class ExcludeContributions(vararg public val types: KClass<*>)
