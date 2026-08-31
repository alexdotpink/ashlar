package dev.placeholder.framework.execution

import org.bukkit.World
import org.bukkit.entity.Entity

/**
 * A temporary capability proving that the current server execution domain owns [entity].
 *
 * The capability may be captured, but its proof is not permanent. Framework modules must give
 * their context parameter a name and call [checkOwnership] before every Paper operation:
 *
 * ```kotlin
 * context(entityContext: EntityContext)
 * fun markGlowing() {
 *     entityContext.checkOwnership()
 *     entityContext.entity.isGlowing = true
 * }
 * ```
 *
 * An anonymous context parameter only restricts where a function can be called. It does not
 * revalidate a captured capability.
 */
public class EntityContext internal constructor(
    entity: Entity,
    private val ownership: OwnershipValidator,
) {
    private val target: Entity = entity

    public val entity: Entity
        get() {
            checkOwnership()
            return target
        }

    /**
     * Checks that the current execution domain still owns [entity].
     *
     * Framework operations must call this immediately before touching Paper state.
     *
     * @throws OwnershipViolationException if the current execution domain does not own [entity]
     */
    public fun checkOwnership() {
        ownership.check()
    }
}

/**
 * A temporary capability proving ownership of the chunk at [chunkX], [chunkZ] in [world].
 *
 * Framework modules must name their [RegionContext] context parameter and call [checkOwnership]
 * immediately before each Paper operation. An anonymous context parameter does not revalidate a
 * capability that escaped its original ownership block.
 */
public class RegionContext internal constructor(
    world: World,
    chunkX: Int,
    chunkZ: Int,
    private val ownership: OwnershipValidator,
) {
    private val target: World = world
    private val targetChunkX: Int = chunkX
    private val targetChunkZ: Int = chunkZ

    public val world: World
        get() {
            checkOwnership()
            return target
        }

    public val chunkX: Int
        get() {
            checkOwnership()
            return targetChunkX
        }

    public val chunkZ: Int
        get() {
            checkOwnership()
            return targetChunkZ
        }

    /**
     * Checks that the current execution domain still owns this chunk.
     *
     * Framework operations must call this immediately before touching Paper state.
     *
     * @throws OwnershipViolationException if the current execution domain does not own this chunk
     */
    public fun checkOwnership() {
        ownership.check()
    }
}

/**
 * A temporary capability proving that the current execution domain is the global region.
 *
 * Framework modules must name their [GlobalContext] context parameter and call [checkOwnership]
 * immediately before each Paper operation. An anonymous context parameter does not revalidate a
 * capability that escaped its original ownership block.
 */
public class GlobalContext internal constructor(
    private val ownership: OwnershipValidator,
) {
    /**
     * Checks that the current execution domain is still the global region.
     *
     * Framework operations must call this immediately before touching Paper state.
     *
     * @throws OwnershipViolationException if the current execution domain is not the global region
     */
    public fun checkOwnership() {
        ownership.check()
    }
}

/**
 * Reports that an execution capability was used outside the ownership domain that issued it.
 *
 * A captured capability can throw this exception even if its original ownership block completed
 * successfully.
 */
public class OwnershipViolationException internal constructor(message: String) :
    IllegalStateException(message)

internal fun interface OwnershipValidator {
    fun check()
}
