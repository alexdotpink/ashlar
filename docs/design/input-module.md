# Input module design

Status: implemented

The input module turns server events into typed player prompts. It owns chat interception, retry feedback, conflicts, deadlines, disconnects, cancellation, and cleanup while leaving multi-step conversations as ordinary suspending Kotlin.

## Goals

- Make one typed player answer one suspending call.
- Keep multi-step branching, loops, and domain state in Kotlin.
- Consume accepted, rejected, and cancelled chat without blocking the server callback.
- Let unrelated chat pass through when the parser chooses.
- Protect one active prompt from accidental replacement by another feature.
- Give expected unanswered endings typed cancellation reasons.
- Make prompt behavior testable without constructing Paper events.

## Non-goals

- The first version does not implement anvil, sign, book, GUI, block, or command input.
- The module does not provide a conversation workflow, state machine, or named-step DSL.
- The module does not reserve a `/cancel` command or chat keyword.
- The module does not perform suspending database or network validation inside a parser.
- The module does not coordinate prompts between different Paper plug-ins or classloaders.
- The module does not introduce KSP or annotations.

## Module shape

Plug-ins opt in through the managed build:

```kotlin
ashlar {
    input()
}
```

This enables the event module transitively. Plug-in code injects one plug-in-scoped `PlayerInput` capability. The first version exposes concrete chat input only. Future channels become sibling operations after their real contracts are understood:

```kotlin
playerInput.chat(player, prompt) { ... }

// Possible later additions, not part of version one:
playerInput.anvil(player, prompt) { ... }
playerInput.sign(player, initialLines) { ... }
playerInput.book(player, initialPages) { ... }
```

There is no generic `InputChannel` adapter in version one.

## Primary interface

The normal call is one capability method with named options and one parser receiver:

```kotlin
val name: WaypointName = playerInput.chat(
    player = player,
    prompt = "What should the waypoint be called?",
    idleTimeout = 30.seconds,
    conflict = InputConflict.REJECT,
    expiredMessage = "Waypoint naming expired.",
    conflictMessage = "Finish your current prompt first.",
) {
    when {
        text.equals("cancel", ignoreCase = true) -> cancel()
        else -> WaypointName.parse(text)?.accept()
            ?: retry("That name is invalid.")
    }
}
```

The common call omits every option:

```kotlin
val confirmed: Boolean = playerInput.chat(
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

Prompts, retry feedback, and lifecycle-message overrides accept plain `String` values or Adventure `Component` values. String conveniences delegate to the Component form.

`PlayerInput` also exposes one atomic external operation:

```kotlin
val cancelled: Boolean = playerInput.cancel(player)
```

It cancels the player's active prompt if one exists. The module exposes no separate `isActive` check and no mutable prompt handle.

## Chat answer and decisions

The non-suspending parser has a `ChatAnswer` receiver:

```kotlin
interface ChatAnswer {
    val text: String
    val component: Component
}
```

The original Adventure component remains available without making ordinary text parsing verbose. The parser must return one `InputDecision<T>` before the live chat callback ends:

| Decision | Chat behavior | Prompt behavior |
| --- | --- | --- |
| `accept(value)` | Cancelled | Completes with `value` |
| `retry(feedback)` | Cancelled | Sends feedback, resets idle timeout, and waits again |
| `cancel()` | Cancelled | Ends with user-cancelled input |
| `pass()` | Not cancelled | Keeps waiting without resetting idle timeout |

Unexpected parser failure cancels the attempted chat, ends the prompt, and reaches the framework failure reporter. This prevents an answer intended for a prompt from becoming public because its parser failed.

The parser cannot suspend. Database and network validation stays in ordinary Kotlin between prompts:

```kotlin
while (true) {
    val name = playerInput.chat(player, "Waypoint name?") {
        WaypointName.parse(text)?.accept()
            ?: retry("That name is malformed.")
    }

    if (store.isAvailable(name)) break
    messenger.tell(player, "'$name' is already taken.")
}
```

## Commands while prompted

Normal Minecraft clients send `/...` through command dispatch rather than chat events. Commands therefore remain available and cannot become chat answers while a prompt is active. `PlayerInput.chat` does not listen to command preprocessing and exposes no command-interception toggle.

## Active-prompt conflicts

One prompt may be active per player inside one framework plug-in. A prompt in another Paper plug-in is outside this embedded framework instance and cannot be coordinated globally.

`InputConflict.REJECT` is the default. A concurrent call sends the configured conflict message, fails immediately, and leaves the active prompt intact. `InputConflict.REPLACE` explicitly cancels the old prompt with reason `REPLACED` before activating and delivering the new prompt. Prompts are never queued.

The event registration becomes active before the module sends the initial prompt, so an immediate player answer cannot race registration.

## Idle timeout

Chat prompts use a 30-second idle timeout by default. Callers may supply another duration or explicitly remove the idle timeout. A consumed retry resets the timer. Accepted, cancelled, and passed chat do not reset it.

`withTimeout` around the suspending call supplies an absolute cap when a feature needs one:

```kotlin
val answer = withTimeout(2.minutes) {
    playerInput.chat(
        player,
        prompt,
        idleTimeout = 30.seconds,
    ) { ... }
}
```

## Completion and cancellation

A successful prompt returns `T` directly. An unanswered prompt throws `InputCancellationException`, a `CancellationException` with one typed `InputCancellationReason`:

- `USER_CANCELLED`
- `EXTERNALLY_CANCELLED`
- `IDLE_TIMEOUT`
- `PLAYER_DISCONNECTED`
- `REPLACED`
- `PLUGIN_STOPPED`

Ordinary callers do not catch it. Callers that care about one ending may inspect the reason. Cancellation of the owning coroutine also closes the prompt registration immediately and preserves normal structured-concurrency behavior.

Starting a second prompt under `REJECT` throws a distinct expected conflict exception because the new prompt never became active.

## Messages

An injected `InputMessages` catalogue supplies replaceable default messages for idle expiry and active-prompt conflict. `expiredMessage` and `conflictMessage` are direct optional overrides on `chat`; there is no per-call message catalogue or builder.

The module sends:

- the initial prompt after registration;
- the parser's feedback after `retry` and outside the live chat callback;
- expiry feedback when the idle timeout wins;
- conflict feedback when a new prompt is rejected.

Player disconnect and plug-in shutdown are silent. Parser `cancel()` carries no built-in wording because the plug-in owns its cancellation language. Delivery enters the player's current entity ownership context and tolerates retirement races.

## Lifecycle and concurrency

`PlayerInput` and its active-prompt registry are plug-in-scoped. Each prompt is owned by its caller's coroutine and by the input runtime for cleanup. Player disconnect, explicit cancellation, replacement, caller cancellation, or plug-in shutdown atomically removes the prompt and closes its event registrations.

Matching chat callbacks retain Paper and Folia's native concurrency. Per-player state uses an atomic transition so exactly one decision wins. Retry feedback runs outside the event callback. The parser receives only ashlar-owned text and Component values; raw Paper events never enter suspended code.

## Testing interface

The module ships a dedicated server-free test kit:

```kotlin
inputTest {
    val pending = async {
        playerInput.chat(alex, "Yes or no?") {
            when (text) {
                "yes" -> accept(true)
                else -> retry("Try again.")
            }
        }
    }

    answer(alex, "wat")
    assertMessage(alex, "Try again.")

    answer(alex, "yes")
    assertTrue(pending.await())
}
```

The test kit supplies simulated players, chat attempts, passed messages, disconnects, conflicts, cancellation, captured delivery, and virtual time. Plug-in tests cross the same `PlayerInput` interface as production code; they do not construct `AsyncChatEvent`.

Real Paper and Folia fixtures verify native chat cancellation, command availability during a prompt, delivery ownership, disconnect races, and plug-in shutdown. The playable sample demonstrates accept, retry, cancel, pass, rejection, replacement, timeout, external cancellation, rich messages, and multi-step Kotlin composition. A real 26.2 client completes the final acceptance checklist.

## Documentation and agent use

The module ships task-oriented how-to pages, complete reference, an agent-authoring workflow, test guidance, KDoc, ABI baselines, and sample links. Agent guidance leads with the typed prompt syntax and tells agents to keep suspending domain validation between prompts.

## Implementation slices

Implementation remains deliberately small:

1. Fix the sample's hard-coded help header and add its regression test.
2. Add `ashlar-input`, managed `input()`, core types, conflict registry, cancellation, messages, and focused server-free tests.
3. Add the Paper/Folia chat adapter and real-server integration fixtures.
4. Add the dedicated public test kit.
5. Add the playable sample, complete docs, ABI baselines, and real-client acceptance run.

Each slice must pass independently before the next begins. Anvil, sign, book, generic channels, conversations, persistence, and command interception remain outside this module.
