package pink.alex.ashlar.input

import pink.alex.ashlar.di.DependencyGraph
import pink.alex.ashlar.di.Inject
import pink.alex.ashlar.di.PluginScoped
import pink.alex.ashlar.execution.EntityOutcome
import pink.alex.ashlar.execution.PlayerRef
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.plugin.Plugin

/** Plug-in-scoped capability for typed player input prompts. */
@PluginScoped
public class PlayerInput private constructor(
    private val delivery: InputDelivery,
    private val messages: InputMessages,
) {
    @Inject
    public constructor(plugin: Plugin, graph: DependencyGraph) : this(
        PaperInputDelivery(plugin),
        graph.contributions(InputMessages::class).singleOrNull() ?: EnglishInputMessages,
    )

    private val prompts: ConcurrentHashMap<UUID, ActivePrompt<*>> = ConcurrentHashMap()

    /** Starts one typed chat prompt using plain messages. */
    public suspend fun <T> chat(
        player: PlayerRef,
        prompt: String,
        idleTimeout: Duration? = 30.seconds,
        conflict: InputConflict = InputConflict.REJECT,
        expiredMessage: String? = null,
        conflictMessage: String? = null,
        parser: ChatAnswer.() -> InputDecision<T>,
    ): T = chat(
        player = player,
        prompt = Component.text(prompt),
        idleTimeout = idleTimeout,
        conflict = conflict,
        expiredMessage = expiredMessage?.let(Component::text),
        conflictMessage = conflictMessage?.let(Component::text),
        parser = parser,
    )

    /** Starts one typed chat prompt using Adventure messages. */
    public suspend fun <T> chat(
        player: PlayerRef,
        prompt: Component,
        idleTimeout: Duration? = 30.seconds,
        conflict: InputConflict = InputConflict.REJECT,
        expiredMessage: Component? = null,
        conflictMessage: Component? = null,
        parser: ChatAnswer.() -> InputDecision<T>,
    ): T {
        require(idleTimeout == null || idleTimeout.isPositive()) {
            "A chat prompt idle timeout must be positive or null"
        }

        val active = ActivePrompt(parser)
        install(player, active, conflict, conflictMessage)
        try {
            deliver(player) { prompt }
            while (true) {
                val signal = receive(player, active, idleTimeout, expiredMessage)
                when (signal) {
                    is PromptSignal.Accepted -> return signal.value
                    is PromptSignal.Retry -> {
                        if (active.isActive()) deliver(player) { signal.feedback }
                    }
                    is PromptSignal.Cancelled -> throw InputCancellationException(signal.reason)
                    is PromptSignal.Failed -> throw signal.cause
                }
            }
        } finally {
            prompts.remove(player.uniqueId, active)
            active.deactivate()
        }
    }

    /** Atomically cancels the player's active prompt, if one exists. */
    public fun cancel(player: PlayerRef): Boolean =
        removeAndCancel(player.uniqueId, InputCancellationReason.EXTERNALLY_CANCELLED)

    internal fun dispatch(
        playerId: UUID,
        component: Component,
    ): Boolean {
        val prompt = prompts[playerId] ?: return false
        val answer = DefaultChatAnswer(
            PlainTextComponentSerializer.plainText().serialize(component),
            component,
        )
        val outcome = prompt.attempt(answer)
        if (outcome.terminal) prompts.remove(playerId, prompt)
        return outcome.consumed
    }

    internal fun disconnect(playerId: UUID): Unit {
        removeAndCancel(playerId, InputCancellationReason.PLAYER_DISCONNECTED)
    }

    internal fun close(): Unit {
        prompts.entries.toList().forEach { (playerId, prompt) ->
            if (prompts.remove(playerId, prompt)) {
                prompt.cancel(InputCancellationReason.PLUGIN_STOPPED)
            }
        }
    }

    private suspend fun <T> install(
        player: PlayerRef,
        prompt: ActivePrompt<T>,
        conflict: InputConflict,
        conflictMessage: Component?,
    ) {
        when (conflict) {
            InputConflict.REJECT -> {
                if (prompts.putIfAbsent(player.uniqueId, prompt) != null) {
                    deliver(player) { locale -> conflictMessage ?: messages.conflict(locale) }
                    throw InputConflictException(player)
                }
            }
            InputConflict.REPLACE -> {
                prompts.put(player.uniqueId, prompt)?.cancel(InputCancellationReason.REPLACED)
            }
        }
    }

    private suspend fun <T> receive(
        player: PlayerRef,
        prompt: ActivePrompt<T>,
        idleTimeout: Duration?,
        expiredMessage: Component?,
    ): PromptSignal<T> {
        if (idleTimeout == null) return prompt.receive()
        val received = withTimeoutOrNull(idleTimeout) { prompt.receive() }
        if (received != null) return received

        if (prompts.remove(player.uniqueId, prompt)) {
            prompt.cancel(InputCancellationReason.IDLE_TIMEOUT)
            deliver(player) { locale -> expiredMessage ?: messages.expired(locale) }
            throw InputCancellationException(InputCancellationReason.IDLE_TIMEOUT)
        }
        return prompt.receive()
    }

    private suspend fun deliver(
        player: PlayerRef,
        message: (Locale) -> Component,
    ) {
        if (!delivery.send(player, message)) {
            removeAndCancel(player.uniqueId, InputCancellationReason.PLAYER_DISCONNECTED)
            throw InputCancellationException(InputCancellationReason.PLAYER_DISCONNECTED)
        }
    }

    private fun removeAndCancel(
        playerId: UUID,
        reason: InputCancellationReason,
    ): Boolean {
        val prompt = prompts.remove(playerId) ?: return false
        prompt.cancel(reason)
        return true
    }

    internal companion object {
        fun testing(delivery: InputDelivery, messages: InputMessages = EnglishInputMessages): PlayerInput =
            PlayerInput(delivery, messages)
    }
}

internal fun interface InputDelivery {
    suspend fun send(player: PlayerRef, message: (Locale) -> Component): Boolean
}

private class PaperInputDelivery(
    private val plugin: Plugin,
) : InputDelivery {
    override suspend fun send(
        player: PlayerRef,
        message: (Locale) -> Component,
    ): Boolean = when (player.access(plugin) { resolved -> resolved.sendMessage(message(resolved.locale())) }) {
        is EntityOutcome.Completed -> true
        EntityOutcome.Retired -> false
    }
}

private data class DefaultChatAnswer(
    override val text: String,
    override val component: Component,
) : ChatAnswer

private class ActivePrompt<T>(
    private val parser: ChatAnswer.() -> InputDecision<T>,
) {
    private val signals: Channel<PromptSignal<T>> = Channel(Channel.UNLIMITED)
    private var active: Boolean = true

    fun attempt(answer: ChatAnswer): AttemptOutcome = synchronized(this) {
        if (!active) {
            return@synchronized AttemptOutcome(terminal = false, consumed = false)
        }
        val decision = runCatching { answer.parser() }
            .getOrElse { failure ->
                active = false
                signals.trySend(PromptSignal.Failed(failure))
                return@synchronized AttemptOutcome(terminal = true, consumed = true)
            }
        when (decision) {
            is InputDecision.Accepted -> {
                active = false
                signals.trySend(PromptSignal.Accepted(decision.value))
                AttemptOutcome(terminal = true, consumed = true)
            }
            is InputDecision.Retry -> {
                signals.trySend(PromptSignal.Retry(decision.feedback))
                AttemptOutcome(terminal = false, consumed = true)
            }
            InputDecision.Cancel -> {
                active = false
                signals.trySend(PromptSignal.Cancelled(InputCancellationReason.USER_CANCELLED))
                AttemptOutcome(terminal = true, consumed = true)
            }
            InputDecision.Pass -> AttemptOutcome(terminal = false, consumed = false)
        }
    }

    fun cancel(reason: InputCancellationReason): Unit = synchronized(this) {
        if (!active) return
        active = false
        signals.trySend(PromptSignal.Cancelled(reason))
    }

    fun deactivate(): Unit = synchronized(this) {
        active = false
        signals.close()
    }

    fun isActive(): Boolean = synchronized(this) { active }

    suspend fun receive(): PromptSignal<T> = signals.receive()
}

private data class AttemptOutcome(
    val terminal: Boolean,
    val consumed: Boolean,
)

private sealed interface PromptSignal<out T> {
    data class Accepted<T>(val value: T) : PromptSignal<T>

    data class Retry(val feedback: Component) : PromptSignal<Nothing>

    data class Cancelled(val reason: InputCancellationReason) : PromptSignal<Nothing>

    data class Failed(val cause: Throwable) : PromptSignal<Nothing>
}
