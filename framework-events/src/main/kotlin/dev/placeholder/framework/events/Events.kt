package dev.placeholder.framework.events

import kotlin.reflect.KClass
import org.bukkit.event.EventPriority

/** Declares one auto-discovered event set with constructor injection. */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
public annotation class Events

/** Declares one synchronous Bukkit or Paper server event handler. */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
public annotation class On(
    val priority: EventPriority = EventPriority.NORMAL,
    val ignoreCancelled: Boolean = false,
)

/** Declares one plug-in-owned coroutine observer of a server event at MONITOR. */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
public annotation class Observe(val ignoreCancelled: Boolean = false)

/** Prevents selected generated event-set contributions from loading in this plug-in. */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
public annotation class ExcludeEventContributions(vararg val types: KClass<*>)
