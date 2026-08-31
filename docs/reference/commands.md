# Commands reference

Enable commands in the managed Gradle extension:

```kotlin
frameworkPlugin {
    commands(strictDocumentation = true)
}
```

This adds the command and DI runtimes plus their focused KSP processors. KSP emits immutable plans, direct constructor and handler calls, typed routes, and contribution indexes. Brigadier construction, parsing, coroutines, policies, help, and response delivery remain handwritten runtime code.

## Declaration annotations

| Annotation | Target | Effect |
| --- | --- | --- |
| `@Commands` | Class | Owns one root, aliases, permission, schema version, and help literal |
| `@Command` | Function or nested class | Overrides an inferred literal, adds aliases, or adds a permission; repeatable |
| `@Group` | Inner class | Adds a literal, inherited permission, and optional shared constructor arguments |
| `@Scope` | Inner class | Adds invocation dependencies and permission without adding a literal |
| `@Root` | Function | Handles the current path instead of adding another literal |
| `@CommandRenamed` | Function or class | Retains an old literal until a command schema version |
| `@CommandFragment` | Class | Merges independently compiled routes into one owner |
| `@ConfigureCommandGraph` | Function | Declares startup-only graph edges |
| `@ExcludeCommandContributions` | Plug-in class | Removes selected discovered command sets or fragments |

## Structure

Annotate one class with `@Commands`. Its public functions are handlers. Names become kebab-case unless an annotation overrides them. Non-public functions are ordinary implementation details. A repeated `@Command` creates additional spellings for one handler without duplicating its body.

Use `@Group` on an inner class to add a literal. Use `@Scope` to add invocation dependencies without adding a path segment. `@Root` makes a function handle the current path. Root, group, scope, and handler permissions are inherited and checked against the sender.

An inner group or scope may receive command arguments and invocation-scoped dependencies in its constructor. The runtime resolves them once for the selected route before invoking the leaf. Use a scope for a value such as the executing account when it should not appear in command syntax.

Every accepted handler runs as a lifecycle-owned asynchronous task. Both ordinary and `suspend` functions use the same path, and Brigadier immediately receives `SINGLE_SUCCESS`.

## Invocation identity

`CommandInvocation` contains the `CommandSender`, `CommandExecutor`, and canonical route identifier. The sender provides the audience, locale, delivery state, and permissions. The executor identifies the entity on whose behalf vanilla command execution occurs and may differ after redirection or forking.

Use `requirePermission` for a dynamic check inside an invocation. Prefer declared permissions when access can be expressed statically because help can then hide inaccessible routes.

## Arguments and options

Built-in primitive codecs retain native Brigadier syntax. Custom `CommandArgumentCodec` implementations provide raw syntax, suspending resolution, suggestions, and route encoding. Mark a codec class with `@Contributes` and `@Inject` for discovery. A custom annotation marked `@CommandArgumentQualifier` selects between codecs sharing a Kotlin type.

`GreedyText` consumes the decoded positional remainder. `@Repeated` on a terminal vararg or list consumes repeated values. Kotlin defaults make terminal positionals optional; nullability alone does not.

Use `@Option` for direct named options and `@Options` for reusable data classes. Supported spellings include `--name value`, `--name=value`, `-n value`, boolean negation, and a standalone `--`. Options can be interleaved with positionals. Options data classes retain ordinary Kotlin defaults without generated presence branches.

See [Command arguments and options](command-arguments.md) for the complete contract.

## Native Minecraft values

The semantic catalogue under `commands.minecraft` maps Paper native argument types to Kotlin values. Server-owned targets become stable player, entity, block, world, or selection references before asynchronous execution. Positions, rotations, ranges, profiles, criteria, registry keys, item stacks, and predicates are copied or wrapped behind framework-owned types. Paper resolver and provider objects never enter handler signatures.

See [Native Minecraft arguments](native-arguments.md) for the full type table and ownership behavior.

## Responses and failures

Handlers may return `Unit`, `String`, Adventure `Component`, `CommandResult`, or a type with a contributed `CommandResponseCodec`. Use `reject` and `orReject` for expected stackless failures. Contributed typed exception handlers are selected by the most-specific exception type. Cancellation does not produce an error response.

Framework messages come from `CommandMessages`; help comes from `CommandHelpRenderer`. Contributed replacements override the English defaults. See [Command results, failures, and observability](command-results.md).

## Policies

`@Cooldown`, `@RateLimit`, `@SingleFlight`, and `@Confirm` use injected time and atomic policy state. A deployment can replace the in-memory state through DI. `@CancelOnExecutorRetire` ties unfinished work to an entity executor.

For custom behavior, annotate an annotation class with `@CommandPolicy`. Its injected `CommandPolicyInterceptor` runs in one fixed phase and may continue, replace, reject, or wrap the remaining invocation. KSP records the typed annotation but generates no policy wrapper. See [Command policies](command-policies.md).

## Help and KDoc

KSP records each handler's first KDoc sentence, `@param` descriptions, and repeated `@example` entries. Strict documentation makes a missing route summary a compilation error.

Executing a root with no matching `@Root` handler renders permission-filtered help. The explicit help literal defaults to `help` and accepts a page number. Set `helpName = ""` to disable that literal. The default renderer shows eight visible routes per page. A contributed renderer may change presentation without changing the generated model.

## Aliases and migrations

Aliases declared by `@Commands`, `@Command`, or `@Group` are required; conflicts fail startup. Root `optionalAliases` are best-effort and are omitted when occupied.

`@CommandRenamed(from, untilVersion)` retains a temporary old spelling while the owning `schemaVersion` is below `untilVersion`. Raise the schema version to expire it.

## Routes, fragments, and graphs

Each command set receives an injectable generated routes class. Its methods create canonical `CommandRoute` values and Adventure run/suggest links. Sensitive route parameters require `sensitive(value)` and use hashed semantic identities.

`@CommandFragment` merges an independently generated plan into an existing root. Exactly one non-fragment owner is required, and ambiguous merged syntax fails before registration.

Functions marked `@ConfigureCommandGraph` receive the injected `CommandGraph` during startup. Redirects, supervised forks, and required or optional external edges are declared with typed routes. The graph freezes before Paper registration. `CommandDispatcher` submits typed routes through the registered runtime.

See [Typed routes, fragments, and command graphs](command-routes.md).

## Testing boundary

`CommandTestHarness` executes generated parsing, option scanning, codecs, direct binding invocation, and basic result conversion without a server. It does not run the production policy, observer, help, delivery, or executor-retirement pipeline. Paper-owned values are intentionally excluded. The integration fixture boots pinned Paper and Folia versions for full runtime behavior.

See [Testing APIs](testing.md).
