package dev.placeholder.framework.events

import kotlin.reflect.KClass
import org.bukkit.event.Event

/** One exception thrown by a synchronous server event handler. */
public data class ServerEventFailure(
    public val eventSet: KClass<*>,
    public val handler: String,
    public val eventType: KClass<out Event>,
    public val cause: Throwable,
)

/** Replaceable sink for synchronous server event handler failures. */
public fun interface ServerEventFailureReporter {
    public fun report(failure: ServerEventFailure)
}
