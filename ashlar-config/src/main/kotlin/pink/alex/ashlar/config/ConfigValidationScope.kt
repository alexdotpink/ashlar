package pink.alex.ashlar.config

import kotlin.reflect.KProperty1

/** Collector available to one pure [ConfigValidation] function. */
public interface ConfigValidationScope<T : Any> {
    public val current: T

    /** Adds an error when [condition] is false. */
    public fun requireValue(
        condition: Boolean,
        property: KProperty1<T, *>,
        message: () -> String,
    )

    /** Adds a non-rejecting warning when [condition] is true. */
    public fun warnIf(
        condition: Boolean,
        property: KProperty1<T, *>,
        message: () -> String,
    )
}
