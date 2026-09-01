# Input reference

Enable typed player input through the managed build:

```kotlin
ashlar {
    input()
}
```

This adds `ashlar-input` and enables events transitively. Input has no annotation processor. Plug-in code injects the plug-in-scoped `PlayerInput` capability.

## Chat prompts

`PlayerInput.chat<T>` sends one prompt and suspends until its synchronous parser accepts a `T`:

```kotlin
val confirmed = playerInput.chat(
    player,
    "Delete this waypoint? Reply yes or no.",
) {
    when (text.lowercase()) {
        "yes" -> accept(true)
        "no" -> accept(false)
        else -> retry("Please reply yes or no.")
    }
}
```

The String overload converts prompts and optional lifecycle overrides to Adventure components. The Component overload preserves styling, hover events, and click events.

The complete option set is:

| Parameter | Default | Meaning |
| --- | --- | --- |
| `player` | Required | Kernel-owned stable `PlayerRef` |
| `prompt` | Required | String or Adventure Component sent after registration |
| `idleTimeout` | 30 seconds | Inactivity before expiry; `null` removes the idle timeout |
| `conflict` | `REJECT` | Behavior when this plug-in already prompts the player |
| `expiredMessage` | Catalogue default | Per-prompt String or Component expiry copy |
| `conflictMessage` | Catalogue default | Per-prompt String or Component conflict copy |
| `parser` | Required | Non-suspending `ChatAnswer.() -> InputDecision<T>` |

A consumed retry restarts `idleTimeout`. Passed chat does not. Use Kotlin `withTimeout` around the call when a feature needs an absolute duration.

## Chat answer

`ChatAnswer` exposes:

- `text`, decoded with Adventure's plain serializer;
- `component`, the original Adventure chat component.

The receiver contains no Paper event or live player object. The parser cannot suspend because it decides chat cancellation before Paper's callback returns.

## Input decisions

| Helper | Chat | Prompt |
| --- | --- | --- |
| `accept(value)` or `value.accept()` | Cancelled | Returns `value` |
| `retry(feedback)` | Cancelled | Sends String or Component feedback and waits again |
| `cancel()` | Cancelled | Throws user-cancelled `InputCancellationException` |
| `pass()` | Unchanged | Continues waiting without resetting idle time |

An unexpected parser exception consumes the attempted chat and reaches the caller. This prevents an intended private answer from becoming public after validation code fails.

Slash commands do not produce chat events. They remain available while a prompt is active and can never become chat answers.

## Conflicts

One prompt may be active per player inside one framework plug-in. `InputConflict.REJECT` sends conflict feedback and throws `InputConflictException` without disturbing the first prompt. `InputConflict.REPLACE` cancels the old prompt with reason `REPLACED` and installs the new prompt. Input never queues prompts.

Each Paper plug-in embeds its own registry. The module does not coordinate prompts across unrelated plug-in classloaders.

## Cancellation

Successful prompts return `T`. An unanswered prompt throws `InputCancellationException`, a coroutine `CancellationException` carrying one `InputCancellationReason`:

| Reason | Cause |
| --- | --- |
| `USER_CANCELLED` | Parser returned `cancel()` |
| `EXTERNALLY_CANCELLED` | `playerInput.cancel(player)` won |
| `IDLE_TIMEOUT` | Idle timeout expired |
| `PLAYER_DISCONNECTED` | Player quit or could not receive delivery |
| `REPLACED` | A prompt explicitly replaced this one |
| `PLUGIN_STOPPED` | Input runtime closed during shutdown |

`PlayerInput.cancel(player)` atomically cancels an active prompt and returns whether one existed. There is no `isActive` check or public prompt handle. The module reserves no command or chat keyword for cancellation.

## Messages and localization

`InputMessages` supplies locale-aware `expired` and `conflict` components. Contribute one replacement implementation through DI; zero contributions use `EnglishInputMessages`, and more than one fails input construction. Per-prompt overrides replace only the corresponding message.

The input runtime sends the initial prompt, retry feedback, expiry feedback, and rejected-conflict feedback. Disconnect and plug-in shutdown are silent. Delivery resolves the current player and enters its entity ownership context.

## Lifecycle

The automatically discovered input runtime owns its Paper chat and quit listeners. Caller cancellation removes the prompt immediately. Disconnect, external cancellation, replacement, idle expiry, and shutdown use atomic per-player transitions so exactly one ending wins under concurrent Folia callbacks.

## Testing

`InputTestHarness` runs the production `PlayerInput` logic without a server. It creates deterministic player references, dispatches String or Component answers, records whether chat was consumed, simulates disconnect and reconnect, captures delivered and passed components, and closes active prompts.

Virtual time comes from the caller's `kotlinx-coroutines-test` scope because the prompt uses the caller coroutine for its idle timeout. See [Testing APIs](testing.md).
