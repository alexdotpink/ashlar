# Wait for and collect server events

## Wait for one projected value

Use `await` when the event is a signal and must not be cancelled:

```kotlin
val joined = serverEvents.await<PlayerJoinEvent, PlayerRef>(within = 30.seconds) {
    if (player.hasPlayedBefore()) skip()
    PlayerRef(player.uniqueId)
}
```

The selector runs in the event callback. Return an ID, snapshot, reference, string, or another ashlar-owned value; do not return the raw event.

## Capture player input

Use `capture` when accepting or rejecting input must cancel a cancellable event:

```kotlin
val answer = serverEvents.capture<AsyncChatEvent, Boolean>(within = 30.seconds) {
    if (player.uniqueId != target.uniqueId) skip()

    when (plainText.serialize(message()).lowercase()) {
        "yes" -> true
        "no" -> false
        else -> retry { messenger.tell(target, "Reply with yes or no.") }
    }
}
```

`skip()` leaves an unrelated event untouched. A successful result cancels that event. `retry` also cancels it, keeps the capture alive, and runs feedback after the callback. Timeout and coroutine cancellation clean up the listener automatically.

## Collect a bounded stream

Use `stream` for several values:

```kotlin
val messages = serverEvents.stream<AsyncChatEvent, String>(
    capacity = 8,
    overflow = BufferOverflow.DROP_OLDEST,
) {
    if (player.uniqueId != target.uniqueId) skip()
    plainText.serialize(message())
}.take(3).toList()
```

Always choose capacity and overflow from the consumer's needs. A live server callback cannot suspend, so server streams allow only `DROP_OLDEST` and `DROP_LATEST`. Cancelling or completing collection unregisters the listener.
