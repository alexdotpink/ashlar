# Dependency injection reference

The dependency graph is one plug-in-owned graph. KSP generates constructor calls; the runtime resolves lifetimes, qualifiers, contributions, and invocation children. It does not use reflection to construct application classes.

## Constructor factories

Annotate a class and one constructor with `@Inject`:

```kotlin
@Inject
@PluginScoped
class HomeRepository(
    private val database: Database,
)
```

KSP creates a direct `DependencyFactory`. Requesting the class through `DependencyResolver.get(HomeRepository::class)` constructs it according to its lifetime.

| Annotation | Lifetime |
| --- | --- |
| `@PluginScoped` | One instance per plug-in graph |
| `@InvocationScoped` | One instance per command invocation child graph |
| `@Factory` | A new instance at each resolution |
| none | Plug-in-scoped |

A plug-in-scoped dependency cannot depend on an invocation-scoped dependency. Cycles, duplicate bindings, missing factories, unsupported generic injection types, and lifetime violations fail with a descriptive exception.

## Generic dependencies

Ashlar keys a dependency by its complete closed type. For example, `Repository<Player>` and `Repository<Waypoint>` are different dependencies even though both use the `Repository` class. Generated factories retain nested arguments and use the same `DependencyKey` for ordering and lookup:

```kotlin
@Inject
class HomeService(
    private val homes: Repository<Home>,
    private val owners: Repository<PlayerProfile>,
)
```

Generic arguments must be closed, invariant, and non-null. KSP rejects stars such as `Repository<*>`, unresolved type parameters such as `Repository<T>`, use-site variance, and nullable nested arguments. Generic classes with an injectable constructor are also rejected because Ashlar cannot choose a closed argument for their generated factory. Bind a closed instance instead.

Framework integrations that bind a generic dependency construct its structural type explicitly:

```kotlin
val key = DependencyKey<Repository<Home>>(
    DependencyType(
        rawType = Repository::class,
        arguments = listOf(DependencyType<Home>(Home::class)),
    ),
)

graph.bind(key, repository)
```

Plug-in constructors normally do not build keys. KSP emits them. The `KClass` overloads of `get`, `bind`, and `bindDefault` remain the shorter API for non-parameterized types.

## Automatic root components

```kotlin
@AshlarComponent(name = "homes")
@Binds(HomeService::class)
@Inject
class HomesComponent(
    private val repository: HomeRepository,
) : PluginComponent(), HomeService
```

`@AshlarComponent` adds a `PluginComponent` to the generated root index. Application roots start before framework roots. Dependencies determine a stable topological order within a phase. `@Binds` is currently consumed only on these root components and exposes the installed instance through the listed interface types.

For an ordinary injected class, request its concrete type. To expose an externally constructed object through an interface, bind it explicitly in `AshlarPlugin.configure`:

```kotlin
override fun DependencyGraph.configure() {
    val store = SqlHomeStore(database)
    bind(store, listOf(HomeStore::class))
}
```

## Contributions

`@Contributes` adds an injected implementation to a deterministic multibinding:

```kotlin
@Contributes
@Inject
class HomeCodec(
    private val homes: HomesComponent,
) : CommandArgumentCodec<Home>
```

Use `DependencyResolver.contributions(Contract::class)` to obtain every assignable contribution. Ashlar modules use this for command sets, codecs, policies, observers, response codecs, help, and messages.

`@ExcludeContributions(types = [Type::class])` on the plug-in entrypoint removes selected generated root or DI contributions. `@ExcludeCommandContributions` is the command-specific equivalent.

## Qualifiers

Create a dependency qualifier as a meta-annotation:

```kotlin
@DependencyQualifier
annotation class ReadReplica

@Inject
class ReportService(
    @ReadReplica private val database: Database,
)
```

The graph key is `(DependencyType, qualifier KClass?)`. A matching qualified binding must exist. Qualifiers distinguish equal structural types; they do not erase generic arguments. Command argument qualifiers use `@CommandArgumentQualifier` and are a separate system.

## Graph API

`DependencyGraph` supports `bind`, qualified `bind`, `bindDefault`, `get`, `factory`, `contributions`, `rootComponents`, and `invocation`. Closing the graph closes constructed `AutoCloseable` instances in reverse creation order.

`InvocationDependencies` inherits plug-in bindings, adds invocation values such as `CommandInvocation`, `CommandSender`, and `CommandExecutor`, owns invocation-scoped instances, and is closed after the invocation.

Plug-in and component subclasses can use the delegated `inject(Type::class)` helper. Prefer constructor injection for application types because it makes dependencies visible and testable.
