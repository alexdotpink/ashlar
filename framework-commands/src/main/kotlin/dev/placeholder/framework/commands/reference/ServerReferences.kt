package dev.placeholder.framework.commands.reference

import dev.placeholder.framework.execution.EntityContext
import dev.placeholder.framework.execution.EntityOutcome
import dev.placeholder.framework.execution.RegionContext
import dev.placeholder.framework.execution.withEntity
import dev.placeholder.framework.execution.withGlobal
import dev.placeholder.framework.execution.withRegion
import java.util.UUID
import net.kyori.adventure.key.Key
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin

/** Stable player identity safe to retain in an asynchronous command. */
@JvmInline
public value class PlayerRef(public val uniqueId: UUID) {
    public suspend fun <T> access(
        plugin: Plugin,
        block: context(EntityContext) (Player) -> T,
    ): EntityOutcome<T> {
        val player = plugin.withGlobal { plugin.server.getPlayer(uniqueId) }
            ?: return EntityOutcome.Retired
        return plugin.withEntity(player) { block(player) }
    }
}

/** Stable entity identity safe to retain in an asynchronous command. */
@JvmInline
public value class EntityRef(public val uniqueId: UUID) {
    public suspend fun <T> access(
        plugin: Plugin,
        block: context(EntityContext) (Entity) -> T,
    ): EntityOutcome<T> {
        val entity = plugin.withGlobal { plugin.server.getEntity(uniqueId) }
            ?: return EntityOutcome.Retired
        return plugin.withEntity(entity) { block(entity) }
    }
}

/** Immutable location snapshot; it never grants access to the represented chunk. */
public data class LocationSnapshot(
    public val world: Key,
    public val x: Double,
    public val y: Double,
    public val z: Double,
    public val yaw: Float = 0f,
    public val pitch: Float = 0f,
)

/** Stable world identity resolved only while the global region is owned. */
@JvmInline
public value class WorldRef(public val key: Key) {
    public suspend fun resolve(plugin: Plugin): World? =
        plugin.withGlobal { plugin.server.getWorld(org.bukkit.NamespacedKey.fromString(key.asString())!!) }
}

/** Stable block position with explicit region-owned access. */
public data class BlockRef(
    public val world: WorldRef,
    public val x: Int,
    public val y: Int,
    public val z: Int,
) {
    public suspend fun <T> access(
        plugin: Plugin,
        block: context(RegionContext) (org.bukkit.block.Block) -> T,
    ): T? {
        val resolvedWorld = world.resolve(plugin) ?: return null
        return plugin.withRegion(resolvedWorld, x shr 4, z shr 4) {
            block(resolvedWorld.getBlockAt(x, y, z))
        }
    }
}

/** Native one-token player selector result represented only by stable references. */
public data class PlayerSelection(public val players: List<PlayerRef>)

/** Native one-token entity selector result represented only by stable references. */
public data class EntitySelection(public val entities: List<EntityRef>)

/** Copies a Bukkit location before it leaves server-owned execution. */
public fun Location.snapshot(): LocationSnapshot = LocationSnapshot(
    world = requireNotNull(world).key,
    x = x,
    y = y,
    z = z,
    yaw = yaw,
    pitch = pitch,
)
