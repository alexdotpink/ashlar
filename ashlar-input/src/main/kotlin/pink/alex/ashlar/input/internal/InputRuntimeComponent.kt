package pink.alex.ashlar.input.internal

import pink.alex.ashlar.ComponentContext
import pink.alex.ashlar.ComponentPhase
import pink.alex.ashlar.AshlarComponent
import pink.alex.ashlar.PluginComponent
import pink.alex.ashlar.di.Inject
import pink.alex.ashlar.events.ServerEvents
import pink.alex.ashlar.events.listen
import pink.alex.ashlar.input.PlayerInput
import io.papermc.paper.event.player.AsyncChatEvent
import org.bukkit.event.EventPriority
import org.bukkit.event.player.PlayerQuitEvent

/** Owns the Paper listeners used by typed player input. */
@AshlarComponent(name = "input", phase = ComponentPhase.FRAMEWORK)
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
