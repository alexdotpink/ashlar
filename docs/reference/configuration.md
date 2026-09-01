# Configuration reference

The configuration module loads static, human-edited plug-in settings into immutable Kotlin values. Public declarations are under `pink.alex.ashlar.config`. Generated linkage uses `pink.alex.ashlar.config.codegen`.

## Artifacts and build

| Artifact | Contents |
| --- | --- |
| `ashlar-config` | Public API, runtime, built-in formats, file handling, serializers, and inspection |
| `ashlar-config-ksp` | Declaration metadata, KDoc extraction, serializers, validators, migrations, and pre-lifecycle linkage |
| `ashlar-config-test` | Server-free production runtime harness and filesystem editing helpers |

Enable the runtime and both required KSP processors with `ashlar { config() }`. The managed Gradle plug-in also applies Kotlin Serialization.

Add the test artifact only to test source sets that use the server-free harness:

```kotlin
dependencies {
    testImplementation("pink.alex.ashlar:ashlar-config-test")
}
```

## Declarations

### `@Config`

`@Config` is a repeatable class annotation. Its target must be a final `@Serializable` data class. Every primary constructor parameter must have a default.

| Property | Type | Default | Contract |
| --- | --- | --- | --- |
| `path` | `String` | required | UTF-8 file beneath the plug-in data directory |
| `schemaVersion` | `Int` | `1` | Current schema, at least 1 |
| `unversionedSchema` | `Int` | `0` | Historical schema assigned to a source without `_ashlar-schema`; `0` means unspecified |
| `reload` | `ConfigReloadMode` | `EXPLICIT` | Explicit reload only or explicit plus file watch |
| `backups` | `Int` | `5` | Maximum valid predecessors retained after writes; from 0 through 100 |
| `maximumBytes` | `Long` | `1_048_576` | Maximum UTF-8 document size before parsing; must be positive |
| `qualifier` | `KClass<out Annotation>` | `Annotation::class` | Optional annotation marked with `@DependencyQualifier` |

The default qualifier means no qualifier. A root may repeat `@Config` for several documents only when every declaration has a distinct qualifier. KSP rejects duplicate `(root type, qualifier)` pairs.

Property names use kebab case in the external document. `maximumWaypoints` becomes `maximum-waypoints`. `@SerialName` replaces the external name exactly. `_ashlar-schema` is reserved at the root.

### `ConfigReloadMode`

`EXPLICIT` reloads through `ConfigHandle.reload()` or `Configurations.reloadAll()`. `WATCH` also watches the source directory and coalesces matching file changes.

### `@ConfigValidation`

Marks a public or internal top-level function with this form:

```kotlin
fun ConfigValidationScope<Root>.name(): Unit
```

The function cannot suspend, declare value parameters or type parameters, or target a type without `@Config`.

`ConfigValidationScope<T>` has `current`, `requireValue(condition, property, message)`, and `warnIf(condition, property, message)`. `requireValue` adds an error when false. `warnIf` adds a warning when true.

### `@ConfigMigration`

`@ConfigMigration(root, from)` marks one public or internal top-level extension that converts schema `from` to `from + 1`:

```kotlin
fun SchemaOne.toSchemaTwo(): SchemaTwo
```

The source and target types need `@Serializable`. The function cannot suspend or declare value parameters or type parameters. KSP requires exactly one connected step for every schema below the current one and requires the last target to be the current root.

## `ConfigHandle<T>`

A generated pre-lifecycle initializer binds the exact closed generic handle. Constructor injection distinguishes `ConfigHandle<One>` from `ConfigHandle<Two>`. A DI qualifier distinguishes multiple handles of the same root type.

| Member | Meaning |
| --- | --- |
| `current: T` | Latest accepted immutable value |
| `values: StateFlow<T>` | Accepted distinct typed values, beginning with `current` |
| `events: Flow<ConfigEvent<T>>` | Accepted, rejected, and unavailable attempts |
| `reload(): ConfigReload<T>` | Read, migrate, decode, and validate the active source |
| `update((T) -> T): ConfigWrite<T>` | Stale-safe validated atomic update |
| `backups(): List<ConfigBackup>` | Value-free metadata for retained predecessors |
| `restore(ConfigBackupId): ConfigRestore<T>` | Validated explicit predecessor restore |

The update transform is non-suspending and runs once. Equal values produce `ConfigWrite.Unchanged` and no filesystem write.

## Outcomes

### `ConfigReload<T>`

| Variant | Fields | Effect |
| --- | --- | --- |
| `Accepted` | `value`, `changed`, `warnings` | Publishes an accepted source; `values` emits only if `changed` |
| `Rejected` | `current`, `problems` | Retains the accepted value because the source is invalid |
| `Unavailable` | `current`, `problem` | Retains the accepted value because a recoverable operation failed |

### `ConfigWrite<T>`

| Variant | Fields | Effect |
| --- | --- | --- |
| `Accepted` | `value`, `warnings` | Backup, atomic persistence, then publication succeeded |
| `Unchanged` | `value` | No write or backup |
| `Rejected` | `current`, `problems` | Validation rejected the transformed value |
| `SourceChanged` | `current`, `acceptedRevision` | Disk no longer matches the accepted revision; no merge or overwrite occurs |
| `Unavailable` | `current`, `problem` | A recoverable file operation failed |

### `ConfigRestore<T>`

| Variant | Fields | Effect |
| --- | --- | --- |
| `Accepted` | `value`, `warnings` | Backup validated and replaced the active source |
| `Rejected` | `current`, `problems` | Backup could not produce a valid current value |
| `NotFound` | `current`, `id` | Backup identifier no longer exists |
| `Unavailable` | `current`, `problem` | A recoverable file operation failed |

Expected document and I/O problems use these outcomes. Unexpected framework faults and exceptions thrown by an update transform propagate normally.

## Events

`ConfigEventOrigin` values are `INITIAL_LOAD`, `MANUAL_RELOAD`, `WATCHED_RELOAD`, `UPDATE`, `MIGRATION`, and `RESTORE`.

`ConfigEvent.Accepted<T>` contains `origin`, `value`, `revision`, `changed`, `changedPaths`, and `warnings`. `changedPaths` contains format-neutral key paths whose typed values changed.

`ConfigEvent.Rejected<T>` contains `origin`, `current`, optional attempted `revision`, and `problems`.

`ConfigEvent.Unavailable<T>` contains `origin`, `current`, and `problem`.

`events` replays the most recent attempt to a new collector. The initial accepted load is the first event. A comment-only reload emits an accepted event with `changed = false`, advances the source revision, and does not emit through `values`.

`ConfigSourceRevision` is an opaque identity of exact source content. Code may compare revisions but should not parse `value`.

## Aggregate operations and inspection

`Configurations.reloadAll()` attempts every handle independently and returns `ConfigReloadReport`. `documents` contains one `ConfigDocumentReload` for each path. `accepted` counts `ACCEPTED` statuses. `retained` counts every other status. One rejection does not roll back another accepted document.

`Configurations.inspect()` returns `ConfigInspection` records with:

- relative `path` and format ID;
- current schema and optional source revision;
- reload mode and watcher status;
- last operation status;
- warning count and value-free problems;
- backup metadata.

`ConfigWatcherStatus` values are `DISABLED`, `STARTING`, `WATCHING`, `RECOVERING`, and `STOPPED`. `ConfigOperationStatus` values are `ACCEPTED`, `REJECTED`, and `UNAVAILABLE`.

Inspection exposes no configuration values or mutation methods.

## Diagnostics

`ConfigProblem` describes source content that was available but unacceptable.

| Field | Meaning |
| --- | --- |
| `path` | Relative document path |
| `key` | Stable `ConfigKeyPath`; empty for a document-wide problem |
| `category` | Tool-stable problem category |
| `severity` | `ERROR` or `WARNING` |
| `message` | Value-free description |
| `location` | Optional one-based line and column |
| `expected` | Optional expected rule or type |
| `nearestKnownKey` | Optional suggestion for an unknown key |

`ConfigProblemCategory` values are `SYNTAX`, `DUPLICATE_KEY`, `UNKNOWN_KEY`, `UNSUPPORTED_SCHEMA`, `DECODING`, `MIGRATION`, `VALIDATION`, `RESOURCE_LIMIT`, and `UNSUPPORTED_FEATURE`.

`ConfigOperationProblem` describes a recoverable file or watcher problem. Its categories are `NOT_FOUND`, `PERMISSION_DENIED`, `READ_FAILED`, `WRITE_FAILED`, `BACKUP_FAILED`, `ATOMIC_REPLACE_FAILED`, and `WATCH_FAILED`.

Diagnostics, logs, events, backup metadata, and inspection do not copy raw source or typed values into messages. `ConfigStartupException` carries a document path and either content problems or one operation problem when initial required configuration cannot load.

## Formats

Built-in selection uses the final extension without case sensitivity.

| Extensions | ID | Dialect | Comments |
| --- | --- | --- | --- |
| `.yml`, `.yaml` | `yaml` | YAML 1.2 core schema | Preserved |
| `.toml` | `toml` | TOML 1.0 | Preserved |
| `.json` | `json` | Strict RFC 8259 JSON | Not allowed |
| `.jsonc` | `jsonc` | JSON with line and block comments | Preserved |

All roots must be mappings or objects. Duplicate keys reject. YAML rejects custom tags, recursive or non-scalar keys, unsupported values, excessive aliases, and non-finite numbers. JSON rejects comments and trailing commas. JSONC accepts comments but otherwise uses the JSON value model.

TOML 1.0 has no null value. A TOML declaration cannot create or write a nullable property whose value is null. Use YAML, JSON, or JSONC when explicit nulls belong in the schema.

New documents and explicit writes include the complete serialized value, including defaults and explicit nulls where the format supports null. A valid existing document may omit a defaulted property. Kotlin supplies that default in memory, and Ashlar does not rewrite the source until an explicit update or migration.

### `ConfigFormat`

`ConfigFormat` is a DI contribution. It declares `id`, `extensions`, and `preservesComments`, then implements:

- `parse(ConfigSource, ConfigLimits): ConfigParse`;
- `create(ConfigValue.ObjectValue, comments): ConfigDocument`;
- `write(ConfigDocument): String`.

`ConfigSource` contains the relative path and UTF-8 text. `ConfigParse` is either `Accepted(document, warnings)` or `Rejected(problems)`.

`ConfigDocument` exposes `formatId`, the format-neutral root `value`, optional `location(key)`, and `patch(value, newComments)`. A format owns its document implementation. `write` accepts only that implementation.

`ConfigValue` variants are `NullValue`, `BooleanValue`, `StringValue`, `IntegerValue`, `DecimalValue`, `ArrayValue`, and `ObjectValue`. `DecimalValue` stores its text representation so a format does not lose decimal precision while projecting source.

A custom format must reject duplicate keys, enforce applicable limits, retain required source trivia, and return source-located content problems. A format with `preservesComments = true` must retain all comment tokens through patches and writes.

## Comments and key order

KSP copies root and property KDoc into comment metadata. New keys receive those comments. Once a source exists, its comments belong to the operator. Later KDoc changes do not rewrite existing comments.

YAML, TOML, and JSONC patches retain comments on values, containers, and standalone positions. When a key disappears, its comments remain under the same parent unless a migration or format patch associates them with a replacement. Strict JSON has no comments.

Serializer declaration order controls newly created documents. Existing source order and formatting remain unless a semantic patch must add or change content.

## Schema marker and adoption

`_ashlar-schema` is reserved top-level metadata. Ashlar removes it before typed decoding and writes the current `schemaVersion` on new and migrated sources.

- A schema 1 declaration accepts an absent marker as schema 1.
- A declaration above schema 1 rejects an absent marker when `unversionedSchema` is `0`.
- A positive `unversionedSchema` adopts an absent marker as that historical schema.
- The adopted schema cannot exceed the current declaration schema.
- A source schema above the declaration schema rejects. Ashlar never downgrades it.
- Every migration is sequential. A migration from 1 produces schema 2, then a migration from 2 produces schema 3.

The runtime validates the final current value, creates a backup, persists the migrated document, and then publishes it.

## Paths and file operations

Paths are relative to the plug-in data directory. Empty paths, absolute paths, `.` or `..` segments, empty segments, Windows drive paths, NUL characters, traversal, and symbolic-link escapes reject. Parent directories may be created beneath the confined root.

Ashlar reads and writes UTF-8. Writes use a temporary sibling and atomic replacement where the filesystem supports it. The runtime serializes reloads, updates, restores, and migrations for each handle. Different documents do not share one transaction.

Before an update, Ashlar compares disk content with the last accepted source revision. A mismatch returns `ConfigWrite.SourceChanged`. It does not auto-merge.

## Limits

`ConfigLimits` defaults are:

| Limit | Default |
| --- | ---: |
| `maximumBytes` | 1,048,576 UTF-8 bytes |
| `maximumDepth` | 64 |
| `maximumScalarCharacters` | 262,144 Unicode code points |
| `maximumAliases` | 50 |

Each value is validated at construction. Bytes, depth, and scalar limits must be positive. Alias count may be zero. `@Config.maximumBytes` sets the per-document byte limit in generated definitions. The other limits use runtime defaults in generated declarations.

Limits cannot disable path confinement, duplicate rejection, stale-source checks, comment guarantees, or atomic replacement behavior.

## Backups

`ConfigBackup` contains an opaque `ConfigBackupId`, creation `Instant`, schema version, and source revision. It never contains source text or a typed value.

Accepted updates, persisted migrations, and restores back up the previous valid active document. Rotation retains at most `@Config.backups` predecessors. A restore first validates its selected predecessor. Ashlar never restores automatically.

## Stable serializers

Ashlar supplies string serializers for values that have stable text identities:

| Serializer | Kotlin type | Text form |
| --- | --- | --- |
| `ConfigDurationSerializer` | `kotlin.time.Duration` | Kotlin duration such as `3s` or `250ms` |
| `ConfigUuidSerializer` | `UUID` | Canonical lowercase UUID |
| `ConfigKeySerializer` | Adventure `Key` | `namespace:value` |
| `ConfigNamespacedKeySerializer` | Bukkit `NamespacedKey` | `namespace:value` |
| `ConfigTypedKeySerializer<T>` | Paper `TypedKey<T>` | `namespace:value` within one constructor-supplied `RegistryKey<T>` |

Use them with `@Serializable(with = ...)` or in a property serializer. `StringConfigSerializer<T>` is the public base for a stable value represented by one string scalar.

Do not serialize live `Player`, `World`, `Entity`, or `Location` objects. Store a UUID, name, domain value, or stable key, then resolve live state under the correct Paper ownership context.

## Test harness

`configTest(vararg definitions, formats, block)` runs the production runtime in a temporary real filesystem and returns `TestResult`. Generated definitions are runtime linkage, so most plug-in tests should place their definitions in a fixture helper rather than construct them in production code.

`ConfigTestScope` exposes `dataDirectory`, `configurations`, typed `handle`, `readSource`, `writeSource`, `editSource`, `replaceSource`, `editorBurst`, `awaitEvent`, `awaitCurrent`, and `awaitInspection`.

`ConfigTestScope.start` owns and removes a temporary directory. `startAt` uses a caller-owned directory and leaves its files in place. `configHandleKey<T>` creates the exact structural key used by generated configuration linkage.

## Generated linkage

`ConfigDefinition`, `ConfigValidator`, `ConfigMigrationStep`, `configValidator`, `configMigration`, and `ConfigurationBootstrap` are public so generated sources and the test artifact can link to the runtime. Plug-in production code should use annotations and inject `ConfigHandle<T>` or `Configurations`.

The processor generates one initializer containing static paths, policy values, exact generic dependency keys, serializer calls, KDoc metadata, and direct calls to validation and migration functions. It does not generate loaders, watchers, format implementations, validators, migrations, handles, or file logic.
