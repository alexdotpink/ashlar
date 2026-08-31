# Items reference

Enable the item module through the managed build:

```kotlin
frameworkPlugin {
    items()
}
```

The module has no KSP processor and registers no listeners. It supplies immutable item recipes, exact snapshots, presentation policies, and typed custom-item identity.

## Authored items

`item(material) { ... }` produces an immutable `ItemSpec`. The builder is discarded after construction.

```kotlin
val waypointIcon = item(Material.COMPASS) {
    name = Component.text("Public waypoints")
    lore {
        line(Component.text("Browse every public waypoint."))
        emptyLine()
        line(Component.text("Left-click to open."))
    }
    glint()
    persistent(categoryKey, "public", PersistentValueCodecs.StringUtf8)
}

val selectedIcon = waypointIcon.edit {
    name = Component.text("Selected waypoints")
    rarity(ItemRarity.RARE)
}
```

`edit` starts with the source material, amount, and changes. It returns a new spec and does not mutate the source.

Common helpers cover name, lore, glint, rarity, and persistent values. Use `data(type, value)`, `data(type)`, `unsetData(type)`, and `resetData(type)` for Paper's complete data-component catalogue. These operations use Paper's generic types, so a new component does not require a matching framework release. `paper(key) { stack -> ... }` is the keyed escape hatch for metadata not represented by the typed API.

`data(type, value)` defensively copies arrays, lists, sets, and maps when the spec is built and again when it is materialized. Paper component values outside those common shapes must themselves be immutable. A `paper` mutation cannot be copied; it must be deterministic and side-effect-free, and its key must change whenever its output semantics change. The key is what gives the escape hatch structural equality across renders.

Materialization validates that the material is an item and that its amount fits the effective maximum stack size after component changes:

```kotlin
val stack: ItemStack = Items.materialize(waypointIcon)
```

Do not retain and mutate that stack as shared framework state. Keep the spec or capture a snapshot.

## Persistent values

`persistent` encodes the value immediately and copies the result. `removePersistent` removes an inherited or previously applied key. Values cannot exceed 32 KiB. The built-in codecs cover UTF-8 strings, big-endian `Int` and `Long`, UUIDs, and opaque bytes.

Implement `PersistentValueCodec<T>` when a domain value needs its own deterministic encoding. The item module stores the encoded value as a PDC byte array under the supplied `NamespacedKey`.

Read values through `Items.persistent(stack, key, codec)` or its snapshot overload. `PersistentValueRead` distinguishes `Found`, `Missing`, and `Invalid` so valuable items do not treat corruption as absence.

## Presentation

`ItemPresentation.Neutral` is the materialization default. It leaves vanilla tooltip behavior alone. `ItemPresentation.MenuAction` hides damage, maximum-damage, and attribute components on virtual action icons.

```kotlin
val icon = Items.materialize(
    waypointIcon,
    presentation = ItemPresentation.MenuAction,
    context = ItemPresentationContext(
        viewerId = player.uniqueId,
        locale = player.locale(),
        theme = "dark",
    ),
)
```

`ItemPresentation` is a fun interface. A custom policy can use the viewer, locale, and theme in the context. Presentation runs after the spec changes. Use `Neutral` for player-owned, stored, or purchasable items.

## Exact snapshots

`ItemSnapshot` captures Paper's opaque native item bytes exactly as `serializeAsBytes()` returns them. It is separate from `ItemSpec` because it must preserve arbitrary live components that the plug-in did not author.

```kotlin
val before = Items.capture(stack)
val after = before.edit { amount -= 1 }
val restored = Items.materialize(after)
```

The editor works on an isolated native copy. It supports material, amount, typed data components, component removal, and a `paper` escape hatch. Unchanged native data survives the edit.

The amount lives in the framework envelope, while the captured Paper payload is amount-normalized and otherwise unchanged. Paper may normalize its own serialization when bytes are deserialized and serialized again, so the framework keeps two representations: the exact captured bytes used for persistence and materialization, and a canonical semantic identity used for equality, stackability, and fingerprints. Two native snapshots compare equal when their material, amount, effective maximum, and canonical identity match even if Paper emitted different equivalent byte sequences. `stackableWith`, `withAmount`, and amount-only `edit` need no server access.

Transaction models and server-free tests can create a detached snapshot without inventing a mutable `ItemStack`:

```kotlin
val stone = ItemSnapshot.detached(
    material = Material.STONE,
    amount = 32,
    maximumAmount = 64,
    stackabilityKey = "ordinary-stone",
)
```

Detached snapshots expose the same amount, maximum, equality, stackability, and amount-only editing behavior. They are deliberately process-local semantic values: `hasNativeData` is false, and both `encode()` and `Items.materialize` reject them with a contract error. A transaction journal, mailbox, or database must contain native snapshots captured on Paper. Their stackability key belongs to the caller, so equal keys must mean the values may really share a stack.

`snapshot.encode()` adds a versioned framework header and SHA-256 checksum. `ItemSnapshot.decode(bytes)` returns one of:

| Outcome | Meaning |
| --- | --- |
| `Found` | The framework envelope and checksum are valid |
| `Malformed` | Framing, material, amount, length, or trailing bytes are invalid |
| `UnsupportedVersion` | A newer framework envelope format wrote the value |
| `Corrupt` | The checksum covering the complete envelope does not match |
| `NativeIncompatible` | The envelope is valid, but Paper cannot deserialize it or its material, maximum, or semantic identity disagrees with the envelope |

`decode` validates the native payload immediately against the running pinned Paper version. It must therefore run inside a live Paper runtime, just like materialization. Structurally valid bytes from an incompatible server line return `NativeIncompatible`; they are never accepted as a detached or partially decoded value.

## Typed custom items

A `CustomItemDefinition<T>` owns a namespaced ID, current schema version, payload codec, renderer, migrations, and optional integrity policy. It does not own interaction handlers or recipes.

```kotlin
@Serializable
data class WaypointTokenData(
    val waypointId: String,
    val ownerId: String,
)

val WaypointToken = customItem<WaypointTokenData>(
    NamespacedKey(plugin, "waypoint_token"),
) {
    version = 2
    data(WaypointTokenData.serializer())
    render { token ->
        item(Material.COMPASS) {
            name = Component.text("Waypoint token")
            lore { line(Component.text(token.waypointId)) }
        }
    }
}

val stack = WaypointToken.create(data)
```

Kotlin Serialization JSON is the default typed codec. It writes defaults, explicit nulls, a fixed class discriminator, strict JSON, and recursively sorted object keys. Its durable codec ID is `kotlin-json-canonical-v1`.

A custom `Json` configuration is a different wire protocol and must have an explicit ID:

```kotlin
data(
    serializer = WaypointTokenData.serializer(),
    json = Json(DefaultItemJson) { prettyPrint = true },
    codecId = "waypoint-json-pretty-v1",
)
```

Change that ID whenever the configuration changes encoded or decoded semantics. Codec IDs use lowercase letters, digits, dots, underscores, and hyphens and are limited to 128 UTF-8 bytes. Implement `CustomItemCodec<T>` to use an existing deterministic binary protocol; custom codecs follow the same ID rules.

Recognition is structured:

```kotlin
when (val read = WaypointToken.read(stack)) {
    is CustomItemRead.Found -> redeem(read.data)
    CustomItemRead.NotThisItem -> Unit
    is CustomItemRead.InvalidData -> report(read.problem)
    is CustomItemRead.UnsupportedVersion -> report("Written by schema ${read.found}")
    is CustomItemRead.MigrationFailed -> report(read.problem)
    is CustomItemRead.InvalidSignature -> confiscate(stack, read.problem)
}
```

`readOrNull` is for paths where every invalid outcome has the same harmless treatment. Security-sensitive paths should keep the sealed result.

## Migrations

Register each supported old schema with its old codec and a direct conversion to the current type:

```kotlin
migrate(fromVersion = 1, codec = KotlinJsonItemCodec(V1Token.serializer())) { old ->
    WaypointTokenData(old.waypointId, ownerId = "unknown")
}
```

Reading an old item does not rewrite it. `Found.sourceVersion` and `Found.migrated` tell the caller whether to create a current replacement. A missing path, wrong old codec ID, or thrown conversion returns `MigrationFailed`.

## HMAC integrity

Signing is opt-in. Give keys stable IDs so old items survive rotation:

```kotlin
integrity(
    HmacKeyring(
        active = HmacKey("2026-08", activeSecret),
        verificationKeys = listOf(HmacKey("2026-05", previousSecret)),
    ),
)
```

Keys must contain at least 32 bytes. New items use the active key. Reads accept every key in the ring and reject missing, unknown, or invalid signatures as `InvalidSignature`. Diagnostics never contain key or payload bytes.

By default the signature covers item ID, schema version, codec ID, and canonical payload bytes. Pass `includePresentation = true` to `integrity` to include the native rendered stack before the framework envelope is attached. That catches changes to the item's name, lore, material, components, or other PDC values. It also ties verification to native serialization on the pinned Paper line.

HMAC detects unauthorized changes. It does not encrypt data and cannot stop a valid stack from being duplicated.

## Bounds and failures

- Authored and edited amounts must be from 1 through 99. Materialization also enforces the item's effective maximum.
- Ordinary persistent values are limited to 32 KiB.
- Custom payloads default to 32 KiB and may be configured up to 1 MiB.
- Snapshot native payloads are limited to 4 MiB.
- Broken authored definitions throw before returning an item.
- Expected custom-item and persistence decode problems use sealed outcomes.

Paper objects stay at the adapter boundary. Construct specs anywhere, but materialize, capture, edit snapshots, and read custom stacks only where the caller already has valid server access.
