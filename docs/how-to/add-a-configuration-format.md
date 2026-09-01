# Add a configuration format

Implement `ConfigFormat` when a plug-in needs a static configuration extension beyond YAML, TOML, JSON, and JSONC. One format owns every file with one of its extensions.

Declare the format as an injected DI contribution:

```kotlin
@Contributes
@Inject
class PropertiesConfigFormat : ConfigFormat {
    override val id: String = "properties"
    override val extensions: Set<String> = setOf("properties")
    override val preservesComments: Boolean = true

    override fun parse(source: ConfigSource, limits: ConfigLimits): ConfigParse {
        // Enforce every applicable limit before returning a document.
        // Reject duplicate keys and return source-located ConfigProblem values.
        return parsePropertiesDocument(source, limits)
    }

    override fun create(
        value: ConfigValue.ObjectValue,
        comments: Map<ConfigKeyPath, String>,
    ): ConfigDocument = createPropertiesDocument(value, comments)

    override fun write(document: ConfigDocument): String {
        require(document.formatId == id)
        return (document as PropertiesDocument).source
    }
}
```

The parser must return a format-owned `ConfigDocument`. Keep both the source representation needed for lossless edits and the format-neutral `ConfigValue.ObjectValue`:

```kotlin
data class PropertiesDocument(
    override val value: ConfigValue.ObjectValue,
    val source: String,
    val entries: List<PropertiesEntry>,
) : ConfigDocument {
    override val formatId: String = "properties"

    override fun location(key: ConfigKeyPath): ConfigSourceLocation? =
        entries.firstOrNull { it.path == key }?.location

    override fun patch(
        value: ConfigValue.ObjectValue,
        newComments: Map<ConfigKeyPath, String>,
    ): ConfigDocument = patchProperties(this, value, newComments)
}
```

`patch` receives the new semantic tree and comments for newly inserted keys. Retain existing whitespace, comments, ordering, and other format trivia. If `preservesComments` is `true`, framework writes must not remove any comment token. Removed-key comments must remain at the same parent unless the format moves them to a replacement.

Apply `ConfigLimits.maximumBytes`, `maximumDepth`, and `maximumScalarCharacters`. Apply `maximumAliases` if the format has aliases or references. Return `ConfigParse.Rejected` for expected source problems. Throw only for a bug or a document from the wrong format passed to `write`.

Extension names must be lowercase-compatible names without a dot. Format IDs cannot be blank. Startup rejects two formats that own the same extension. Custom formats are added before built-ins, but they cannot replace a built-in extension because duplicate ownership rejects.

Test the format with `configTest(formats = listOf(PropertiesConfigFormat(), ...))`. Cover duplicate keys, each resource limit, source locations, complete creation, semantic patches, removed keys, and comment retention.

The [format reference](../reference/configuration.md#configformat) lists the full contract.
