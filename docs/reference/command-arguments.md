# Command arguments and options reference

Handler parameter types define the command grammar. KSP validates the static shape; the runtime parses tokens and resolves domain values.

## Built-in scalar types

`String`, `Int`, `Long`, `Float`, `Double`, and `Boolean` have built-in codecs. Their route values are encoded canonically. `CommandSyntax` additionally exposes `WORD`, `STRING`, `GREEDY_STRING`, `INTEGER`, `LONG`, `FLOAT`, `DOUBLE`, and `BOOLEAN` for custom codecs.

`GreedyText` is the framework's greedy string type. It consumes the decoded terminal remainder. Quoted and unquoted forms both work:

```text
/waypoint search market square
/waypoint search "market square"
```

Greedy and repeated positional parameters must be terminal. Mark a terminal `List<T>` or vararg with `@Repeated` to consume zero or more ordered values.

## Optional positionals

A Kotlin default makes a terminal positional optional:

```kotlin
fun list(order: WaypointOrder = WaypointOrder.NAME, page: Int = 1)
```

Defaults can only be omitted from the end of a route. Nullability by itself does not make a positional optional.

## Direct options

```kotlin
fun search(
    query: GreedyText,
    @Option(short = 'l') limit: Int?,
    @Option verbose: Boolean,
)
```

Direct options cannot use Kotlin default parameters. Use nullable input when absence and `null` are equivalent, or `OptionValue<T>` when the handler must distinguish absence from a present value. Boolean direct options are required unless represented with nullable or `OptionValue`.

## Options classes

```kotlin
@Options
data class SearchOptions(
    @Option(short = 'l') val limit: Int = 20,
    @Option val verbose: Boolean = false,
    @Option(name = "tag") @Repeated val tags: List<String> = emptyList(),
)
```

Options classes retain their Kotlin constructor defaults. Supported input forms are:

- `--limit 5`
- `--limit=5`
- `-l 5`
- `--verbose` and `--no-verbose`
- repeated `--tag one --tag two`
- `--` to stop option scanning

Named options may be interleaved with positionals. Long names default to the parameter or property name converted to kebab-case. Short names must be unique in a route.

## Custom codecs

Implement `CommandArgumentCodec<T>` and contribute it through DI:

```kotlin
@Contributes
@Inject
class WaypointCodec(
    private val store: WaypointStore,
) : CommandArgumentCodec<Waypoint> {
    override val type = Waypoint::class
    override val syntax = CommandSyntax.WORD

    override suspend fun resolve(
        raw: String,
        invocation: CommandInvocation,
        dependencies: DependencyResolver,
    ): Waypoint = store.find(raw) ?: invalidArgument("unknown waypoint")

    override suspend fun suggest(
        input: String,
        invocation: CommandInvocation,
        dependencies: DependencyResolver,
    ): List<String> = store.names().filter { it.startsWith(input, ignoreCase = true) }

    override fun encode(value: Waypoint): String = value.name
}
```

`resolve` and `suggest` may suspend. `encode` must produce the semantic command value used by generated typed routes. Throw `invalidArgument(reason)` for expected decoding failure.

Contribute `CommandSuggestionProvider<T>` to replace suggestions while retaining the existing codec.

## Argument qualifiers

When one Kotlin type has multiple command meanings, create a meta-annotation:

```kotlin
@CommandArgumentQualifier
annotation class OwnedWaypoint
```

Set the same qualifier on the codec and handler parameter. The runtime selects by both type and qualifier; ambiguous or missing codecs fail rather than guessing.

## Metadata controls

`@Sensitive` omits the value from observations and requires `sensitive(value)` when generating routes. `@Observed` opts a non-sensitive value into observer metadata. `@Restricted` is reserved for restricted route visibility and is not consumed by the current command compiler.

## Low-level token utilities

Most plug-ins should let generated commands use these utilities internally. They are public for focused parsing tests and modules that need identical command-line rules.

- `CommandLineTokenizer.tokenize` returns `CommandToken` values with decoded text, source indexes, and a quoted-or-escaped flag. It supports single quotes, double quotes, and backslash escapes.
- `CommandLineSyntaxException` is stackless and carries a reason plus optional source position.
- `NamedOptionDefinition` describes a `VALUE` or `BOOLEAN` option, optional short name, and repetition rule.
- `NamedOptionScanner.scan` returns `ParsedCommandLine`, which exposes positionals, option values, boolean lookup, and a decoded positional remainder.
- `NamedOptionKind`, `CommandToken`, `ParsedCommandLine`, `NamedOptionDefinition`, and `NamedOptionScanner` are runtime value types; they do not register Brigadier nodes.
