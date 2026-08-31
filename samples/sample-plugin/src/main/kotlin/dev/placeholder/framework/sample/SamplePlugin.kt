package dev.placeholder.framework.sample

import dev.placeholder.framework.ComponentContext
import dev.placeholder.framework.FrameworkPlugin
import dev.placeholder.framework.PluginComponent
import dev.placeholder.framework.commands.ExcludeCommandContributions
import kotlinx.coroutines.delay
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.event.EventHandler
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import kotlin.time.Duration.Companion.milliseconds

/** Runnable command-module showcase for Paper and Folia. */
@ExcludeCommandContributions(ExcludedShowcaseCommands::class)
public class SamplePlugin : FrameworkPlugin() {
    private val welcome by component { ShowcaseWelcome() }

    override fun ComponentContext.enable() {
        logger.info("Command showcase enabled; use /showcase or /sc")
    }

    override fun ComponentContext.disable() {
        logger.info("Command showcase stopped")
    }
}

private class ShowcaseWelcome : PluginComponent() {
    override fun ComponentContext.start() {
        val listener = object : Listener {
            @EventHandler
            fun onJoin(event: PlayerJoinEvent) {
                val player = event.player
                task("welcome-${player.uniqueId}") {
                    delay(500.milliseconds)
                    if (!player.isOnline) return@task
                    player.sendMessage(
                        Component.text("Framework command showcase is ready. ", NamedTextColor.GREEN)
                            .append(
                                Component.text("Open /showcase", NamedTextColor.GOLD)
                                    .clickEvent(ClickEvent.runCommand("/showcase")),
                            ),
                    )
                }
            }
        }
        plugin.server.pluginManager.registerEvents(listener, plugin)
        own(AutoCloseable { HandlerList.unregisterAll(listener) })
    }
}
