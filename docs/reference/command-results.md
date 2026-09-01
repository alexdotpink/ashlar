# Command results, failures, and observability

## Handler return values

The command runtime supports:

| Return type | Effect |
| --- | --- |
| `Unit` | No response |
| `String` | One text response |
| Adventure `Component` | One rich response |
| `CommandResult` | Zero or more ordered responses |
| Contributed domain type | Encoded by its `CommandResponseCodec<T>` |

Build multiple responses with `responses`:

```kotlin
fun purge(): CommandResult = responses {
    reply("Removed all entries.")
    reply(Component.text("This cannot be undone."))
}
```

`CommandResult.Empty` and `CommandResult.of(component)` are available for explicit construction.

## Expected rejection

Use `reject` for an expected refusal:

```kotlin
val home = repository.find(name).orReject { "Unknown home '$name'." }
```

String and Adventure component variants are available. Rejection is stackless, produces the supplied response, and emits a rejected observation rather than an unexpected-failure report.

Argument codecs use `invalidArgument(reason)`. The runtime formats the failure through `CommandMessages.invalidArgument`. Missing arguments and permission failures use the same message contract.

## Unexpected exceptions

Contribute `CommandExceptionHandler<E>` for a domain exception that should become a consistent command result. The runtime chooses the handler with the most-specific declared exception type. Without a matching handler, it logs the failure and sends `CommandMessages.unexpectedFailure` when delivery is still possible.

Coroutine cancellation is not an error response. If a player can no longer receive a response, delivery is dropped.

## Custom responses and messages

`CommandResponseCodec<T>` converts a handler's domain result to `CommandResult`. Its `encode` function may suspend. At most one matching codec may exist for a return type.

Contribute one `CommandMessages` to replace the default English ashlar-originated messages. Contribute one `CommandHelpRenderer` to replace help rendering. Multiple replacements fail startup rather than depending on discovery order.

## Observers

`CommandObserver` receives `CommandEvent.Accepted`, `Completed`, `Rejected`, and `Failed`. Observers cannot alter the outcome. An observer failure is reported and does not fail the command.

Only parameters marked `@Observed` enter observed argument metadata. `@Sensitive` always wins and excludes the value. Use observers for telemetry and audit metadata, not authorization or business behavior.
