package dev.placeholder.framework

import dev.placeholder.framework.di.DependencyResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import net.kyori.adventure.text.logger.slf4j.ComponentLogger
import org.bukkit.Server
import org.bukkit.plugin.Plugin

/** Paper services available during the plug-in load callback. */
public interface PluginLoadContext {
    public val plugin: Plugin

    public val server: Server

    public val logger: ComponentLogger
}

/** Runtime services passed to component and plug-in lifecycle hooks. */
public interface ComponentContext : PluginLoadContext {
    /** The component's slash-separated path in the plug-in's component tree. */
    public val componentName: String

    /** Dependencies visible to this running component. */
    public val dependencies: DependencyResolver

    /** Launches an ordinary supervised task owned by this component. */
    public fun task(
        name: String? = null,
        block: suspend CoroutineScope.() -> Unit,
    ): Job

    /** Launches an ordinary supervised task with an explicit coroutine start mode. */
    public fun task(
        name: String?,
        start: CoroutineStart,
        block: suspend CoroutineScope.() -> Unit,
    ): Job

    /** Launches a supervised task whose uncaught failure disables the plug-in. */
    public fun criticalTask(
        name: String? = null,
        block: suspend CoroutineScope.() -> Unit,
    ): Job

    /** Registers a synchronous resource for reverse-order closure during shutdown. */
    public fun <T : AutoCloseable> own(resource: T): T
}
