package dev.placeholder.framework.sample

import dev.placeholder.framework.commands.CommandFragment
import dev.placeholder.framework.commands.Group
import dev.placeholder.framework.commands.minecraft.AxisSelection
import dev.placeholder.framework.commands.minecraft.BlockColumnRef
import dev.placeholder.framework.commands.minecraft.BlockPredicate
import dev.placeholder.framework.commands.minecraft.BlockStateInput
import dev.placeholder.framework.commands.minecraft.CenterIntegers
import dev.placeholder.framework.commands.minecraft.FineColumnSnapshot
import dev.placeholder.framework.commands.minecraft.FinePositionSnapshot
import dev.placeholder.framework.commands.minecraft.FromRegistry
import dev.placeholder.framework.commands.minecraft.ItemPredicate
import dev.placeholder.framework.commands.minecraft.ItemStackSnapshot
import dev.placeholder.framework.commands.minecraft.MinecraftAngle
import dev.placeholder.framework.commands.minecraft.MinecraftDoubleRange
import dev.placeholder.framework.commands.minecraft.MinecraftIntegerRange
import dev.placeholder.framework.commands.minecraft.MinecraftRotation
import dev.placeholder.framework.commands.minecraft.MinecraftTime
import dev.placeholder.framework.commands.minecraft.MinimumTicks
import dev.placeholder.framework.commands.minecraft.PlayerProfileSelection
import dev.placeholder.framework.commands.minecraft.RegistryValueKey
import dev.placeholder.framework.commands.minecraft.RegistryValueRef
import dev.placeholder.framework.commands.minecraft.ScoreboardCriterion
import dev.placeholder.framework.commands.minecraft.SignedMessageInput
import dev.placeholder.framework.commands.minecraft.test
import dev.placeholder.framework.commands.reference.BlockRef
import dev.placeholder.framework.commands.reference.EntityRef
import dev.placeholder.framework.commands.reference.EntitySelection
import dev.placeholder.framework.commands.reference.PlayerRef
import dev.placeholder.framework.commands.reference.PlayerSelection
import dev.placeholder.framework.commands.reference.WorldRef
import io.papermc.paper.entity.LookAnchor
import java.util.UUID
import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.Style
import net.kyori.adventure.text.format.TextColor
import org.bukkit.GameMode
import org.bukkit.HeightMap
import org.bukkit.NamespacedKey
import org.bukkit.block.structure.Mirror
import org.bukkit.block.structure.StructureRotation
import org.bukkit.plugin.Plugin
import org.bukkit.scoreboard.DisplaySlot

/** Every type below uses Paper's native argument parser and leaves it as a stable framework value. */
@CommandFragment(ShowcaseCommands::class)
internal class ShowcaseMinecraftCommands(
    private val plugin: Plugin,
) {
    @Group(name = "minecraft", aliases = ["mc"])
    inner class Minecraft {
        /** Lists the native argument families available below this group. */
        fun minecraftOverview(): Component = Component.text(
            "Native groups: selectors, positions, values, items, text, registries. Use /sc help for routes.",
            NamedTextColor.AQUA,
        )

        @Group(name = "selectors")
        inner class Selectors {
            /** Resolves exactly one online player to a stable reference. */
            fun nativePlayer(player: PlayerRef): String = "PlayerRef(${player.uniqueId})"

            /** Resolves a native player selector to stable references. */
            fun nativePlayers(players: PlayerSelection): String = "${players.players.size} stable player reference(s)"

            /** Resolves exactly one entity to a stable reference. */
            fun nativeEntity(entity: EntityRef): String = "EntityRef(${entity.uniqueId})"

            /** Resolves a native entity selector to stable references. */
            fun nativeEntities(entities: EntitySelection): String = "${entities.entities.size} stable entity reference(s)"

            /** Copies native player-profile selector results. */
            fun nativeProfiles(profiles: PlayerProfileSelection): String =
                profiles.profiles.joinToString(prefix = "Profiles: ") { it.name ?: it.uniqueId.toString() }
        }

        @Group(name = "positions")
        inner class Positions {
            /** Resolves a block position without retaining a chunk. */
            fun nativeBlockPosition(block: BlockRef): String =
                "Block ${block.x}, ${block.y}, ${block.z} in ${block.world.key}"

            /** Resolves a two-dimensional block column. */
            fun nativeBlockColumn(column: BlockColumnRef): String =
                "Block column ${column.x}, ${column.z} in ${column.world.key}"

            /** Resolves a centered fine position snapshot. */
            fun nativeFinePosition(@CenterIntegers position: FinePositionSnapshot): String =
                "Fine position ${position.x}, ${position.y}, ${position.z}"

            /** Resolves a centered fine column snapshot. */
            fun nativeFineColumn(@CenterIntegers column: FineColumnSnapshot): String =
                "Fine column ${column.x}, ${column.z}"

            /** Resolves an absolute or relative rotation. */
            fun nativeRotation(rotation: MinecraftRotation): String =
                "Rotation yaw=${rotation.yaw}, pitch=${rotation.pitch}"

            /** Resolves an absolute or relative angle. */
            fun nativeAngle(angle: MinecraftAngle): String = "Angle ${angle.degrees}°"

            /** Parses Minecraft's compact axis-set syntax. */
            fun nativeAxes(axes: AxisSelection): String = "Axes ${axes.axes.joinToString()}"
        }

        @Group(name = "blocks-items")
        inner class BlocksAndItems {
            /** Parses and places a native block state using region-owned access. */
            suspend fun nativeBlockState(block: BlockRef, state: BlockStateInput): String =
                if (state.place(plugin, block)) "Placed ${state.blockData().asString} at $block"
                else "The target world was unavailable."

            /** Evaluates a native block predicate without forced chunk loading. */
            suspend fun nativeBlockPredicate(block: BlockRef, predicate: BlockPredicate): String =
                "Predicate result: ${predicate.test(plugin, block)}"

            /** Copies a natively parsed item stack. */
            fun nativeItem(item: ItemStackSnapshot): String =
                "Parsed item ${item.copy().type.key} x${item.copy().amount}"

            /** Tests a native item predicate against a player's held item. */
            suspend fun nativeItemPredicate(player: PlayerRef, predicate: ItemPredicate): String {
                var result = "Player retired before item inspection."
                player.access(plugin) { resolved ->
                    result = "Main-hand item matches: ${predicate.matches(resolved.inventory.itemInMainHand)}"
                }
                return result
            }
        }

        @Group(name = "text")
        inner class TextValues {
            /** Parses a named Minecraft color. */
            fun nativeNamedColor(color: NamedTextColor): Component = Component.text(color.toString(), color)

            /** Parses a hexadecimal text color. */
            fun nativeHexColor(color: TextColor): Component = Component.text(color.asHexString(), color)

            /** Parses a native Adventure component input. */
            fun nativeComponent(component: Component): Component = Component.text("Parsed: ").append(component)

            /** Parses a native Adventure style input. */
            fun nativeStyle(style: Style): String = "Style: $style"

            /** Captures signed-message content and its deferred signature resolution. */
            fun nativeSignedMessage(message: SignedMessageInput): String =
                "Signed-message input captured ${message.content.length} characters."
        }

        @Group(name = "values")
        inner class Values {
            /** Parses a scoreboard display slot. */
            fun nativeDisplaySlot(slot: DisplaySlot): String = "Display slot: $slot"

            /** Parses a Bukkit namespaced key. */
            fun nativeNamespacedKey(key: NamespacedKey): String = "NamespacedKey: $key"

            /** Parses an Adventure key. */
            fun nativeAdventureKey(key: Key): String = "Adventure Key: $key"

            /** Parses an open or bounded Minecraft integer range. */
            fun nativeIntegerRange(range: MinecraftIntegerRange): String = "Integer range: $range"

            /** Parses an open or bounded Minecraft floating-point range. */
            fun nativeDoubleRange(range: MinecraftDoubleRange): String = "Double range: $range"

            /** Resolves a loaded world to a stable key. */
            fun nativeWorld(world: WorldRef): String = "World: ${world.key}"

            /** Parses a native game mode. */
            fun nativeGameMode(mode: GameMode): String = "Game mode: $mode"

            /** Parses a native height-map value. */
            fun nativeHeightMap(heightMap: HeightMap): String = "Height map: $heightMap"

            /** Parses a native UUID. */
            fun nativeUuid(uuid: UUID): String = "UUID: $uuid"

            /** Copies a native scoreboard criterion. */
            fun nativeCriterion(criterion: ScoreboardCriterion): String = "Criterion: $criterion"

            /** Parses an entity look anchor. */
            fun nativeLookAnchor(anchor: LookAnchor): String = "Look anchor: $anchor"

            /** Parses Minecraft time while enforcing a twenty-tick minimum. */
            fun nativeTime(@MinimumTicks(20) time: MinecraftTime): String = "Time: ${time.ticks} ticks"

            /** Parses a structure mirror value. */
            fun nativeMirror(mirror: Mirror): String = "Mirror: $mirror"

            /** Parses a structure rotation value. */
            fun nativeStructureRotation(rotation: StructureRotation): String = "Structure rotation: $rotation"
        }

        @Group(name = "registries")
        inner class Registries {
            /** Resolves an item registry entry to its stable typed identity. */
            fun nativeRegistryValue(
                @FromRegistry("minecraft:item") value: RegistryValueRef<Any>,
            ): String = "Registry value: ${value.registry} -> ${value.value}"

            /** Parses an item registry key without resolving its value. */
            fun nativeRegistryKey(
                @FromRegistry("minecraft:item") value: RegistryValueKey<Any>,
            ): String = "Registry key: ${value.registry} -> ${value.value}"
        }
    }
}
