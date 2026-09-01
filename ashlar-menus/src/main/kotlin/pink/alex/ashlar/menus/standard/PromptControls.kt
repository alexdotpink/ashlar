package pink.alex.ashlar.menus.standard

import pink.alex.ashlar.execution.PlayerRef
import pink.alex.ashlar.input.ChatAnswer
import pink.alex.ashlar.input.InputConflict
import pink.alex.ashlar.input.InputDecision
import pink.alex.ashlar.input.PlayerInput
import pink.alex.ashlar.input.accept
import pink.alex.ashlar.input.retry
import pink.alex.ashlar.items.ItemSpec
import pink.alex.ashlar.menus.ChestScope
import pink.alex.ashlar.menus.input.focusedChatInput
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import net.kyori.adventure.text.Component

/**
 * Declares a menu control backed by one typed ashlar-input chat prompt.
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
