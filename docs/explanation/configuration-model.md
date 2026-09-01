# Source documents and configuration values

Ashlar treats the source document and the Kotlin value as different things.

The source document belongs to the server operator. It has text, comments, ordering, whitespace, a schema marker, and an exact content revision. The configuration value belongs to plug-in code. It is an immutable serializable object that has passed migration, decoding, and validation.

This split prevents two common mistakes. A plug-in cannot mutate an in-memory tree and accidentally expose half an edit. The framework cannot regenerate a whole file and erase the operator's notes each time one property changes.

## Acceptance is one boundary

Parsing does not make a source current. Neither does decoding. Ashlar accepts a candidate only after every stage succeeds:

```text
bounded source
  -> lossless document
  -> schema migration
  -> typed decode
  -> validation
  -> required backup and persistence
  -> atomic publication
```

Readers see the old complete value until the last step. Rejected syntax, a failed migration, validation errors, and file write failures all leave `current` unchanged.

Migrations and plug-in updates use persist-before-publish. If Ashlar published first, a component could act on a value that never reached disk. Restarting the server would then restore older settings. Persisting first keeps the visible value and active document on the same accepted revision.

## Lossless documents carry operator intent

Kotlin Serialization gives Ashlar the semantic value tree. It cannot retain where a comment appeared or whether an operator chose a particular layout. Each format therefore owns a `ConfigDocument` with both the semantic projection and enough parsed source to patch it.

An update changes only the semantic parts that differ. The format keeps existing comments and layout around them. KDoc has a narrower job. It supplies comments for a new document or a newly inserted key. After creation, existing comments belong to the operator, even if the KDoc later changes.

Strict JSON is the deliberate exception because comments are not part of JSON. JSONC provides the JSON-shaped format with comment retention. TOML retains comments but TOML 1.0 cannot represent null. A nullable property with a null value therefore needs YAML, JSON, or JSONC.

## Revisions prevent quiet overwrites

Every accepted source has an opaque fingerprint. Before an update writes, Ashlar reads the active file again and compares its fingerprint with the accepted one. A mismatch returns `ConfigWrite.SourceChanged`.

Automatic merging would need to decide whether plug-in code or an operator owns each conflicting key and comment. The configuration module has no sound general answer. It refuses the write and lets application code reload, show the rejection, or ask an administrator to resolve it.

## Values and attempt events have different jobs

`StateFlow<T>` represents accepted typed state. It emits only when the value changes. `Flow<ConfigEvent<T>>` represents attempts. It includes rejections, operational failures, warnings, origins, revisions, and accepted comment-only edits.

A comment-only edit matters to stale-write protection, so it changes the accepted source revision. It does not change `T`, so the state flow stays quiet. Code that only rebuilds behavior from values does not run again. Operational tooling can still observe the accepted event.

## KSP links declarations but does not run configuration

The processor has access to facts the runtime should not rediscover with reflection. It knows the closed `ConfigHandle<T>` type, DI qualifier, serializer, KDoc, and exact validation and migration functions.

Generated code records those facts and calls the handwritten runtime. It does not generate parsers, handles, watchers, validation bodies, migration bodies, or file operations. This keeps behavior in ordinary Kotlin that unit tests and code navigation can follow. It also keeps generated source small enough to inspect when a compiler error points into it.

The [configuration reference](../reference/configuration.md) defines the exact API. The [configuration authoring workflow](../agents/config-authoring.md) tells coding agents how to apply it.
