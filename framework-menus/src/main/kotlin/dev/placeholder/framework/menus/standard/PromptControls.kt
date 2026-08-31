package dev.placeholder.framework.menus.standard

import dev.placeholder.framework.execution.PlayerRef
import dev.placeholder.framework.input.ChatAnswer
import dev.placeholder.framework.input.InputConflict
import dev.placeholder.framework.input.InputDecision
import dev.placeholder.framework.input.PlayerInput
import dev.placeholder.framework.input.accept
import dev.placeholder.framework.input.retry
import dev.placeholder.framework.items.ItemSpec
import dev.placeholder.framework.menus.ChestScope
import dev.placeholder.framework.menus.input.focusedChatInput
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import net.kyori.adventure.text.Component

/**
 * Declares a menu control backed by one typed framework-input chat prompt.
 * Runtime use requires the input capability to be installed with `input()`.
 */
context(chest: ChestScope)
public fun <T> promptControl(
    slot: Int,
    item: ItemSpec,
    input: PlayerInput,
    player: PlayerRef,
    prompt: Component,
    idleTimeout: Duration? = 30.seconds,
    conflict: InputConflict = InputConflict.REJECT,
    parser: ChatAnswer.() -> InputDecision<T>,
    onAccepted: suspend (T) -> Unit,
) {
    promptControlImpl(chest, slot, item, input, player, prompt, idleTimeout, conflict, parser, onAccepted)
}

private fun <T> promptControlImpl(
    chest: ChestScope,
    slot: Int,
    item: ItemSpec,
    input: PlayerInput,
    player: PlayerRef,
    prompt: Component,
    idleTimeout: Duration?,
    conflict: InputConflict,
    parser: ChatAnswer.() -> InputDecision<T>,
    onAccepted: suspend (T) -> Unit,
) {
    chest.slot(slot) {
        this.item = item
        onPrimary {
            val value = focusedChatInput(
                input = input,
                player = player,
                prompt = prompt,
                idleTimeout = idleTimeout,
                conflict = conflict,
                parser = parser,
            )
            onAccepted(value)
        }
    }
}

/** Declares a trimmed non-blank textual search prompt. */
context(chest: ChestScope)
public fun searchControl(
    slot: Int,
    item: ItemSpec,
    input: PlayerInput,
    player: PlayerRef,
    prompt: Component,
    blankFeedback: Component,
    idleTimeout: Duration? = 30.seconds,
    onSearch: suspend (String) -> Unit,
) {
    promptControlImpl(
        chest,
        slot,
        item,
        input,
        player,
        prompt,
        idleTimeout,
        InputConflict.REJECT,
        parser = {
            text.trim().takeIf(String::isNotEmpty)
                ?.let(::accept)
                ?: retry(blankFeedback)
        },
        onAccepted = onSearch,
    )
}
