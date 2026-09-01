# Configuration module design

Status: implemented

Implemented by the structural DI, configuration runtime, KSP linkage, test harness, sample, and pinned Paper and Folia fixture commits completed in September 2026. Performance benchmarking remains deferred by project direction.

The configuration module turns human-edited settings documents into immutable, validated Kotlin values. It loads required values before application construction, preserves operator comments, publishes accepted reloads atomically, and gives every expected source problem a typed outcome.

## Goals

- Make an immutable serializable data class the normal settings declaration.
- Inject `ConfigHandle<T>` directly without wrapper classes or registry lookups.
- Fail plug-in enable before application construction when required settings are invalid.
- Create absent documents from complete Kotlin defaults.
- Support explicit and opt-in watched reloads without publishing partial or invalid values.
- Preserve every human-authored YAML, TOML, and JSONC comment through framework writes.
- Catch unknown keys and validation problems with path and source-location diagnostics.
- Give breaking schemas compile-checked sequential migrations.
- Detect unseen external edits before a plug-in write can overwrite them.
- Keep writes atomic and retain a bounded, validated recovery history.
- Test declarations, edits, watches, migrations, conflicts, and recovery without starting Paper.

## Non-goals

- Configuration does not store player preferences, caches, domain records, or arbitrary documents. Those belong to persistence modules.
- Configuration does not resolve raw passwords, tokens, or signing keys. Settings may contain stable references consumed by a separate secrets capability.
- A configuration value does not contain live `Player`, `World`, `Entity`, or `Location` objects.
- Separate configuration documents do not participate in one atomic reload or cross-file validation transaction.
- The module does not register a Minecraft reload command. Plug-ins own command names and permissions.
- The module does not combine file values with environment variables, system properties, or remote setting layers.
- KSP does not generate loaders, validators, migrations, watchers, handles, or format code.

## Module shape

The feature ships as three artifacts:

- `ashlar-config`: handwritten runtime, public API, built-in formats, file durability, and inspection;
- `ashlar-config-ksp`: declaration metadata, KDoc extraction, direct serializer and function references, and pre-lifecycle binding contributions;
- `ashlar-config-test`: server-free production handles, real temporary documents, editor-style writes, watches, backups, and restart behavior.

The managed build enables them with:

```kotlin
ashlar {
    config()
}
```

Public code lives under `pink.alex.ashlar.config`.

## Primary declaration and injection

One final serializable data class normally owns one unqualified document:

```kotlin
@Config(
    path = "waypoints.yml",
    schemaVersion = 3,
    unversionedSchema = 1,
    reload = ConfigReloadMode.WATCH,
)
@Serializable
data class WaypointSettings(
    /** Maximum public waypoints one account may own. */
    val maximumWaypoints: Int = 10,

    /** Delay before a requested teleport begins. */
    val teleportDelay: Duration = 3.seconds,
)
```

Every required constructor property has a Kotlin default. KSP rejects a configuration root that cannot decode an empty document, because Ashlar could not create a missing source deterministically. Nested serializable types may have their own defaults and KDoc.

Application code injects the exact closed generic handle:

```kotlin
@Inject
class Waypoints(
    private val settings: ConfigHandle<WaypointSettings>,
) {
    fun maximum(): Int = settings.current.maximumWaypoints
}
```

The same type may back more than one static document through existing DI qualifiers:

```kotlin
@DependencyQualifier
annotation class Nether

@DependencyQualifier
annotation class End

@Config(path = "worlds/nether.yml", qualifier = Nether::class)
@Config(path = "worlds/end.yml", qualifier = End::class)
@Serializable
data class WorldRules(
    val explosions: Boolean = true,
)

@Inject
class Worlds(
    @Nether private val nether: ConfigHandle<WorldRules>,
    @End private val end: ConfigHandle<WorldRules>,
)
```

Dynamic families of per-player, per-world, or caller-named documents are deliberately not configuration handles. They belong to persistence.

## Structural generic dependency identity

Direct handle injection requires DI to distinguish complete generic types. Ashlar will replace raw-class-only identity with an immutable recursive type shape:

```kotlin
DependencyType(
    rawType = ConfigHandle::class,
    arguments = listOf(DependencyType(WaypointSettings::class)),
)
```

Generated constructor factories retain concrete nested generic arguments and reuse one generated key for dependency ordering and lookup. The initial implementation supports closed invariant types and rejects star projections, unresolved type parameters, use-site variance, and nested nullable arguments with compile errors. It adds no runtime classpath scanning or generic reflection.

The change is system-wide rather than configuration-specific. `Repository<Player>` and `Repository<Waypoint>` also become distinct keys. Existing raw `KClass` binding and lookup helpers remain conveniences for non-parameterized types.

## Startup order

Configuration definitions are pre-lifecycle contributions. Before resolving application components, Ashlar performs these steps for every required definition:

1. Resolve the path and format under the plug-in data folder.
2. Create a missing document from the complete default value.
3. Parse one bounded lossless source document.
4. Resolve its schema version and run required migrations.
5. Decode the current serializable type.
6. Run every validation function and collect all problems.
7. Persist an accepted migration before publication.
8. Bind the exact qualified `ConfigHandle<T>` key.

Any non-accepted result fails plug-in enable before application construction. Existing kernel rollback then owns cleanup. A handle is never injected in a pending or partially loaded state.

Watchers begin only after the initial value is valid and bound. Blocking filesystem work runs outside Paper ownership threads. Configuration callbacks do not grant global, region, or entity access.

## Handle contract

The normal handle has one synchronous value and two flows:

```kotlin
interface ConfigHandle<T : Any> {
    val current: T
    val values: StateFlow<T>
    val events: Flow<ConfigEvent<T>>

    suspend fun reload(): ConfigReload<T>
    suspend fun update(transform: (T) -> T): ConfigWrite<T>
    suspend fun backups(): List<ConfigBackup>
    suspend fun restore(id: ConfigBackupId): ConfigRestore<T>
}
```

`current` and `values` expose accepted immutable values only. A successful operation publishes one complete value after persistence and validation finish. Equal typed values do not emit again through `values`.

`events` includes accepted and rejected attempts. An accepted event identifies its origin, source revision, changed key paths, warnings, and whether the typed value changed. It carries no raw source values. A comment-only edit advances the accepted source revision and emits `Accepted(changed = false)` without re-emitting `T`.

`Configurations.reloadAll()` attempts every handle independently and returns a complete report. It is not atomic and never rolls back an accepted handle because another document rejected.

## Reload and failure outcomes

Manual and watched reload use the same operation:

```kotlin
sealed interface ConfigReload<out T> {
    data class Accepted<T>(
        val value: T,
        val changed: Boolean,
        val warnings: List<ConfigProblem>,
    ) : ConfigReload<T>

    data class Rejected<T>(
        val current: T,
        val problems: List<ConfigProblem>,
    ) : ConfigReload<T>

    data class Unavailable<T>(
        val current: T,
        val problem: ConfigOperationProblem,
    ) : ConfigReload<T>
}
```

`Rejected` covers malformed syntax, duplicate or unknown keys, unsupported schemas, decode errors, migration rejection, and validation errors. `Unavailable` covers recoverable filesystem and permission failures. Both leave `current` and `values` untouched. Unexpected framework bugs and exceptions thrown by plug-in transforms retain ordinary exception behavior.

Diagnostics identify the document, key path, line, column, category, expected rule, and nearest known key where applicable. Framework logs and inspection never echo raw source or typed values.

Watched rejections are deduplicated by source revision. The runtime logs one concise rejection and one recovery when a later source becomes valid. Manual operations return their result without duplicate framework error logs.

## Watching and source revisions

`ConfigReloadMode.EXPLICIT` is the default. `WATCH` is an explicit declaration policy:

```kotlin
@Config(
    path = "waypoints.yml",
    reload = ConfigReloadMode.WATCH,
)
```

Directory events, editor write bursts, and atomic rename saves are coalesced into one stable-source attempt. A rejected edit does not stop the watcher. A later valid edit can recover normally.

Every accepted source has a fingerprint. Framework writes suppress their matching watch event by fingerprint rather than a timing window. Before an explicit update writes, Ashlar verifies that disk still matches the accepted source. An unseen external change returns `ConfigWrite.SourceChanged`; it is never overwritten or merged automatically.

## Explicit updates

Plug-in code writes through one non-suspending transform inside a suspending operation:

```kotlin
val result = settings.update { current ->
    current.copy(maximumWaypoints = 20)
}
```

The handle serializes reloads, updates, migrations, and restores. An update:

1. Reads the current accepted value and source revision.
2. Runs the transform once.
3. Encodes a format-neutral value tree.
4. Applies the value difference to the lossless source document.
5. Runs validation and collects warnings.
6. Verifies the source fingerprint has not changed.
7. Backs up the previous valid document.
8. Writes a temporary sibling and replaces the destination atomically where supported.
9. Publishes the accepted source and value.

An equal transformed value performs no write and creates no backup. There is no separate unrestricted `save(value)` and no mutable editor object.

## Formats and lossless documents

The module ships four formats selected by file extension:

| Extension | Format | Comments |
| --- | --- | --- |
| `.yml`, `.yaml` | Safe YAML 1.2 | Preserved |
| `.toml` | TOML | Preserved |
| `.json` | Strict JSON | Not part of the format |
| `.jsonc` | JSON with comments | Preserved |

Safe YAML supports core scalar values and bounded aliases. Duplicate keys, custom tags, excessive aliases, excessive nesting, oversized scalars, and oversized documents reject before typed decoding. Every built-in and custom format follows common duplicate-key and resource-limit contracts.

`ConfigFormat` is a public contribution seam. A format owns its extensions, bounded lossless parser, value-tree projection, comment-aware patching, and writer. A comment-capable custom format must retain all comment tokens through framework writes. Ad hoc per-declaration codec callbacks are not supported.

Kotlin property names become kebab-case keys by default. `@SerialName` overrides one key. Newly created and explicitly saved documents contain every property, including default values and explicit nulls, in serializer declaration order. TOML rejects a null because TOML 1.0 has no null value.

Valid existing documents are never rewritten merely because a newer defaulted property is absent. Kotlin supplies that default in memory. A later explicit update or migration may insert the property with its initial KDoc comment.

## Comment ownership

KSP copies configuration class and property KDoc into generated comment metadata. A newly created key receives that documentation as a source comment. Once the document exists, every existing comment is operator-owned and later KDoc changes never rewrite it.

Lossless writes preserve comments attached to values, mappings, sequences, tables, and standalone positions. When a migration removes a commented key, its comments remain standalone at the same parent location. Typed migrations do not edit operator comments. No framework operation silently deletes them.

Strict JSON cannot contain comments. JSONC exists when a JSON-shaped document needs the comment guarantee.

## Validation

Cross-field rules are pure annotated extensions:

```kotlin
@ConfigValidation
fun ConfigValidationScope<WaypointSettings>.validate() {
    requireValue(
        current.maximumWaypoints in 1..100,
        WaypointSettings::maximumWaypoints,
    ) {
        "must be between 1 and 100"
    }
    warnIf(
        current.maximumWaypoints > 80,
        WaypointSettings::maximumWaypoints,
    ) {
        "may produce very large listings"
    }
}
```

Property references produce stable typed paths. Reusable single-value invariants belong in serializable value classes. Validation functions cannot suspend, resolve dependencies, perform I/O, mutate Paper, or change the value.

Every error is collected before rejection. Warnings allow acceptance and remain visible in operation results, watched diagnostics, and redacted inspection.

## Schemas and migrations

`_ashlar-schema` is a reserved top-level metadata key removed before typed decoding. Schema 1 may accept an absent marker. Schema 2 and later require `unversionedSchema` to adopt an unversioned historical document; otherwise the source rejects as ambiguous. A document newer than the declaration rejects and is never downgraded.

Breaking changes use pure sequential extension functions:

```kotlin
@Serializable
data class WaypointSettingsV1(
    val teleportDelayTicks: Long,
)

@Serializable
data class WaypointSettingsV2(
    val teleportDelay: Duration,
)

@ConfigMigration(WaypointSettings::class, from = 1)
fun WaypointSettingsV1.toV2(): WaypointSettingsV2 =
    WaypointSettingsV2(teleportDelayTicks.ticks)

@ConfigMigration(WaypointSettings::class, from = 2)
fun WaypointSettingsV2.toV3(): WaypointSettings =
    WaypointSettings(
        teleportDelay = teleportDelay,
        maximumWaypoints = 10,
    )
```

KSP verifies one unbroken chain to the current schema, retains each historical serializer, and emits direct function references. Migration functions cannot suspend, use DI, perform I/O, or inspect Paper. Adding a defaulted property needs no migration.

An accepted migration validates the current value, creates a backup, atomically persists the migrated lossless document, then publishes. Any migration, validation, backup, or write problem prevents publication.

## Backups and restore

Explicit updates and persisted migrations retain five timestamped valid predecessors by default. The declaration may choose another bounded count. Ashlar never silently loads a backup because the active document is malformed.

A validated restore is explicit:

```kotlin
val backup = settings.backups().first()
val result = settings.restore(backup.id)
```

Restore parses the selected source, migrates when supported, validates, backs up the current valid document, atomically installs the restored document, and publishes. Unsupported future schemas, corrupt backups, and failed validation do not modify the active source or value.

## Paths and limits

Declaration paths resolve beneath the plug-in data folder:

```text
waypoints.yml         -> plugins/PluginName/waypoints.yml
worlds/nether.yml     -> plugins/PluginName/worlds/nether.yml
```

Absolute paths, traversal, and symlink escapes reject. New files use UTF-8. Lossless writes preserve existing newline and comment content where the format supports it.

Declarations may override bounded operational defaults such as maximum document bytes, backup count, and reload mode. They cannot disable path confinement, atomic replacement, comment preservation, stale-source checks, resource limits, or diagnostic redaction.

## Built-in value support

Kotlin Serialization owns primitives, enums, collections, maps, nullability, nested data classes, sealed types, and value classes. Ashlar supplies human-readable serializers for stable operational values such as:

- Kotlin `Duration`;
- UUID;
- Adventure `Key`;
- Bukkit `NamespacedKey`;
- stable Paper registry keys.

The module does not deserialize loaded worlds, players, entities, or locations. A setting stores a stable name, UUID, key, or plug-in domain value and resolves live state later under the proper ownership context.

## Inspection

`Configurations.inspect()` exposes immutable operational metadata:

- relative path and format;
- current schema and source revision;
- reload mode and watcher status;
- accepted or rejected operational status;
- warning counts and value-free problem summaries;
- backup identifiers and timestamps.

Inspection exposes neither values nor mutation capabilities. Observers and logs follow the same redaction rule.

## Testing contract

`ashlar-config-test` drives production parsing, migrations, validation, handles, source revisions, writes, watching, backups, and restore against isolated temporary directories. Bounded polling is reserved for real `WatchService` behavior. It covers:

- missing-file creation and complete defaults;
- every built-in format and custom-format contribution;
- unknown and duplicate keys with source locations and suggestions;
- validation errors and accepted warnings;
- valid, invalid, unversioned, future, and broken migration chains;
- comment and formatting retention, including orphan comments;
- comment-only reloads without value emission;
- editor bursts, atomic rename, watched rejection, and recovery;
- stale external edits and concurrent explicit operations;
- backup rotation, corrupt backup, restore, and restore rollback;
- process restart and concurrent explicit operations;
- plug-in shutdown cleanup in the Paper and Folia fixtures;
- diagnostic and inspection redaction;
- semantic equality after lossless document patches.

Real filesystem tests cover `WatchService`, symlink confinement, atomic replacement, editor rename saves, and process restart. Paper and Folia fixtures verify startup ordering, automatic component injection, lifecycle cleanup, and that file work does not violate server ownership. Configuration values themselves require no connected-client acceptance.

## Documentation completion

Implementation is not complete until the repository contains:

- a first-configuration tutorial;
- task guides for validation, migrations, watched reload, explicit updates, custom formats, and recovery;
- complete API, format, schema, diagnostic, and limit reference;
- an explanation of source documents, values, and atomic publication;
- agent authoring and implementation workflows;
- KDoc, ABI baselines, sample coverage, and Paper/Folia fixture evidence.

Until those APIs exist, this design page and the configuration ADRs are the only configuration documentation. Planned syntax here must not be presented as shipped reference.

## Implementation slices

Implementation proceeds in finished slices rather than one broad scaffold:

1. Add structural generic dependency keys, generated closed-type lookup, graph and processor tests, ABI updates, and DI documentation.
2. Add the three configuration artifacts, managed build wiring, pre-lifecycle definitions, direct handle injection, lossless YAML, missing-file defaults, manual reload, validation, and the deterministic test kit.
3. Add explicit lossless updates, source fingerprints, stale-write rejection, atomic replacement, backup rotation, and validated restore.
4. Add opt-in watching, event and value flows, rejection deduplication, recovery logging, bulk reload, and redacted inspection.
5. Add reserved schema metadata, unversioned adoption, typed sequential migrations, removed-key comment retention, and persist-before-publish behavior.
6. Add TOML, strict JSON, JSONC, the public format seam, stable value serializers, and common resource limits.
7. Add the playable sample, tutorial, how-to guides, reference, agent docs, ABI baselines, benchmarks, and full Paper/Folia verification.

Each slice must leave its public path documented, tested, and usable. Later slices may deepen the same runtime; they must not add a parallel registry API, mutable configuration objects, generic document storage, or generated runtime behavior.
