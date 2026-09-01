# Configuration implementation workflow for agents

Use this workflow when changing `ashlar-config`, `ashlar-config-ksp`, or `ashlar-config-test`. Plug-in settings changes use [configuration authoring](config-authoring.md) instead.

## 1. Locate the owning layer

Keep public contracts in `ashlar-config`. Keep file, codec, validation, watcher, and publication behavior in handwritten runtime code. Keep KSP limited to declaration checks and static linkage. Keep deterministic driving helpers in `ashlar-config-test`.

Do not solve a runtime behavior change by generating a second implementation. Do not expose internal document storage through `ConfigHandle`.

## 2. Preserve the acceptance boundary

Trace the complete operation before editing it. Parsing, migration, typed decoding, validation, backup, persistence, and publication have a fixed order. A new exit path before publication must retain the accepted value and source revision.

For migrations, restores, and updates, write tests that fail after each persistence boundary. Verify that publication occurs only after the required file operation succeeds.

## 3. Preserve source ownership

Format changes need semantic assertions and source-text assertions. Verify unchanged comments, standalone comments, key order where retained, whitespace around untouched values, newline style, and comments from removed keys.

Test YAML, TOML, and JSONC independently. Do not infer one format's result from another. Keep strict JSON comment-free. Include TOML's lack of null in tests and diagnostics.

Use randomized patch round trips for parser and patcher changes. Reparse every produced document and compare its `ConfigValue` with the requested tree.

## 4. Keep expected failures typed and redacted

Malformed or unsupported content returns `ConfigProblem`. Recoverable file and watch failures return `ConfigOperationProblem`. Unexpected implementation faults throw.

Add a test that searches problem messages, events, inspection records, and logs for representative source values. Diagnostics may name a path, key, type rule, category, and location. They must not echo raw configuration values.

## 5. Maintain confinement and bounds

Run real filesystem tests for absolute paths, traversal, Windows drive forms, symlink escapes, directory replacement, permissions, temporary files, atomic move fallback, and restart. Never relax path confinement through a declaration option.

Every parser must enforce bytes, nesting, scalar size, duplicates, and any format-specific expansion such as YAML aliases before typed decoding.

## 6. Keep watchers subordinate to lifecycle

Start watchers only after initial acceptance and binding. Coalesce editor bursts and atomic replacement saves. Deduplicate repeated rejection attempts by source revision, then verify recovery on a later valid revision.

Shutdown must cancel watcher work, close watch services, and leave no collector or temporary file behind. File work stays on the I/O dispatcher and grants no Paper ownership.

## 7. Keep generated source small

Processor tests must inspect and compile generated Kotlin. Generated code may contain metadata, exact structural DI keys, serializer calls, and direct validator or migration calls. Reject any change that adds format parsing, file access, watcher logic, validation bodies, migration bodies, or reflective lookup to generated source.

Update the processor fixture when a public declaration rule changes. Test invalid models for a precise compiler diagnostic.

## 8. Verify the claimed layer

Run focused module and processor tests during the change. Before completion, run:

```bash
./gradlew :ashlar-config:test :ashlar-config-ksp:test :ashlar-config-test:test
./gradlew :ashlar-config-test:compileProcessorFixtureKotlin
./gradlew build checkKotlinAbi
python3 scripts/check_docs.py
```

Run Paper and Folia integration fixtures after changes to startup initialization, DI binding, watchers, cleanup, or ownership. Report which formats, failure boundaries, and server implementations the evidence covers.
