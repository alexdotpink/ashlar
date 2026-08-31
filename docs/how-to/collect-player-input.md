# Collect typed player input

## Ask for one typed answer

Inject `PlayerInput`, then parse one player's chat synchronously:

```kotlin
val radius: Int = playerInput.chat(player, "Search radius?") {
    text.toIntOrNull()
        ?.takeIf { it in 1..512 }
        ?.accept()
        ?: retry("Enter a number from 1 through 512.")
}
```

Accepted and retried chat is cancelled. The call returns the accepted integer.

## Let unrelated chat pass

Use `pass()` when only marked chat belongs to the prompt:

```kotlin
val answer = playerInput.chat(player, "Reply answer:<value> when ready.") {
    if (!text.startsWith("answer:")) pass()
    else accept(text.removePrefix("answer:"))
}
```

Ordinary chat remains public and does not reset the idle timeout.

## Compose a conversation

Use normal Kotlin for multiple prompts and suspending domain validation:

```kotlin
while (true) {
    val name = playerInput.chat(player, "Waypoint name?") {
        WaypointName.parse(text)?.accept()
            ?: retry("That name is malformed.")
    }

    if (!store.isAvailable(name)) {
        messenger.tell(player, "'$name' is already taken.")
        continue
    }

    val confirmed = playerInput.chat(player, "Create '$name'? Reply yes or no.") {
        when (text.lowercase()) {
            "yes" -> accept(true)
            "no" -> accept(false)
            else -> retry("Reply yes or no.")
        }
    }

    if (confirmed) store.create(player, name)
    break
}
```

Database work occurs between prompts. Parsers stay non-suspending so Paper receives the cancellation decision in the live callback.

## Handle an ending only when needed

Most callers let `InputCancellationException` cancel the surrounding command or task. Catch one reason only when the feature has useful work to do:

```kotlin
try {
    playerInput.chat(player, prompt) { parseAnswer(text) }
} catch (cancelled: InputCancellationException) {
    if (cancelled.reason == InputCancellationReason.REPLACED) {
        audit.recordPromptReplacement(player)
    }
    throw cancelled
}
```

Rethrow cancellation after observation so structured cancellation remains intact.
