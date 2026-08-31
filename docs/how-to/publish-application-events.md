# Publish application events

Use application events for immutable notifications between features inside one plug-in.

## Define and publish a value

```kotlin
data class HomeCreated(val home: HomeSnapshot) : ApplicationEvent

class HomeService(private val events: ApplicationEvents) {
    suspend fun create(home: Home) {
        repository.save(home)
        events.publish(HomeCreated(home.snapshot()))
    }
}
```

Publication completes after every matching ordinary and suspending handler finishes.

## Handle it in an event set

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

Handlers have no priority and run as independent children. A handler on a base application-event interface receives its assignable subtypes. If one or more handlers fail, the publisher receives `ApplicationEventException` after the others finish.

Use an application event for plug-in-local state changes. Use a custom Bukkit/Paper event when another plug-in must observe the notification.
