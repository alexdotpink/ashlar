# Configuration authoring workflow for agents

Use this workflow when adding or changing plug-in settings.

## 1. Confirm the data is configuration

Use configuration only for static, human-edited operational settings. Put player preferences, domain records, caches, and caller-named documents in a persistence module. Store stable references to secrets, not raw secret resolution logic.

List each required static document and its relative path. Decide whether more than one document uses the same Kotlin type. If so, define one `@DependencyQualifier` annotation per document.

## 2. Enable the smallest build feature

Add `config()` to the managed `ashlar` block. Do not add the runtime, serialization plug-in, or KSP processors by hand when the managed plug-in owns the build.

## 3. Declare an immutable root

Use a final `@Serializable` data class with `@Config`. Give every primary constructor parameter a Kotlin default. Write KDoc on the root and properties because it initializes comments in new documents.

Choose YAML for ordinary operator-edited settings. Use JSONC when surrounding tooling expects JSON-shaped data with comments. Use strict JSON only when comments are not needed. Use TOML only when the value cannot contain null.

Use stable strings, UUIDs, duration serializers, and registry keys for server identities. Do not put `Player`, `World`, `Entity`, or `Location` in a configuration root.

## 4. Add pure validation

Add a top-level `@ConfigValidation` extension for cross-field rules. Use property references in every `requireValue` and `warnIf` call. Aggregate independent rules instead of returning after the first problem.

Keep I/O, dependency resolution, Paper access, and mutation out of validation.

## 5. Preserve schema compatibility

Adding a defaulted property does not require a migration. A rename, removal, representation change, or required semantic conversion does.

Keep serializable historical types and add one `@ConfigMigration` extension for each adjacent schema. Increase `schemaVersion` only with a complete chain. Set `unversionedSchema` only when the shape of old unmarked installations is known.

Never add compatibility parsing to the current data class to avoid a migration. It hides which schema was accepted and makes removal hard to verify.

## 6. Inject the exact handle

Inject `ConfigHandle<Root>` into application code. Add the matching DI qualifier to the constructor parameter when the root has repeated `@Config` declarations.

Read `current` for synchronous behavior. Collect `values` for work driven only by changed accepted values. Collect `events` for diagnostics and attempt history. Own collectors in a lifecycle component and enter an Ashlar ownership context before Paper access.

Use `Configurations` only for administrator-wide reload or redacted inspection. Do not add a parallel configuration registry.

## 7. Handle every operation outcome

Match every `ConfigReload`, `ConfigWrite`, and `ConfigRestore` variant. Report `Rejected` problems without raw source values. Treat `Unavailable` as a recoverable file problem. On `SourceChanged`, reload and base any retry on the newly accepted value.

Do not read or write the active file beside `ConfigHandle`. Direct writes bypass validation and become external edits.

## 8. Verify declaration and runtime behavior

Use `ashlar-config-test` for the production parser and handle without Paper. Cover:

- complete creation from defaults;
- accepted and rejected reloads;
- every validation error and warning;
- each migration starting schema;
- comment retention for explicit writes;
- stale external edits;
- backup rotation and restore;
- watched rejection and recovery when `WATCH` is enabled;
- qualifier separation when a root declares several documents.

Run processor compilation so KSP verifies the actual declarations and generated direct calls. Run `build` and `checkKotlinAbi` when framework public declarations change. Use Paper and Folia fixtures for startup order, injection, watcher cleanup, and server ownership. Configuration values alone do not require a connected client.

Read the [configuration reference](../reference/configuration.md) for signatures and limits. Read [source documents and configuration values](../explanation/configuration-model.md) before changing update or comment behavior.
