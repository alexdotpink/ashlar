package dev.placeholder.framework.input.testing

import dev.placeholder.framework.execution.PlayerRef
import dev.placeholder.framework.input.EnglishInputMessages
import dev.placeholder.framework.input.InputDelivery
import dev.placeholder.framework.input.InputMessages
import dev.placeholder.framework.input.PlayerInput
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer

/** Result of one simulated chat answer. */
public data class InputTestAttempt(
    public val component: Component,
    public val consumed: Boolean,
)

/** Server-free fixture for the public [PlayerInput] contract. */
public class InputTestHarness(
    messages: InputMessages = EnglishInputMessages,
) : AutoCloseable {
    private val delivery = TestInputDelivery()

    /** The production input capability driven by this fixture. */
    public val playerInput: PlayerInput = PlayerInput.testing(delivery, messages)

    /** Creates a deterministic online player reference for [name]. */
    public fun player(
        name: String,
        locale: Locale = Locale.US,
    ): PlayerRef {
        require(name.isNotBlank()) { "A test player name must not be blank" }
        val reference = PlayerRef(
            UUID.nameUUIDFromBytes("framework-input:$name".toByteArray(StandardCharsets.UTF_8)),
        )
        delivery.connect(reference, locale)
        return reference
    }

    /** Dispatches plain chat through the active prompt for [player]. */
    public fun answer(
        player: PlayerRef,
        text: String,
    ): InputTestAttempt = answer(player, Component.text(text))

    /** Dispatches rich chat through the active prompt for [player]. */
    public fun answer(
        player: PlayerRef,
        component: Component,
    ): InputTestAttempt {
        val consumed = playerInput.dispatch(player.uniqueId, component)
        return InputTestAttempt(component, consumed).also { attempt ->
            if (!attempt.consumed) delivery.pass(player, component)
        }
    }

    /** Simulates a player disconnect and cancels their active prompt. */
    public fun disconnect(player: PlayerRef) {
        delivery.disconnect(player)
        playerInput.disconnect(player.uniqueId)
    }

    /** Makes a previously disconnected test player available again. */
    public fun reconnect(
        player: PlayerRef,
        locale: Locale = Locale.US,
    ) {
        delivery.connect(player, locale)
    }

    /** Returns every component delivered to [player] in order. */
    public fun messages(player: PlayerRef): List<Component> = delivery.messages(player)

    /** Returns delivered messages converted with Adventure's plain serializer. */
    public fun plainMessages(player: PlayerRef): List<String> =
        messages(player).map(PlainTextComponentSerializer.plainText()::serialize)

    /** Returns chat attempts that the parser explicitly passed through. */
    public fun passed(player: PlayerRef): List<Component> = delivery.passed(player)

    /** Clears captured delivery and passed-chat history for [player]. */
    public fun clear(player: PlayerRef) {
        delivery.clear(player)
    }

    override fun close() {
        playerInput.close()
        delivery.close()
    }
}

private class TestInputDelivery : InputDelivery, AutoCloseable {
    private class PlayerState(
        var locale: Locale,
        var online: Boolean = true,
        val messages: MutableList<Component> = mutableListOf(),
        val passed: MutableList<Component> = mutableListOf(),
    )

    private val players: ConcurrentHashMap<UUID, PlayerState> = ConcurrentHashMap()

    override suspend fun send(
        player: PlayerRef,
        message: (Locale) -> Component,
    ): Boolean {
        val state = players[player.uniqueId] ?: return false
        return synchronized(state) {
            if (!state.online) return@synchronized false
            state.messages += message(state.locale)
            true
        }
    }

    fun connect(player: PlayerRef, locale: Locale) {
        players.compute(player.uniqueId) { _, existing ->
            existing?.apply {
                this.locale = locale
                online = true
            } ?: PlayerState(locale)
        }
    }

    fun disconnect(player: PlayerRef) {
        players[player.uniqueId]?.let { state -> synchronized(state) { state.online = false } }
    }

    fun pass(player: PlayerRef, component: Component) {
        players[player.uniqueId]?.let { state -> synchronized(state) { state.passed += component } }
    }

    fun messages(player: PlayerRef): List<Component> = players[player.uniqueId]
        ?.let { state -> synchronized(state) { state.messages.toList() } }
        .orEmpty()

    fun passed(player: PlayerRef): List<Component> = players[player.uniqueId]
        ?.let { state -> synchronized(state) { state.passed.toList() } }
        .orEmpty()

    fun clear(player: PlayerRef) {
        players[player.uniqueId]?.let { state ->
            synchronized(state) {
                state.messages.clear()
                state.passed.clear()
            }
        }
    }

    override fun close() {
        players.clear()
    }
}
