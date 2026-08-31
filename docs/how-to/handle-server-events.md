# Handle server events

## Declare a synchronous handler

Use `@On` when code must inspect, cancel, or mutate the live event before native dispatch continues:

```kotlin
@Events
class ProtectionEvents(private val claims: ClaimStore) {
    @On(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun BlockBreakEvent.protect() {
        if (!claims.canBuild(player.uniqueId, block.location)) isCancelled = true
    }
}
```

Keep this function synchronous. Its receiver is live Paper state and its callback may run concurrently on Folia.

## Observe without delaying dispatch

Use `@Observe` for owned coroutine work. Copy stable input before the first suspension:

```kotlin
@Observe
suspend fun PlayerJoinEvent.audit() {
    val joined = PlayerJoined(PlayerRef(player.uniqueId), Instant.now())
    audit.write(joined)
}
```

The code that builds `joined` runs inside the callback. After `audit.write` suspends, do not read or mutate the raw event or its Paper-owned objects. Re-enter an explicit ownership context if later work needs server state.

## Register a component-local listener

Use a dynamic listener when registration depends on component state:

```kotlin
class SessionComponent(
    private val serverEvents: ServerEvents,
    private val sessions: SessionStore,
) : PluginComponent() {
    override fun ComponentContext.start() {
        serverEvents.listen<PlayerQuitEvent> {
            sessions.remove(player.uniqueId)
        }
    }
}
```

The component owns the registration. Stopping it unregisters the listener even if startup later rolls back.

Use `@On` for fixed plug-in behavior, `@Observe` for asynchronous follow-up, and `listen` only when the listener lifetime is genuinely dynamic.
