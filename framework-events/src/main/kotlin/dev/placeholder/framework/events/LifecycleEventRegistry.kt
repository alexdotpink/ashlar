package dev.placeholder.framework.events

import dev.placeholder.framework.di.Inject
import dev.placeholder.framework.di.PluginScoped
import io.papermc.paper.plugin.lifecycle.event.LifecycleEvent
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEventType
import org.bukkit.plugin.Plugin

/** Typed registration API for Paper lifecycle event keys owned by the current plug-in. */
@Inject
@PluginScoped
public class LifecycleEventRegistry(
    private val plugin: Plugin,
) {
    /** Registers one prioritized synchronous lifecycle handler. */
    public fun <E : LifecycleEvent> on(
        type: LifecycleEventType.Prioritizable<in Plugin, E>,
        priority: Int = 0,
        handler: E.() -> Unit,
    ) {
        plugin.lifecycleManager.registerEventHandler(
            type.newHandler { event -> event.handler() }.priority(priority),
        )
    }

    /** Registers one synchronous lifecycle monitor. */
    public fun <E : LifecycleEvent> monitor(
        type: LifecycleEventType.Monitorable<in Plugin, E>,
        handler: E.() -> Unit,
    ) {
        plugin.lifecycleManager.registerEventHandler(
            type.newHandler { event -> event.handler() }.monitor(),
        )
    }

    /** Registers a monitor for a prioritizable lifecycle event key. */
    public fun <E : LifecycleEvent> monitor(
        type: LifecycleEventType.Prioritizable<in Plugin, E>,
        handler: E.() -> Unit,
    ) {
        plugin.lifecycleManager.registerEventHandler(
            type.newHandler { event -> event.handler() }.monitor(),
        )
    }
}
