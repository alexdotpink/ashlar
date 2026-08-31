package dev.placeholder.framework.commands.route

import dev.placeholder.framework.commands.minecraft.AxisSelection
import dev.placeholder.framework.commands.minecraft.BlockColumnRef
import dev.placeholder.framework.commands.minecraft.FineColumnSnapshot
import dev.placeholder.framework.commands.minecraft.FinePositionSnapshot
import dev.placeholder.framework.commands.minecraft.MinecraftAngle
import dev.placeholder.framework.commands.minecraft.MinecraftDoubleRange
import dev.placeholder.framework.commands.minecraft.MinecraftIntegerRange
import dev.placeholder.framework.commands.minecraft.MinecraftRotation
import dev.placeholder.framework.commands.minecraft.MinecraftTime
import dev.placeholder.framework.commands.minecraft.RegistryValueKey
import dev.placeholder.framework.commands.minecraft.RegistryValueRef
import dev.placeholder.framework.commands.reference.BlockRef
import dev.placeholder.framework.commands.reference.EntityRef
import dev.placeholder.framework.commands.reference.PlayerRef
import dev.placeholder.framework.commands.reference.WorldRef
import java.util.UUID
import net.kyori.adventure.key.Key
import net.kyori.adventure.text.format.TextColor
import org.bukkit.GameMode
import org.bukkit.HeightMap
import org.bukkit.NamespacedKey

internal fun semanticArguments(value: Any): List<CommandRouteSegment> = when (value) {
    is PlayerRef -> one(value.uniqueId.toString())
    is EntityRef -> one(value.uniqueId.toString())
    is BlockRef -> listOf(value.x, value.y, value.z).map { coordinate -> routeArgument(coordinate.toString()) }
    is BlockColumnRef -> listOf(value.x, value.z).map { coordinate -> routeArgument(coordinate.toString()) }
    is FinePositionSnapshot -> listOf(value.x, value.y, value.z).map { coordinate -> routeArgument(coordinate.toString()) }
    is FineColumnSnapshot -> listOf(value.x, value.z).map { coordinate -> routeArgument(coordinate.toString()) }
    is MinecraftRotation -> listOf(value.yaw, value.pitch).map { angle -> routeArgument(angle.toString()) }
    is MinecraftAngle -> one(value.degrees.toString())
    is MinecraftTime -> one(value.ticks.toString())
    is AxisSelection -> one(value.toString())
    is MinecraftIntegerRange -> one(range(value.minimum, value.maximum))
    is MinecraftDoubleRange -> one(range(value.minimum, value.maximum))
    is WorldRef -> one(value.key.asString())
    is RegistryValueRef<*> -> one(value.value.asString())
    is RegistryValueKey<*> -> one(value.value.asString())
    is NamespacedKey -> one(value.asString())
    is Key -> one(value.asString())
    is UUID -> one(value.toString())
    is GameMode -> one(value.name.lowercase())
    is HeightMap -> one(value.name.lowercase())
    is TextColor -> one(value.asHexString())
    is Enum<*> -> one(value.name.lowercase())
    else -> error("${value::class.qualifiedName} cannot be encoded into a typed command route")
}

private fun one(value: String): List<CommandRouteSegment> = listOf(routeArgument(value))

private fun range(minimum: Any?, maximum: Any?): String =
    "${minimum ?: ""}..${maximum ?: ""}"
