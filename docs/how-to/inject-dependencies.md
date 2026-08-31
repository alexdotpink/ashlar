# Inject dependencies

Use constructor injection for framework components, command sets, codecs, policies, observers, and application services.

## Add a generated constructor

```kotlin
@Inject
@PluginScoped
class HomeRepository(
    private val database: Database,
)
```

`@PluginScoped` shares one instance in the plug-in graph. `@InvocationScoped` shares one instance inside a command invocation. `@Factory` creates a new instance at each injection point. The default is plug-in scope.

## Bind a root component to an interface

```kotlin
interface HomeStore

@FrameworkComponent(name = "home-store")
@Inject
@Binds(HomeStore::class)
class SqlHomeStore(
    private val database: Database,
) : PluginComponent(), HomeStore
```

`@Binds` currently applies to automatically installed root components. It exposes that installed component through the listed interfaces. For an ordinary injected class, request its concrete type or add an explicit external binding in `FrameworkPlugin.configure`.

## Contribute an extension

```kotlin
@Contributes
@Inject
class HomeArgumentCodec(
    private val store: HomeStore,
) : CommandArgumentCodec<Home>
```

Contributions form deterministic multibindings. Runtime modules ask for every implementation of contracts such as `CommandArgumentCodec`, `CommandObserver`, or `CommandResponseCodec`.

## Qualify a dependency

```kotlin
@DependencyQualifier
annotation class ReadReplica

@Inject
class Reports(
    @ReadReplica private val database: Database,
)
```

The graph requires a matching qualified binding. Dependency qualifiers and command argument qualifiers are separate mechanisms.

The graph rejects cycles, duplicate bindings, invocation dependencies requested from plug-in scope, and classes without a generated factory.
