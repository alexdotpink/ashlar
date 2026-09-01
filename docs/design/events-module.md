# Events module design

Status: implemented

The events module gives plug-in authors one Kotlin-first module for Bukkit/Paper server events, Paper lifecycle events, and plug-in-local application events. It keeps their contracts distinct. It does not place a generic wrapper over three different dispatch models.

## Goals

- Make static listeners one annotated class with constructor injection.
- Preserve synchronous priority, mutation, and cancellation for live server events.
- Support owned coroutine observers without pretending an event remains live after suspension.
- Make one-off waits, captures, retries, and bounded event streams concise.
- Provide structured suspending publication for immutable application events.
- Use Paper's native typed lifecycle keys.
- Generate only immutable definitions, direct calls, and contribution linkage.

## Non-goals

- The module does not design chat, anvil, sign, GUI, block, or other player-input conversations. A later input module will build those protocols on event capture.
- Application events do not cross Paper plug-in classloaders.
- The module does not infer a global, region, or entity scheduler for arbitrary custom events.
- The first version does not add Paper bootstrap support for tag or datapack lifecycle events.
- The module does not serialize Folia callbacks or add ordering within one Bukkit priority.

## Module shape

Plug-ins opt in through the managed build:

```kotlin
ashlar {
    events()
}
```

The module exposes separate `ServerEvents` and `ApplicationEvents` capabilities. Paper lifecycle configuration receives `LifecycleEventRegistry`. A future module such as input may enable events transitively.

`@Events` implies plug-in-scoped constructor injection and automatic discovery. The event runtime owns registration and unregisters every server listener during component shutdown.

## Static server handlers

```kotlin
@Events
class ProtectionEvents(
    private val claims: ClaimStore,
) {
    @On(
        priority = EventPriority.HIGH,
        ignoreCancelled = true,
    )
    fun BlockBreakEvent.protect() {
        if (!claims.canBuild(player.uniqueId, block.location)) {
            isCancelled = true
        }
    }
}
```

The event is a Kotlin extension receiver. A server handler:

- is synchronous and returns `Unit`;
- uses Bukkit's `EventPriority` and `ignoreCancelled` vocabulary;
- may inspect or mutate the event according to that event's Paper/Folia callback contract;
- runs concurrently and reentrantly whenever the server dispatches it that way;
- receives no hidden lock, queue, or second ordering number.

`@On` defaults to `EventPriority.NORMAL` and includes cancelled events, matching Bukkit. `ignoreCancelled = true` is valid only for a `Cancellable` receiver. `MONITOR` remains available, and its native no-mutation rule applies.

## Coroutine observers

```kotlin
@Events
class JoinEvents(
    private val audit: AuditLog,
) {
    @Observe
    suspend fun PlayerJoinEvent.audit() {
        val record = PlayerJoined(
            player = PlayerRef(player.uniqueId),
            joinedAt = Instant.now(),
        )

        audit.write(record)
    }
}
```

`@Observe` always registers at `EventPriority.MONITOR`, may choose whether to ignore cancelled events, and returns `Unit`. The runtime starts its coroutine undispatched. Code before the first suspension runs inside the live callback and may copy stable values. After the first suspension, the continuation becomes ordinary plug-in-owned coroutine work. The raw event grants no Paper ownership after that boundary, and mutation has no supported effect.

An observer that performs long non-suspending work still blocks the native event callback. It must copy what it needs and reach a genuine suspension promptly.

An observer failure follows the kernel's ordinary task-failure contract.

## Dynamic server handlers

Dynamic synchronous handlers require a component context:

```kotlin
override fun ComponentContext.start() {
    serverEvents.listen<BlockPlaceEvent>(
        priority = EventPriority.HIGH,
        ignoreCancelled = true,
    ) {
        isCancelled = featureDisabled
    }
}
```

The context owns the returned registration automatically. The registration may close early, but cannot outlive its component accidentally.

## Temporal server operations

Temporal operations run a selector synchronously in the event callback and let only its ashlar-owned return value cross a suspension boundary.

### Await

```kotlin
val player = serverEvents.await<PlayerJoinEvent, PlayerRef>(
    within = 30.seconds,
) {
    if (player.hasPlayedBefore()) skip()
    PlayerRef(player.uniqueId)
}
```

`skip()` ignores an unrelated event. Returning a value atomically completes the query. `within` is optional; coroutine cancellation and plug-in shutdown always unregister an unfinished query. Await defaults to `MONITOR` and includes cancelled events.

### Capture

```kotlin
val choice = serverEvents.capture<AsyncChatEvent, ShopChoice>(
    within = 30.seconds,
) {
    if (player.uniqueId != target.uniqueId) skip()

    ShopChoice.parse(plainText.serialize(message())) ?: retry {
        target.tell("Try again.")
    }
}
```

Capture is available only when the event type is both `Event` and `Cancellable`. Returning a value cancels that selected event and completes the query. `retry {}` cancels the selected event, keeps the query registered, and runs its suspending feedback action outside the callback. Capture defaults to `HIGHEST` and ignores events cancelled by an earlier handler.

When Folia callbacks race, selectors keep their native concurrency. The first accepted value wins through an atomic state transition. A callback that loses the race does not cancel its event. Retry actions run sequentially in the waiting coroutine.

### Stream

```kotlin
val movement = serverEvents.stream<PlayerMoveEvent, LocationSnapshot>(
    capacity = 1,
    overflow = BufferOverflow.DROP_OLDEST,
) {
    if (player.uniqueId != target.uniqueId) skip()
    to?.snapshot() ?: skip()
}
```

A server stream requires a positive bounded capacity and either `DROP_OLDEST` or `DROP_LATEST`. Server dispatch cannot suspend for a collector. The selector may run concurrently on Folia, and the resulting Flow reflects native arrival order. Stream defaults to `MONITOR` and includes cancelled events.

A selector failure completes its query or stream exceptionally. It does not cancel the current event unless the selector already mutated it directly.

## Application events

Application events are plug-in-local immutable notification values:

```kotlin
data class HomeCreated(
    val home: HomeSnapshot,
) : ApplicationEvent
```

With `ApplicationEvents` in context, the event publishes itself:

```kotlin
context(events: ApplicationEvents)
suspend fun createHome(home: Home) {
    repository.save(home)
    HomeCreated(home.snapshot()).publish()
}
```

The capability retains an explicit method for adapters and Java:

```kotlin
applicationEvents.publish(HomeCreated(home.snapshot()))
```

Handlers use the same event-set class:

```kotlin
@Events
class HomeEvents(
    private val map: HomeMap,
    private val audit: AuditLog,
) {
    @OnApplication
    fun HomeCreated.updateMap() {
        map.add(home)
    }

    @OnApplication
    suspend fun HomeCreated.audit() {
        audit.record(this)
    }
}
```

Publication has these rules:

- Every handler whose receiver is assignable from the concrete event runs.
- Ordinary and suspending handlers run as independent supervised publication children.
- Publication waits for every matching handler and provides no priority.
- Zero matching handlers is successful.
- No event history is retained or replayed.
- If handlers fail, publication throws one aggregate after all matching handlers finish.
- Caller cancellation cancels unfinished publication children.

Application streams may carry the immutable event directly. They require bounded capacity and an explicit `SUSPEND`, `DROP_OLDEST`, or `DROP_LATEST` overflow policy.

## Paper lifecycle events

Lifecycle event classes do not uniquely identify their Paper lifecycle key. Event sets therefore configure the native typed keys:

```kotlin
@Events
class PluginLifecycleEvents(
    private val commands: HomeCommands,
) {
    @ConfigureLifecycleEvents
    fun LifecycleEventRegistry.configure() {
        on(LifecycleEvents.COMMANDS, priority = 10) {
            registrar().register(commands.command)
        }

        monitor(LifecycleEvents.COMMANDS) {
            commands.recordCompletedRegistration()
        }
    }
}
```

Lifecycle handlers are synchronous. The registry accepts Paper's native `LifecycleEventType` values and exposes only configuration that the key supports. The first version accepts keys valid for the current plug-in owner. Bootstrap-owned tag and datapack keys require a later kernel and managed-build design.

## Event-set inheritance

An event set marked `@Events` must be final or abstract:

```kotlin
@Events
abstract class BaseProtectionEvents {
    @On(priority = EventPriority.HIGH)
    open fun BlockBreakEvent.protect() = Unit
}

class OverworldProtectionEvents : BaseProtectionEvents()
```

Every concrete transitive class descendant of an abstract event-set base contributes automatically. Interfaces do not contribute handler metadata.

An ordinary override keeps the base handler metadata. Repeating the relevant handler annotation replaces its metadata. `@DisableEventHandler` removes one inherited handler. `@DisableEvents` suppresses an entire class branch. A plug-in can exclude library contributions at its entrypoint:

```kotlin
@ExcludeEventContributions(OptionalMetricsEvents::class)
class HomesPlugin : AshlarPlugin()
```

## Failures, shutdown, and interoperability

A synchronous server handler failure goes to a replaceable `ServerEventFailureReporter` with the event set, handler, event type, and cause. Dispatch continues. The runtime cannot roll back mutations made before failure.

Stopping the event runtime prevents new registrations, closes dynamic and static registrations, cancels observers and temporal queries, closes streams, and waits through the kernel's normal bounded task drain.

Application events stay inside one framework plug-in classloader. Cross-plug-in contracts use custom Bukkit/Paper events. The module can listen to any valid custom `Event`, but it does not wrap `PluginManager.callEvent`; the publisher knows which kernel ownership context the custom event requires.

## Code generation boundary

Event KSP generates one small KotlinPoet binding per concrete event set. A binding contains immutable handler definitions, contribution linkage, and direct function calls. It generates no registration, dispatch, coroutine, Flow, failure, lifecycle, or publication behavior.

KSP rejects invalid handler receivers, return types, suspension combinations, cancellation flags, visibility, inheritance conflicts, and unsupported class shapes during compilation.

## Verification boundary

The server-free event test runtime exercises generated bindings, filtering, cancellation, observers, application publication, temporal operations, cleanup, and failures. It does not claim Bukkit `HandlerList`, Paper lifecycle-manager, or Folia ownership behavior.

Real Paper and Folia fixtures verify registration, unregistration, native priority, cancellation, custom events, lifecycle keys, callback ownership, parallel region dispatch, and plug-in disable. A playable sample demonstrates every public syntax before the module is considered complete.

## Implementation history

The module was delivered in these independently verified slices:

1. Static `@On` server handlers, DI discovery, ownership, failure reporting, and focused tests.
2. `@Observe`, dynamic context-owned listeners, and shutdown behavior.
3. Receiver-style `await`, `capture`, and `stream`.
4. Application event publication and application streams.
5. Native-key lifecycle configuration and inherited event sets.

Each slice must compile and run independently before the next begins.
