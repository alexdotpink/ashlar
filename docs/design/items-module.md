# Items module design

Status: implemented in `framework-items`

The items module is the framework-wide item model. It builds authored items, captures live items without loss, and gives plug-ins durable typed custom-item identity. Menus depend on it, but hotbars, commands, events, storage, crafting, and later modules use the same API directly.

## Goals

- Express every item feature exposed by the pinned Paper data-component API.
- Keep authored item definitions immutable, reusable, and readable.
- Preserve Paper's exact captured native bytes through storage and transactions.
- Give custom items namespaced identity, typed versioned payloads, migrations, and structured decode failures.
- Support optional payload authentication without burdening ordinary items.
- Materialize presentation deliberately for normal items and decorative menu actions.
- Keep mutable `ItemStack` values at Paper adapter boundaries.

## Non-goals

- The module does not own clicks, combat, block interaction, cooldowns, recipes, or other gameplay behavior.
- It does not create a second event or command system around custom items.
- It does not use reflection, Java object serialization, or arbitrary object storage in PDC.
- It does not promise that an authored item can vary per viewer after it becomes a real shared inventory item. Per-viewer presentation is available when a caller materializes a virtual presentation such as a menu action.
- It does not treat signatures as encryption or item-duplication prevention.

## Public artifact

The managed build exposes one production artifact:

```kotlin
frameworkPlugin {
    items()
}
```

`framework-menus` enables items transitively. The item module has no KSP processor, annotations, reflection, or compiler plug-in.

## Authored item specifications

`ItemSpec` is an immutable recipe for an item the plug-in intentionally authors:

```kotlin
val waypointIcon: ItemSpec = item(Material.COMPASS) {
    amount = 1
    name = text("Public waypoints")

    lore {
        line(text("Browse every public waypoint."))
        emptyLine()
        line(text("Left-click to open."))
    }

    data(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true)
    persistent(keys.category, Category.PUBLIC, CategoryCodec)
}
```

The builder is a construction convenience, not a mutable item object. It defensively copies common container and array component values and produces one immutable value. A spec may be copied and transformed without mutating the source:

```kotlin
val selectedIcon = waypointIcon.edit {
    name = text("Selected public waypoints")
    data(DataComponentTypes.RARITY, ItemRarity.RARE)
}
```

The API exposes typed helpers for common components and a generic typed `data(type, value)` operation for the complete Paper component catalogue. New Paper components do not require a new framework release before callers can use their typed native component key and value. The keyed `paper` mutation is the unavoidable advanced escape hatch: callers must make the callback deterministic and side-effect-free because an arbitrary closure cannot be made deeply immutable by the framework.

Material-specific validation happens before a stack reaches a player. Invalid amounts, contradictory components, unsupported material/component combinations, and oversized persistent payloads produce descriptive failures containing the item definition path.

## Item presentation

An `ItemPresentation` controls tooltip and visual policy at materialization time:

```kotlin
items.materialize(
    spec = waypointIcon,
    presentation = ItemPresentation.MenuAction,
)
```

The neutral default preserves ordinary vanilla presentation. `MenuAction` hides irrelevant damage, attribute, and durability noise normally undesirable on a virtual action icon. A caller can restore any vanilla field explicitly. Storage slots never apply decorative defaults to player-owned items.

Presentation is separate from identity and payload. It can depend on the current locale, theme, and viewer when materializing a virtual item without changing the reusable spec.

## Lossless live snapshots

`ItemSnapshot` is an immutable lossless capture of one live stack:

```kotlin
val before: ItemSnapshot = items.capture(paperStack)

val after = before.edit {
    amount -= 1
}

val restored: ItemStack = items.materialize(before)
```

Capture stores the exact amount-normalized bytes returned by Paper without rewriting them. Materialization gives those opaque bytes back to the same pinned Paper line. Paper itself may normalize an equivalent item during deserialize/serialize, so equality, stackability, and fingerprints use a separate canonical semantic identity rather than requiring byte-identical recapture. Uninterpreted native data remains in the original payload when a snapshot changes only its amount. A native edit necessarily passes through Paper and captures the new exact result. Transactions, persistence adapters, diagnostics, and recovery use snapshots rather than mutable stacks.

Server-free algorithms may use detached semantic snapshots for planning and conservation checks. Detached values have no Paper payload and cannot be materialized or persisted. Durable transaction state must contain native snapshots captured by Paper.

`ItemSpec` and `ItemSnapshot` remain separate because they optimize for different work:

| Type | Purpose | Normal source |
| --- | --- | --- |
| `ItemSpec` | Readable authored recipe | Kotlin item DSL or custom-item renderer |
| `ItemSnapshot` | Exact live value | Captured Paper stack, storage, transaction, or recovery |

Raw `ItemStack` equality is not a framework identity operation. Snapshot equality and stackability use explicit framework rules aligned with the pinned server behavior.

## Typed custom items

A custom item definition owns durable identity and data, not gameplay behavior:

```kotlin
@Serializable
data class WaypointTokenData(
    val waypointId: UUID,
    val ownerId: UUID,
)

val WaypointToken = customItem<WaypointTokenData>(
    id = key("waypoint_token"),
) {
    data(WaypointTokenData.serializer())

    render { token ->
        item(Material.COMPASS) {
            name = text("Waypoint token")
            lore {
                line(text(token.waypointId.toString()))
            }
        }
    }
}
```

Creation and recognition are typed:

```kotlin
val stack = WaypointToken.create(
    WaypointTokenData(waypoint.id, player.uniqueId),
)

when (val token = WaypointToken.read(stack)) {
    is CustomItemRead.Found -> redeem(token.data)
    CustomItemRead.NotThisItem -> pass()
    is CustomItemRead.InvalidData -> report(token.problem)
    is CustomItemRead.InvalidSignature -> confiscateAndReport(token.problem)
}
```

The identity is namespaced to the plug-in. The encoded envelope records the item ID, schema version, codec identity where required, canonical payload bytes, and optional integrity metadata. Recognition never collapses wrong item, malformed data, unsupported version, migration failure, and failed authenticity into one nullable result.

Behavior composes through its owning module:

```kotlin
@Events
class WaypointTokenEvents(
    private val teleporter: WaypointTeleporter,
) {
    @Observe
    suspend fun PlayerInteractEvent.useWaypointToken() {
        val token = WaypointToken.readOrNull(item) ?: return
        teleporter.teleport(PlayerRef(player.uniqueId), token.waypointId)
    }
}
```

Recipes belong to the future crafting module. Interaction belongs to events. Presentation in menus belongs to menus. The item definition remains reusable in every context.

## Payload codecs and migrations

Kotlin Serialization is the default codec. It uses fixed strict settings and recursively sorts JSON object keys so logically equal map payloads have canonical bytes:

```kotlin
customItem<VoucherData>(key("voucher")) {
    data(VoucherData.serializer())
}
```

The public codec seam remains small and typed for existing protocols:

```kotlin
customItem<LegacyToken>(key("legacy_token")) {
    data(ProtobufItemCodec(LegacyToken.parser()))
}
```

Every codec has a constrained durable ID. A custom Kotlin `Json` configuration must supply a different explicit ID; configuration changes that affect wire semantics require another ID. The framework owns envelope framing, schema versions, payload limits, canonical signing bytes, and structured diagnostics. A migration converts a known older version into the current typed value:

```kotlin
migrate(fromVersion = 1) { old ->
    old.toCurrent()
}
```

Migration is explicit and ordered. Missing paths fail decoding without rewriting the source stack. A caller chooses when an old item should be rematerialized in its current form.

## Optional integrity

Definitions may authenticate payloads with a versioned HMAC keyring:

```kotlin
integrity {
    hmacSha256(
        activeKey = secrets.itemSigningKey,
        verificationKeys = secrets.previousItemSigningKeys,
    )
}
```

Signing is off by default. The signature covers custom-item identity, schema version, and canonical payload bytes. Presentation fields are excluded unless a definition opts them in. Verification accepts configured previous keys so rotation does not invalidate existing items; new materialization always uses the active key.

Secret retrieval and rotation use plug-in configuration or secret infrastructure, not hard-coded framework keys. Error output identifies the definition and failure category without logging raw secret material or sensitive payloads.

## Persistence

The item module defines stable snapshot encoding required by menu recovery and storage adapters. Encoding is versioned, checksummed, bounded, and native-only. A decoder verifies both the envelope and the native payload against the running pinned Paper line. It either reconstructs a compatible native snapshot or returns a structured malformed, corrupt, unsupported-version, or native-incompatible outcome; it never silently drops unknown data or accepts a detached value as durable state.

The module does not prescribe a database. A plug-in may store snapshot bytes in SQL, document storage, files, or another framework module later.

## Testing

Item tests must cover:

- spec materialization for every supported typed component helper;
- exact snapshot capture/materialization round trips;
- preservation of uninterpreted data after unrelated edits;
- stackability and amount boundaries;
- custom-item create/read round trips;
- every structured decode failure;
- migrations across every supported schema version;
- signature verification, tampering, and key rotation;
- persistence encoding compatibility;
- real Paper materialization for the pinned server line.

Property tests should generate legal item snapshots and verify conservation through edit, encode, decode, and materialize cycles. Real Paper fixtures remain responsible for native component behavior; server-free tests own the immutable model and codecs.

## Implementation record

The shipped module completed the planned slices:

1. The artifact, immutable `ItemSpec`, generic typed data components, neutral materialization, and Paper round-trip checks.
2. `ItemSnapshot`, exact capture, equality, editing, checksummed persistence encoding, and conservation tests.
3. Presentation policies and per-viewer materialization context.
4. Typed custom-item identity, Kotlin Serialization codecs, structured reads, and migrations.
5. Pluggable codecs, optional HMAC integrity, key rotation, optional presentation coverage, reference docs, and ABI baselines.

No menu code enters the item module. The pinned Paper and Folia fixtures prove the snapshot and persistence contracts before menu storage consumes them.
