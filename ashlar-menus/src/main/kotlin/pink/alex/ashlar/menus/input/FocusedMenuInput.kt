package pink.alex.ashlar.menus.input

import pink.alex.ashlar.execution.PlayerRef
import pink.alex.ashlar.input.ChatAnswer
import pink.alex.ashlar.input.InputConflict
import pink.alex.ashlar.input.InputDecision
import pink.alex.ashlar.input.PlayerInput
import pink.alex.ashlar.menus.MenuActionScope
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import net.kyori.adventure.text.Component

/**
 * Hides the active menu, runs one typed chat prompt, then restores the latest menu render.
 * Runtime use requires the framework input capability to be installed with `input()`.
 */
public suspend fun <T> MenuActionScope.focusedChatInput(
    input: PlayerInput,
    player: PlayerRef,
    prompt: Component,
    idleTimeout: Duration? = 30.seconds,
    conflict: InputConflict = InputConflict.REJECT,
    expiredMessage: Component? = null,
    conflictMessage: Component? = null,
    parser: ChatAnswer.() -> InputDecision<T>,
): T = withFocusedInput {
    input.chat(
        player = player,
        prompt = prompt,
        idleTimeout = idleTimeout,
        conflict = conflict,
        expiredMessage = expiredMessage,
        conflictMessage = conflictMessage,
        parser = parser,
    )
}
