# Command policies reference

Policies apply reusable behavior around a command invocation. Permissions are static access checks; policies are invocation-time rules.

## Built-in policies

| Annotation | Scope | Behavior |
| --- | --- | --- |
| `@Cooldown(value, mode)` | Class or function | One success, attempt, or accepted invocation per sender and route during `value` seconds |
| `@RateLimit(permits, per, mode)` | Class or function | Token-bucket or sliding-window limit per sender and route |
| `@SingleFlight` | Class or function | Rejects overlapping handler work for the same sender and route |
| `@Confirm(expiresAfterSeconds)` | Function | Requires the same semantic route and canonical arguments twice within the window |
| `@CancelOnExecutorRetire` | Function | Cancels unfinished work when its entity executor retires |

`CooldownMode` values charge at different points:

- `ATTEMPT`: before argument resolution, including invalid input
- `ACCEPTED`: after successful resolution and before the handler
- `SUCCESS`: after the handler completes successfully

`RateLimitMode` values are `TOKEN_BUCKET` and `SLIDING_WINDOW`. Policy identity uses the sender UUID when available, otherwise the sender name, plus the canonical route. Confirmation includes canonically encoded arguments and clears after the confirmed invocation.

## Policy state and time

Built-ins use `CommandPolicyState.update` as an atomic read-modify-write operation. The default `InMemoryCommandPolicyState` is thread-safe, ephemeral, and local to one plug-in process. Contribute or bind a replacement for distributed or persistent semantics.

`CommandPolicyRecord` stores an opaque value and optional expiry. Time comes from an injectable `java.time.Clock`, which makes policy behavior deterministic in focused tests.

## Custom policies

Define an annotation linked to an interceptor:

```kotlin
@CommandPolicy(
    interceptor = AuditGateInterceptor::class,
    phase = CommandPolicyPhase.AFTER_RESOLUTION,
    order = 20,
)
annotation class AuditGate(val category: String)

@Inject
class AuditGateInterceptor : CommandPolicyInterceptor<AuditGate> {
    override suspend fun intercept(
        annotation: AuditGate,
        context: CommandPolicyContext,
        next: suspend () -> Any?,
    ): Any? {
        checkAccess(annotation.category, context.invocation)
        return next()
    }
}
```

Phases run in this order: `BEFORE_RESOLUTION`, `AFTER_RESOLUTION`, `BEFORE_HANDLER`, `AFTER_HANDLER`. Interceptors in one phase run by ascending `order`. Call `next()` once to continue. An interceptor may reject, replace the result, or wrap downstream execution instead.

`CommandPolicyContext` contains the invocation and the canonical argument list available at that phase. KSP records the typed annotation instance and interceptor class; policy execution remains handwritten runtime code.

`CommandPolicyDefinition` and its `Cooldown`, `RateLimit`, `SingleFlight`, `Confirm`, and `Custom` variants are immutable generated-plan metadata. Plug-in code normally uses annotations rather than constructing definitions.
