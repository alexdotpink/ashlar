package dev.placeholder.framework.menus

/**
 * One redacted lifecycle observation emitted by a live menu session.
 *
 * @property player stable reference for the observed session owner
 * @property event semantic lifecycle event without mutable native state
 */
public data class MenuObservation(
    public val player: dev.placeholder.framework.execution.PlayerRef,
    public val event: MenuTrace,
)

/** Receives redacted lifecycle observations without gaining mutable session access. */
public fun interface MenuObserver {
    /** Called after the corresponding semantic event has occurred. */
    public fun observe(observation: MenuObservation)
}

/** Result of applying a synchronous cross-cutting interaction policy. */
public sealed interface MenuInterception {
    /** Continues normal action or storage dispatch. */
    public data object Allow : MenuInterception

    /** Rejects the gesture and optionally presents typed feedback. */
    public data class Reject(public val feedback: MenuFeedback? = null) : MenuInterception
}

/** Synchronous policy invoked for every current-revision interaction before dispatch. */
public fun interface MenuInterceptor {
    /** Returns whether [interaction] may reach its declared action or storage binding. */
    public fun intercept(interaction: MenuInteraction): MenuInterception
}

/** Removable observer or interceptor registration. */
public fun interface MenuRegistration : AutoCloseable {
    /** Removes this registration. Repeated calls have no effect. */
    override fun close()
}
