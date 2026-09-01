# Kernel reference

This page describes the stable kernel concepts and their runtime contracts.

## `AshlarPlugin`

`AshlarPlugin` extends Paper's `JavaPlugin`. Its `onLoad`, `onEnable`, and `onDisable` methods are final.

Available hooks:

- `PluginLoadContext.load()` runs during Paper load. Runtime components, tasks, and owned resources are unavailable.
- `ComponentContext.enable()` runs after every root component starts.
- `ComponentContext.disable()` runs after task cancellation and bounded draining, but before components stop.

The same plug-in instance cannot run a second lifecycle. Replacing or reloading its JAR requires a server restart.

## Plug-in components

`PluginComponent` is the base class for a stateful part of a plug-in. The component object is also the capability used by its parent or plug-in.

Declare components with a delegated factory:

```kotlin
private val database by component {
    Database(databaseConfig)
}

private val homes by component {
    Homes(database)
}
```

Factories are deferred until enable. The kernel constructs and fully starts an earlier sibling before evaluating the next factory. A delegate is unavailable before successful start and after stop.

Components may declare child components. Startup walks children in declaration order before the parent. Shutdown calls the parent first and then children in reverse declaration order. If construction or `start()` fails, the kernel runs best-effort rollback and fails plug-in enable. A component whose `start()` was entered also receives `stop()` during rollback.

Root components can also be discovered through DI:

```kotlin
@AshlarComponent(
    name = "homes",
    phase = ComponentPhase.APPLICATION,
)
@Inject
class HomesComponent(
    private val repository: HomeRepository,
) : PluginComponent()
```

Application roots start before framework roots. Within a phase, generated constructor dependencies determine a stable topological order. A dependency cycle, duplicate root name, or invalid root type fails startup. Use `@ExcludeContributions` on the plug-in entrypoint to remove selected auto-installed roots.

`start()` and `stop()` are synchronous. A component must be safe to use when `start()` returns. Background warm-up may improve performance but cannot establish correctness.

## Component context

`ComponentContext` provides:

- the owning Paper `Plugin` and `Server`
- Paper's Adventure `ComponentLogger`
- the slash-separated component path
- `task` and `criticalTask`
- `own` for synchronous `AutoCloseable` resources

`AshlarPlugin.configure` receives the `DependencyGraph` after generated contributions load and before automatic roots are constructed. Use it for external instances or deliberate overrides. The graph already contains the plug-in as its concrete class, `AshlarPlugin`, and Paper `Plugin`, plus the `Server` and graph itself.

Each component owns a supervised coroutine scope below its parent. An unnamed task uses the component path as its coroutine name. A named task appends its name to that path.

An ordinary uncaught task failure is reported and does not cancel siblings. An uncaught `criticalTask` failure reports the error and requests safe plug-in disable on the global scheduler. Coroutine cancellation is not reported as failure.

## Shutdown

Disable performs these operations in order:

1. Prevent new tasks and owned resources.
2. Cancel the root coroutine scope and every component child scope.
3. Wait up to two seconds by default for cooperative completion.
4. Run the plug-in disable hook.
5. Stop root components in reverse order, with each parent stopping before its children.
6. Close remaining owned resources in reverse registration order.

Plug-ins may override the drain duration. After the limit, the kernel reports unfinished tasks and continues cleanup.

## Ownership contexts

`PlayerRef` is a stable UUID-backed player identity shared by commands, events, and input. `access(plugin)` resolves the current player and runs one non-suspending block inside its entity ownership context, returning `EntityOutcome.Retired` when the player is unavailable.

The kernel exposes these suspend functions on Paper `Plugin`:

- `withGlobal { ... }`
- `withRegion(location) { ... }`
- `withRegion(world, chunkX, chunkZ) { ... }`
- `withEntity(entity) { ... }`

Each block is non-suspending and represents one atomic scheduler callback. If the caller already owns the target, the block runs inline. Otherwise the caller suspends while the kernel schedules the block, then resumes in its previous coroutine context.

`withEntity` returns `EntityOutcome.Completed(value)` or `EntityOutcome.Retired`. Retirement means Paper could not run the callback because the entity became unavailable. The outcome may be ignored when retirement requires no action, or handled with `onRetired`.

Ashlar and module operations may use context parameters to require ownership:

```kotlin
context(entityContext: EntityContext)
fun Player.sendAshlarMessage(message: Component) {
    entityContext.checkOwnership()
    sendMessage(message)
}
```

The named context must call `checkOwnership()` immediately before touching Paper. This revalidates a context value that a callback may have captured. An anonymous context parameter alone proves lexical availability, not current runtime ownership.

Direct Paper methods and schedulers are outside the framework's safety guarantee.

`GlobalContext`, `RegionContext`, and `EntityContext` are capability values created only by the ownership functions. `RegionContext` exposes its world and chunk coordinates. `EntityContext` exposes the live entity for the duration of its callback.

## Failure vocabulary

- Ordinary absence uses nullable values.
- Expected scheduler races use sealed outcomes such as `EntityOutcome`.
- Violated framework contracts throw exceptions such as `OwnershipViolationException`.
- Failures thrown by an ownership block propagate to its suspended caller.
- Parent coroutine cancellation remains ordinary coroutine cancellation.

## Custom failure reporting

Override `taskFailureReporter(logger)` to route uncaught task failures to another sink. `TaskFailure` contains the component path, optional task name, critical flag, and cause. A reporter must not assume it is running in a Paper ownership context.
