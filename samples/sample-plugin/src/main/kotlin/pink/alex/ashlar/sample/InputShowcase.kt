package pink.alex.ashlar.sample

import pink.alex.ashlar.commands.Commands
import pink.alex.ashlar.execution.PlayerRef
import pink.alex.ashlar.input.InputConflict
import pink.alex.ashlar.input.PlayerInput
import pink.alex.ashlar.input.accept
import pink.alex.ashlar.input.cancel
import pink.alex.ashlar.input.pass
import pink.alex.ashlar.input.retry
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor

@Commands(name = "input", aliases = ["in"])
internal class InputShowcaseCommands(
    private val playerInput: PlayerInput,
) {
    /** Captures yes or no, retries invalid chat, and accepts cancel as user cancellation. */
    suspend fun choose(player: PlayerRef): String {
        val answer = playerInput.chat(
            player,
            Component.text("Reply yes, no, or cancel.", NamedTextColor.GOLD),
        ) {
            when (text.lowercase()) {
                "yes" -> accept(true)
                "no" -> accept(false)
                "cancel" -> cancel()
                else -> retry(Component.text("Please reply yes, no, or cancel.", NamedTextColor.RED))
            }
        }
        return "Accepted $answer."
    }

    /** Passes ordinary chat and accepts only text beginning with answer:. */
    suspend fun prefixed(player: PlayerRef): String = playerInput.chat(
        player,
        "Send public chat, then answer:<value>.",
    ) {
        if (!text.startsWith("answer:")) pass() else accept(text.removePrefix("answer:"))
    }

    /** Starts a long-lived prompt used to demonstrate conflict rejection. */
    suspend fun wait(player: PlayerRef): String = playerInput.chat(
        player,
        "This prompt is active. Commands still work; reply done when finished.",
        idleTimeout = 2.minutes,
    ) {
        if (text == "done") accept("Finished the waiting prompt.") else retry("Reply done to finish.")
    }

    /** Explicitly replaces the player's active prompt. */
    suspend fun replace(player: PlayerRef): String = playerInput.chat(
        player,
        "The previous prompt was replaced. Reply replacement.",
        conflict = InputConflict.REPLACE,
    ) {
        if (text == "replacement") accept("Replacement accepted.") else retry("Reply replacement.")
    }

    /** Atomically cancels the selected player's active prompt. */
    fun cancelActive(player: PlayerRef): String =
        if (playerInput.cancel(player)) "Cancelled the active prompt." else "No prompt is active."

    /** Starts a three-second prompt with custom expiry feedback. */
    suspend fun timeout(player: PlayerRef): String = playerInput.chat(
        player,
        "Reply quickly; this expires after three idle seconds.",
        idleTimeout = 3.seconds,
        expiredMessage = "The sample prompt expired as expected.",
    ) {
        accept("Answered before expiry: $text")
    }

    /** Composes two typed prompts with ordinary Kotlin control flow. */
    suspend fun multiStep(player: PlayerRef): String {
        val name = playerInput.chat(player, "Choose a name.") {
            text.trim().takeIf(String::isNotEmpty)?.accept() ?: retry("The name cannot be blank.")
        }
        val confirmed = playerInput.chat(player, "Create '$name'? Reply yes or no.") {
            when (text.lowercase()) {
                "yes" -> accept(true)
                "no" -> accept(false)
                else -> retry("Reply yes or no.")
            }
        }
        return if (confirmed) "Created '$name'." else "Did not create '$name'."
    }

    /** Proves slash commands remain available while a chat prompt is active. */
    fun ping(): String = "Input commands still work."
}
