package dev.placeholder.framework.input.internal

import dev.placeholder.framework.ComponentContext
import dev.placeholder.framework.ComponentPhase
import dev.placeholder.framework.FrameworkComponent
import dev.placeholder.framework.PluginComponent
import dev.placeholder.framework.di.Inject
import dev.placeholder.framework.events.ServerEvents
import dev.placeholder.framework.events.listen
import dev.placeholder.framework.input.PlayerInput
import io.papermc.paper.event.player.AsyncChatEvent
import org.bukkit.event.EventPriority
import org.bukkit.event.player.PlayerQuitEvent

/** Owns the Paper listeners used by typed player input. */
@FrameworkComponent(name = "input", phase = ComponentPhase.FRAMEWORK)
@Inject
public class InputRuntimeComponent(
    private val serverEvents: ServerEvents,
    private val playerInput: PlayerInput,
) : PluginComponent() {
    override fun ComponentContext.start() {
        serverEvents.listen<AsyncChatEvent>(
            priority = EventPriority.HIGHEST,
            ignoreCancelled = true,
        ) {
            if (playerInput.dispatch(player.uniqueId, message())) isCancelled = true
        }
        serverEvents.listen<PlayerQuitEvent> {
            playerInput.disconnect(player.uniqueId)
        }
    }

    override fun ComponentContext.stop() {
        playerInput.close()
    }
}
