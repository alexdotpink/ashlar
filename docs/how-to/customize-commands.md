# Add custom command behavior

## Resolve a domain argument

Implement and contribute `CommandArgumentCodec<T>`. Pick a `CommandSyntax`, resolve the raw value, provide suggestions when useful, and encode the value for generated routes.

```kotlin
@Contributes
@Inject
class WaypointCodec(
    private val store: WaypointStore,
) : CommandArgumentCodec<Waypoint> {
    override val type = Waypoint::class

    override suspend fun resolve(
        raw: String,
        invocation: CommandInvocation,
        dependencies: DependencyResolver,
    ): Waypoint = store.find(raw) ?: invalidArgument("unknown waypoint")

    override suspend fun suggest(
        input: String,
        invocation: CommandInvocation,
        dependencies: DependencyResolver,
    ) = store.names().filter { it.startsWith(input, ignoreCase = true) }

    override fun encode(value: Waypoint) = value.name
}
```

Contribute `CommandSuggestionProvider<T>` when suggestions should change without replacing the codec.

## Encode a domain response

Contribute `CommandResponseCodec<T>` and return `T` directly from handlers. Use `CommandExceptionHandler<E>` for domain exceptions that need a consistent response.

## Add a policy

Create an annotation meta-annotated with `@CommandPolicy`, then inject its `CommandPolicyInterceptor`. Choose the earliest phase that has the required data. Call `next()` exactly once unless the policy deliberately rejects or replaces the invocation.

## Replace help or framework messages

Contribute one `CommandHelpRenderer` or `CommandMessages` implementation. The runtime requires at most one contributed replacement for each contract.

## Observe command metadata

Contribute `CommandObserver`. Observer failures are logged and never alter command results. Only parameters marked `@Observed` enter `observedArguments`; any parameter marked `@Sensitive` stays out.
