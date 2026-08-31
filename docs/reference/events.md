# Events reference

Enable events in the managed Gradle extension:

```kotlin
frameworkPlugin {
    events()
}
```

This adds `framework-events` and its focused KSP processor. The processor emits one small direct-call binding per concrete event set. Registration, dispatch, coroutines, Flow, failure handling, and shutdown remain handwritten runtime code.

## Event families

The module keeps three dispatch models separate:

| Family | Declaration | Dispatch contract |
| --- | --- | --- |
| Bukkit/Paper server events | `@On`, `@Observe`, `ServerEvents` | Native synchronous callback, priorities, and cancellation |
| Plug-in-local application events | `ApplicationEvent`, `@OnApplication`, `ApplicationEvents` | Structured suspending publication inside one plug-in |
| Paper lifecycle events | `@ConfigureLifecycleEvents`, `LifecycleEventRegistry` | Native typed lifecycle keys owned by the plug-in |

## Event sets

`@Events` marks a constructor-injected, automatically discovered event set. A concrete event set must be final. An abstract event set may define inherited handlers; every concrete transitive subclass is discovered automatically.

Functions without an event annotation are ordinary implementation details. Handler functions may be non-public because generated bindings make direct calls.

`@ExcludeEventContributions(A::class, B::class)` on the `FrameworkPlugin` subclass excludes selected generated event sets from server registration, lifecycle configuration, and application publication.

## Synchronous server handlers

`@On` marks a non-suspending `Event` extension function returning `Unit`:

```kotlin
@Events
class ProtectionEvents(private val claims: ClaimStore) {
    @On(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun BlockBreakEvent.protect() {
        if (!claims.canBuild(player.uniqueId, block.location)) isCancelled = true
    }
}
```

`priority` defaults to `NORMAL`. `ignoreCancelled` defaults to `false`, meaning the handler still receives cancelled events. `ignoreCancelled = true` is valid only for `Cancellable` receivers. Handlers run with Paper's native concurrency and reentrancy; the framework adds no lock or ordering inside a priority.

A synchronous failure is sent to `ServerEventFailureReporter` with a `ServerEventFailure`. Contribute or bind a replacement reporter to change logging. Dispatch continues, but mutations made before the failure cannot be rolled back.

## Coroutine observers

`@Observe` marks a suspending `Event` extension function returning `Unit`. It always registers at `MONITOR` and may set `ignoreCancelled`.

The observer starts undispatched. Code before its first genuine suspension runs inside the live callback, which is the place to project IDs, snapshots, and immutable values. After suspension, it is ordinary plug-in-owned coroutine work: retaining the raw event does not retain Paper or Folia ownership, and later mutation has no supported effect. Failures follow the kernel task-failure contract.

## Dynamic handlers

`ServerEvents.listen<E>` registers a synchronous handler and returns `EventRegistration`. It requires a `ComponentContext`; the current component automatically owns and closes the registration:

```kotlin
override fun ComponentContext.start() {
    serverEvents.listen<PlayerQuitEvent> {
        sessions.remove(player.uniqueId)
    }
}
```

Call `close()` only when the listener must end before its component.

## Temporal queries

Temporal selectors run synchronously in the native callback. Only the selected framework or application value crosses the suspension boundary.

### `await`

`await<E, R>` waits for the first selector result. It defaults to `MONITOR`, includes cancelled events, and accepts an optional Kotlin `Duration` timeout. Call `skip()` to ignore the current event. Selector failure completes the call exceptionally. Cancellation, timeout, and plug-in shutdown unregister the listener.

### `capture`

`capture<E, R>` is available when `E` is both `Event` and `Cancellable`. It defaults to `HIGHEST` and ignores already-cancelled events. Returning a value cancels that event and completes the call. `skip()` ignores it without cancellation. `retry { feedback() }` cancels it, keeps waiting, and executes suspending feedback outside the callback. The first accepted value wins when callbacks race; retry actions are sequential.

### `stream`

`stream<E, R>` creates a cold `Flow<R>` and requires a positive `capacity` plus `DROP_OLDEST` or `DROP_LATEST`. `SUSPEND` is rejected because a server callback may never wait for a collector. It defaults to `MONITOR` and includes cancelled events. `skip()` filters an event. Cancelling collection unregisters the listener.

## Application events

An application event implements the marker interface and should be immutable:

```kotlin
data class HomeCreated(val home: HomeSnapshot) : ApplicationEvent
```

`@OnApplication` marks an ordinary or suspending extension function whose receiver implements `ApplicationEvent`. `ApplicationEvents.publish(event)` runs every handler whose receiver type is assignable from the concrete event. Handlers are independent supervised children; publication waits for all of them. There is no priority and zero handlers is success.

If handlers fail, publication waits for the remaining handlers and throws `ApplicationEventException`. Its `failures` contain `ApplicationEventHandlerFailure` values with handler identity and cause. Caller cancellation cancels unfinished children.

With an `ApplicationEvents` context receiver, `event.publish()` is equivalent to the explicit call.

`ApplicationEvents.stream<E>(capacity, overflow)` returns a bounded, non-replaying Flow. All three `BufferOverflow` policies are valid: `SUSPEND` backpressures `publish`, while drop policies do not. Streams and handlers see assignable subtypes.

Application events remain inside one framework plug-in and classloader. Use a custom Bukkit/Paper `Event` for cross-plug-in interoperability.

## Paper lifecycle events

`@ConfigureLifecycleEvents` marks a synchronous `LifecycleEventRegistry` extension function:

```kotlin
@ConfigureLifecycleEvents
fun LifecycleEventRegistry.configure() {
    on(LifecycleEvents.COMMANDS, priority = 10) {
        registrar().register(command)
    }
    monitor(LifecycleEvents.COMMANDS) {
        audit.completed()
    }
}
```

`on` accepts a native `Prioritizable` key and integer priority. `monitor` accepts native `Monitorable` and `Prioritizable` keys. The current release supports keys owned by the running plug-in; bootstrap-owned tag and datapack keys are outside this API.

## Inheritance controls

An ordinary override retains the inherited handler annotation and metadata. Repeating `@On`, `@Observe`, `@OnApplication`, or `@ConfigureLifecycleEvents` replaces that metadata. `@DisableEventHandler` removes one inherited handler. `@DisableEvents` suppresses an entire descendant branch.

Interfaces do not contribute handler metadata. Event-set inheritance is intended for a small shared policy base, not for runtime handler composition.

## Shutdown

The event runtime owns static and dynamic registrations, observers, temporal queries, application streams, and `ApplicationEvents`. Plug-in shutdown unregisters listeners, closes streams, cancels unfinished coroutine work, and drains tasks through the kernel's normal bounded shutdown.

## Testing boundary

`EventTestHarness` executes generated and dynamic server handlers in priority order, cancellation filtering, observers, application publication, temporal operations, and failures without a server. It does not emulate Bukkit `HandlerList`, Paper's lifecycle manager, or Folia ownership. See [Testing APIs](testing.md).
