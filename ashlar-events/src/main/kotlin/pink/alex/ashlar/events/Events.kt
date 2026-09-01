package pink.alex.ashlar.events

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

/** Declares one ordinary or suspending handler for a plug-in-local application event. */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
public annotation class OnApplication

/** Marks one synchronous native-key Paper lifecycle configuration function. */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
public annotation class ConfigureLifecycleEvents

/** Removes inherited handler metadata from one overriding function. */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
public annotation class DisableEventHandler

/** Prevents one automatic event-set class branch from contributing handlers. */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
public annotation class DisableEvents

/** Prevents selected generated event-set contributions from loading in this plug-in. */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
public annotation class ExcludeEventContributions(vararg val types: KClass<*>)
