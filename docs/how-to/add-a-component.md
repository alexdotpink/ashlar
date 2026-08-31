# Add a lifecycle component

Use a component for state that owns startup, shutdown, tasks, children, or closeable resources.

## Declare an explicit child

```kotlin
class HomesComponent(
    private val repository: HomeRepository,
) : PluginComponent() {
    private val cache by component { HomeCache(repository) }

    override fun ComponentContext.start() {
        val watcher = repository.watch()
        own(watcher)
        task("warm cache") { cache.warm() }
    }

    override fun ComponentContext.stop() {
        cache.flush()
    }
}
```

The child starts before its parent. Shutdown calls the parent's `stop()`, then stops children in reverse declaration order, then closes remaining resources in reverse registration order.

## Install an injected root automatically

```kotlin
@FrameworkComponent(name = "homes")
@Inject
class HomesComponent(
    private val repository: HomeRepository,
) : PluginComponent()
```

KSP adds this component to the generated root index. `ComponentPhase.APPLICATION` roots start before `ComponentPhase.FRAMEWORK` roots such as command registration.

Use `@Binds(HomeService::class)` on the component when other dependencies request an interface it implements.

## Choose ordinary or critical tasks

Use `task` when one failed job should be reported without cancelling siblings. Use `criticalTask` when an uncaught failure makes the plug-in unsafe to keep enabled. Both task types are cancelled during plug-in shutdown.

The component is complete when its public capability is ready as soon as `start()` returns and every acquired resource has one `own()` call or child owner.
