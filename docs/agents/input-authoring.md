# Input authoring workflow for agents

Use this sequence when a plug-in needs player input.

## 1. Start with one typed prompt

Inject `PlayerInput` and call `chat`. Return a domain value through `accept`, not a raw string that another layer reparses.

## 2. Keep the parser synchronous

Use `ChatAnswer.text` for ordinary decoding and `component` only when rich content matters. Return `accept`, `retry`, `cancel`, or `pass` before the parser exits. Put database, network, and other suspending validation between prompt calls in ordinary Kotlin.

## 3. Choose consumption deliberately

Use `retry` for an answer intended for the prompt but invalid. Use `pass` only when that chat should remain public and should not reset idle time. Slash commands already bypass chat input.

## 4. Keep the safe conflict default

Leave `InputConflict.REJECT` unless the new feature intentionally takes ownership from an existing prompt. Use `REPLACE` only for a visible handoff. Never build a second queue around `PlayerInput`.

## 5. Let cancellation propagate

Normal commands and tasks should not catch `InputCancellationException`. When observing one reason, rethrow the same exception. Use `playerInput.cancel(player)` for an explicit plug-in-owned cancel command or menu action.

## 6. Test through the input fixture

Use `InputTestHarness` for acceptance, retry feedback, passed chat, conflict, replacement, cancellation, disconnect, and virtual-time expiry. Use a real Paper server for native chat cancellation and delivery. Run Folia when ownership or callback concurrency changes. Use a real client for player-visible prompts and command availability during an active prompt.

The feature is complete when the accepted answer, invalid answer, expected cancellation, conflict behavior, and cleanup path have matching evidence.
