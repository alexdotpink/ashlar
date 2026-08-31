# Native Minecraft argument reference

These handler types use Paper's native Brigadier argument types. The adapter resolves Paper providers before asynchronous handler execution and returns stable identities, immutable snapshots, or framework-owned operations.

| Handler type | Native syntax / meaning | Result contract |
| --- | --- | --- |
| `PlayerRef` | one player selector | Stable UUID; use `access(plugin)` |
| `PlayerSelection` | player selector | Ordered stable player references |
| `EntityRef` | one entity selector | Stable UUID; access may return `Retired` |
| `EntitySelection` | entity selector | Ordered stable entity references |
| `PlayerProfileSelection` | player profiles | `PlayerProfileSnapshot` values containing detached UUID, name, and `ProfilePropertySnapshot` values |
| `BlockRef` | block position | Stable world and integer position; use region-owned `access` |
| `BlockColumnRef` | block column | Stable world, x, z; call `at(y)` for a `BlockRef` |
| `FinePositionSnapshot` | fine position | Detached world and x/y/z |
| `FineColumnSnapshot` | fine column | Detached world and x/z |
| `MinecraftRotation` | absolute or relative rotation | Detached yaw and pitch |
| `MinecraftAngle` | absolute or relative angle | Detached degrees |
| `AxisSelection` | compact axes such as `xz` | Non-empty set of Bukkit axes |
| `BlockStateInput` | Minecraft block-state syntax | Detached `BlockData`; region-safe `place` operation |
| `BlockPredicate` | block predicate | Suspendable test returning `BlockPredicateResult`: match, no match, or unloaded chunk |
| `ItemStackSnapshot` | Minecraft item-stack syntax | Defensive copy; `copy()` returns another clone |
| `ItemPredicate` | Minecraft item predicate | Tests an `ItemStack` |
| `NamedTextColor` | named color | Adventure named color |
| `TextColor` | hexadecimal color | Adventure color; native input is the bare hex value |
| `Component` | JSON/text component | Adventure component |
| `Style` | component style | Adventure style |
| `SignedMessageInput` | signed message | Content plus suspendable signature resolution |
| `DisplaySlot` | scoreboard slot | Bukkit display slot |
| `NamespacedKey` | namespaced key | Bukkit key |
| `Key` | namespaced key | Adventure key |
| `MinecraftIntegerRange` | `min..max` integer range | Inclusive optional bounds |
| `MinecraftDoubleRange` | `min..max` double range | Inclusive optional bounds |
| `WorldRef` | world | Stable key; `resolve(plugin)` uses global ownership |
| `GameMode` | game mode | Bukkit enum |
| `HeightMap` | height map | Bukkit enum |
| `UUID` | UUID | Java UUID |
| `ScoreboardCriterion` | objective criterion | Detached name, read-only flag, and render type |
| `LookAnchor` | entity anchor | Paper enum |
| `MinecraftTime` | Minecraft duration | Non-negative server ticks |
| `Mirror` | structure mirror | Bukkit enum |
| `StructureRotation` | structure rotation | Bukkit enum |
| `RegistryValueRef<T>` | value from one registry | Stable registry and value keys |
| `RegistryValueKey<T>` | key from one registry | Typed registry and value keys without resolution |

## Native qualifiers

`@CenterIntegers` centers integral fine-position coordinates on their containing blocks. `@MinimumTicks(value)` changes the minimum accepted Minecraft time. `@FromRegistry("minecraft:...")` is required on `RegistryValueRef<T>` and `RegistryValueKey<T>` to select the Paper registry.

## Ownership rules

`PlayerRef.access` and `EntityRef.access` return `EntityOutcome<T>`. `BlockRef.access` returns `null` if its world is unavailable. `WorldRef.resolve` returns `null` when the world is not loaded. These methods do not retain live Paper objects after the callback.

`BlockPredicate.test(plugin, block)` does not synchronously load an absent chunk. Pass `loadChunk = true` only when that behavior is deliberate. `BlockStateInput.place` performs the mutation under the target region's ownership. `SignedMessageInput.resolve()` may suspend and can fail if signature resolution fails.

Paper-native types require a real Paper command tree and are not supported by `CommandTestHarness`.
