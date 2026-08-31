package dev.placeholder.framework.sample

import dev.placeholder.framework.ComponentContext
import dev.placeholder.framework.FrameworkComponent
import dev.placeholder.framework.PluginComponent
import dev.placeholder.framework.commands.Commands
import dev.placeholder.framework.commands.reject
import dev.placeholder.framework.commands.reference.PlayerRef
import dev.placeholder.framework.di.Inject
import dev.placeholder.framework.di.PluginScoped
import dev.placeholder.framework.events.ApplicationEvent
import dev.placeholder.framework.events.ApplicationEvents
import dev.placeholder.framework.events.ConfigureLifecycleEvents
import dev.placeholder.framework.events.Events
import dev.placeholder.framework.events.LifecycleEventRegistry
import dev.placeholder.framework.events.Observe
import dev.placeholder.framework.events.On
import dev.placeholder.framework.events.OnApplication
import dev.placeholder.framework.events.ServerEvents
import dev.placeholder.framework.events.capture
import dev.placeholder.framework.events.listen
import dev.placeholder.framework.events.retry
import dev.placeholder.framework.events.skip
import dev.placeholder.framework.events.stream
import dev.placeholder.framework.execution.withGlobal
import io.papermc.paper.event.player.AsyncChatEvent
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.event.Event
import org.bukkit.event.HandlerList
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.Plugin
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@Inject
@PluginScoped
internal class EventShowcaseState {
    private val events = CopyOnWriteArrayList<String>()

    fun record(value: String) {
        events += value
    }

    fun snapshot(): List<String> = events.toList()
}

@Events
internal class SampleEvents(
    private val plugin: Plugin,
    private val state: EventShowcaseState,
) {
    @On
    internal fun PlayerJoinEvent.recordJoin() {
        state.record("join:${player.name}")
    }

    @Observe
    internal suspend fun PlayerJoinEvent.welcome() {
        val player = PlayerRef(player.uniqueId)
        delay(500.milliseconds)
        player.access(plugin) { resolved ->
            resolved.sendMessage(
                Component.text("Framework events are ready. ", NamedTextColor.GREEN)
                    .append(
                        Component.text("Open /events", NamedTextColor.GOLD)
                            .clickEvent(ClickEvent.runCommand("/events")),
                    ),
            )
        }
    }

    @On
    internal fun SampleCustomEvent.recordCustom() {
        state.record("custom:$value")
    }

    @OnApplication
    internal fun SampleApplicationEvent.recordApplication() {
        state.record("application:$value")
    }

    @OnApplication
    internal suspend fun SampleApplicationEvent.recordApplicationAsync() {
        delay(10.milliseconds)
        state.record("application-suspend:$value")
    }

    @ConfigureLifecycleEvents
    internal fun LifecycleEventRegistry.configureSampleEvents() {
        on(LifecycleEvents.COMMANDS, priority = -100) {
            state.record("lifecycle:commands")
        }
    }
}

@Events
internal class ExcludedSampleEvents(
    private val state: EventShowcaseState,
) {
    @On
    internal fun PlayerJoinEvent.mustNotRun() {
        state.record("excluded")
    }
}

@FrameworkComponent(name = "dynamic-events")
@Inject
internal class DynamicEventComponent(
    private val serverEvents: ServerEvents,
    private val state: EventShowcaseState,
) : PluginComponent() {
    override fun ComponentContext.start() {
        serverEvents.listen<PlayerQuitEvent> {
            state.record("quit:${player.name}")
        }
    }
}

@Commands(name = "events", aliases = ["ev"])
internal class EventShowcaseCommands(
    private val plugin: Plugin,
    private val serverEvents: ServerEvents,
    private val applicationEvents: ApplicationEvents,
    private val state: EventShowcaseState,
) {
    /** Lists event activity observed by the showcase. */
    fun state(): String = state.snapshot().joinToString().ifBlank { "No event activity yet." }

    /** Publishes one structured plug-in-local application event. */
    suspend fun publish(value: String): String {
        applicationEvents.publish(SampleApplicationEvent(value))
        return "Published '$value' to every application handler."
    }

    /** Calls one custom Bukkit event in the global ownership context. */
    suspend fun custom(value: String): String {
        plugin.withGlobal { plugin.server.pluginManager.callEvent(SampleCustomEvent(value)) }
        return "Called custom server event '$value'."
    }

    /** Captures the selected player's next valid yes or no chat message. */
    suspend fun choose(player: PlayerRef): String {
        val answer = serverEvents.capture<AsyncChatEvent, String>(within = 30.seconds) {
            if (this.player.uniqueId != player.uniqueId) skip()
            val message = PlainTextComponentSerializer.plainText().serialize(message()).lowercase()
            message.takeIf { it == "yes" || it == "no" } ?: retry {
                player.access(plugin) { resolved ->
                    resolved.sendMessage(Component.text("Reply with yes or no.", NamedTextColor.RED))
                }
            }
        }
        return "Captured '$answer'."
    }

    /** Collects a bounded number of selected-player chat messages without cancelling them. */
    suspend fun collect(player: PlayerRef, count: Int = 2): String {
        val expected = count.takeIf { it in 1..5 } ?: reject("Count must be from 1 through 5.")
        val messages = serverEvents.stream<AsyncChatEvent, String>(
            capacity = expected,
            overflow = BufferOverflow.DROP_OLDEST,
        ) {
            if (this.player.uniqueId != player.uniqueId) skip()
            PlainTextComponentSerializer.plainText().serialize(message())
        }.take(expected).toList()
        return "Collected: ${messages.joinToString()}"
    }
}

internal data class SampleApplicationEvent(val value: String) : ApplicationEvent

internal class SampleCustomEvent(val value: String) : Event() {
    override fun getHandlers(): HandlerList = HANDLERS

    companion object {
        private val HANDLERS = HandlerList()

        @JvmStatic
        fun getHandlerList(): HandlerList = HANDLERS
    }
}
