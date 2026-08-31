package dev.placeholder.framework.commands.minecraft

import dev.placeholder.framework.commands.reference.BlockRef
import dev.placeholder.framework.commands.reference.WorldRef
import java.util.UUID
import net.kyori.adventure.key.Key
import org.bukkit.Axis
import org.bukkit.scoreboard.RenderType

/** An angle in degrees, resolved relative to the command executor when necessary. */
@JvmInline
public value class MinecraftAngle(public val degrees: Float) {
    init {
        require(degrees.isFinite()) { "Minecraft angles must be finite" }
    }
}

/** A Minecraft duration expressed in server ticks. */
@JvmInline
public value class MinecraftTime(public val ticks: Int) {
    init {
        require(ticks >= 0) { "Minecraft time cannot be negative" }
    }
}

/** An immutable rotation resolved relative to the command executor. */
public data class MinecraftRotation(
    public val yaw: Float,
    public val pitch: Float,
) {
    init {
        require(yaw.isFinite() && pitch.isFinite()) { "Minecraft rotations must be finite" }
    }
}

/** One or more axes selected by Minecraft's compact `xyz` syntax. */
public class AxisSelection(axes: Iterable<Axis>) {
    public val axes: Set<Axis> = axes.toSet()

    init {
        require(this.axes.isNotEmpty()) { "An axis selection cannot be empty" }
    }

    override fun equals(other: Any?): Boolean = other is AxisSelection && axes == other.axes

    override fun hashCode(): Int = axes.hashCode()

    override fun toString(): String = axes.joinToString(separator = "") { it.name.lowercase() }
}

/** A block column resolved in a stable world, without loading or retaining its chunks. */
public data class BlockColumnRef(
    public val world: WorldRef,
    public val x: Int,
    public val z: Int,
) {
    public fun at(y: Int): BlockRef = BlockRef(world, x, y, z)
}

/** An immutable fine position resolved in a stable world. */
public data class FinePositionSnapshot(
    public val world: WorldRef,
    public val x: Double,
    public val y: Double,
    public val z: Double,
) {
    init {
        require(x.isFinite() && y.isFinite() && z.isFinite()) { "Positions must be finite" }
    }
}

/** A two-dimensional fine position resolved in a stable world. */
public data class FineColumnSnapshot(
    public val world: WorldRef,
    public val x: Double,
    public val z: Double,
) {
    init {
        require(x.isFinite() && z.isFinite()) { "Positions must be finite" }
    }
}

/** One immutable property copied from a player profile selector result. */
public data class ProfilePropertySnapshot(
    public val name: String,
    public val value: String,
    public val signature: String? = null,
)

/** Player identity and properties copied before asynchronous command execution. */
public class PlayerProfileSnapshot(
    public val uniqueId: UUID?,
    public val name: String?,
    properties: Iterable<ProfilePropertySnapshot> = emptyList(),
) {
    public val properties: List<ProfilePropertySnapshot> = properties.toList()

    init {
        require(uniqueId != null || name != null) { "A player profile needs a UUID or name" }
    }

    override fun equals(other: Any?): Boolean =
        other is PlayerProfileSnapshot &&
            uniqueId == other.uniqueId &&
            name == other.name &&
            properties == other.properties

    override fun hashCode(): Int {
        var result = uniqueId?.hashCode() ?: 0
        result = 31 * result + (name?.hashCode() ?: 0)
        return 31 * result + properties.hashCode()
    }

    override fun toString(): String = "PlayerProfileSnapshot(uniqueId=$uniqueId, name=$name)"
}

/** Native profile-selector output copied into stable values. */
public class PlayerProfileSelection(profiles: Iterable<PlayerProfileSnapshot>) {
    public val profiles: List<PlayerProfileSnapshot> = profiles.toList()

    override fun equals(other: Any?): Boolean =
        other is PlayerProfileSelection && profiles == other.profiles

    override fun hashCode(): Int = profiles.hashCode()

    override fun toString(): String = profiles.toString()
}

/** An inclusive integer range; either end may be omitted. */
public data class MinecraftIntegerRange(
    public val minimum: Int? = null,
    public val maximum: Int? = null,
) {
    init {
        require(minimum == null || maximum == null || minimum <= maximum) {
            "Range minimum cannot exceed its maximum"
        }
    }

    public operator fun contains(value: Int): Boolean =
        (minimum == null || value >= minimum) && (maximum == null || value <= maximum)
}

/** An inclusive floating-point range; either end may be omitted. */
public data class MinecraftDoubleRange(
    public val minimum: Double? = null,
    public val maximum: Double? = null,
) {
    init {
        require(minimum?.isNaN() != true && maximum?.isNaN() != true) { "Range bounds cannot be NaN" }
        require(minimum == null || maximum == null || minimum <= maximum) {
            "Range minimum cannot exceed its maximum"
        }
    }

    public operator fun contains(value: Double): Boolean =
        !value.isNaN() &&
            (minimum == null || value >= minimum) &&
            (maximum == null || value <= maximum)
}

/** A scoreboard criterion copied out of the server-owned scoreboard API. */
public data class ScoreboardCriterion(
    public val name: String,
    public val readOnly: Boolean,
    public val defaultRenderType: RenderType,
)

/** Stable identity for a value accepted by a particular Minecraft registry. */
public data class RegistryValueRef<T : Any>(
    public val registry: Key,
    public val value: Key,
)

/** A typed registry key accepted without resolving the corresponding registry value. */
public data class RegistryValueKey<T : Any>(
    public val registry: Key,
    public val value: Key,
)
