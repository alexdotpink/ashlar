package dev.placeholder.framework.input

import dev.placeholder.framework.execution.PlayerRef
import java.util.Locale
import kotlinx.coroutines.CancellationException
import net.kyori.adventure.text.Component

/** Policy used when a player already has an active input prompt. */
public enum class InputConflict {
    /** Preserve the active prompt and reject the new call. */
    REJECT,

    /** Cancel the active prompt before installing the new one. */
    REPLACE,
}

/** Expected reason why an input prompt ended without an answer. */
public enum class InputCancellationReason {
    USER_CANCELLED,
    EXTERNALLY_CANCELLED,
    IDLE_TIMEOUT,
    PLAYER_DISCONNECTED,
    REPLACED,
    PLUGIN_STOPPED,
}

/** Expected cancellation of an input prompt before it accepted an answer. */
public class InputCancellationException(
    public val reason: InputCancellationReason,
) : CancellationException("Input prompt ended: $reason")

/** Expected rejection of a new prompt because another prompt is active. */
public class InputConflictException(
    public val player: PlayerRef,
) : CancellationException("Player ${player.uniqueId} already has an active input prompt")

/** Framework-owned chat value examined by a synchronous input parser. */
public interface ChatAnswer {
    public val text: String
    public val component: Component
}

/** One synchronous decision for an attempted input answer. */
public sealed interface InputDecision<out T> {
    public data class Accepted<T>(public val value: T) : InputDecision<T>

    public data class Retry(public val feedback: Component) : InputDecision<Nothing>

    public data object Cancel : InputDecision<Nothing>

    public data object Pass : InputDecision<Nothing>
}

/** Accepts [value], consumes the attempted chat, and completes the prompt. */
public fun <T> accept(value: T): InputDecision<T> = InputDecision.Accepted(value)

/** Accepts this value, consumes the attempted chat, and completes the prompt. */
@JvmName("acceptInputValue")
public fun <T> T.accept(): InputDecision<T> = accept(this)

/** Consumes the attempted chat, sends [feedback], and waits for another answer. */
public fun retry(feedback: Component): InputDecision<Nothing> = InputDecision.Retry(feedback)

/** Consumes the attempted chat, sends plain [feedback], and waits again. */
public fun retry(feedback: String): InputDecision<Nothing> = retry(Component.text(feedback))

/** Consumes the attempted chat and ends the prompt as user-cancelled. */
public fun cancel(): InputDecision<Nothing> = InputDecision.Cancel

/** Leaves the attempted chat untouched and keeps waiting. */
public fun pass(): InputDecision<Nothing> = InputDecision.Pass

/** Replaceable player-facing messages for input lifecycle outcomes. */
public interface InputMessages {
    public fun expired(locale: Locale): Component

    public fun conflict(locale: Locale): Component
}

/** English fallback used when a plug-in contributes no [InputMessages]. */
public data object EnglishInputMessages : InputMessages {
    override fun expired(locale: Locale): Component = Component.text("That prompt expired.")

    override fun conflict(locale: Locale): Component = Component.text("Finish your current prompt first.")
}
