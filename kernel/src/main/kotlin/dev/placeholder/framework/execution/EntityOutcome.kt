package dev.placeholder.framework.execution

/** The result of an entity ownership block. */
public sealed interface EntityOutcome<out T> {
    /** The entity remained available and the block returned [value]. */
    public data class Completed<T>(public val value: T) : EntityOutcome<T>

    /** The entity retired before its queued ownership block could start. */
    public data object Retired : EntityOutcome<Nothing>

    /**
     * Runs [action] when this outcome is [Retired], then returns this outcome unchanged.
     *
     * This is convenient for optional cleanup or logging when the caller does not need to branch
     * on the outcome.
     */
    public fun onRetired(action: () -> Unit): EntityOutcome<T> {
        if (this === Retired) action()
        return this
    }
}
