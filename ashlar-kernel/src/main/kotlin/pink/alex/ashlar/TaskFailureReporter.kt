package pink.alex.ashlar

/** Details reported when a component-owned coroutine fails. */
public data class TaskFailure(
    public val componentName: String,
    public val taskName: String?,
    public val critical: Boolean,
    public val cause: Throwable,
)

/** Receives uncaught failures from component-owned tasks. */
public fun interface TaskFailureReporter {
    public fun report(failure: TaskFailure)
}
