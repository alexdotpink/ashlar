package dev.placeholder.framework.menus.input

import dev.placeholder.framework.execution.PlayerRef
import dev.placeholder.framework.input.ChatAnswer
import dev.placeholder.framework.input.InputConflict
import dev.placeholder.framework.input.InputDecision
import dev.placeholder.framework.input.PlayerInput
import dev.placeholder.framework.menus.MenuActionScope
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
