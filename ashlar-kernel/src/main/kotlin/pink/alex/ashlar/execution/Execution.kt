package pink.alex.ashlar.execution

import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.entity.Entity
import org.bukkit.plugin.Plugin

/**
 * Runs [block] while the server owns [entity].
 *
 * If the caller already owns the entity, this function runs [block] immediately without
 * suspending. Otherwise, it queues the block through the entity scheduler and suspends until the
 * block finishes. The block cannot suspend, so one ownership grant cannot span a coroutine
 * suspension. The caller resumes in its previous coroutine context.
 *
 * Cancelling the caller cancels queued work when it has not started. If the entity retires before
 * the work starts, this function returns [EntityOutcome.Retired]. Exceptions from [block] keep
 * their normal coroutine behavior.
 */
public suspend fun <T> Plugin.withEntity(
    entity: Entity,
    block: context(EntityContext) () -> T,
): EntityOutcome<T> {
    val adapter = platformAdapter
    if (adapter.owns(this, entity)) {
        return runEntityBlock(adapter, this, entity, block)
    }

    return awaitEntitySchedule(
        schedule = { run, retired -> adapter.schedule(this, entity, run, retired) },
        run = { runEntityBlock(adapter, this, entity, block) },
    )
}

/**
 * Runs [block] while the server owns the chunk containing [location].
 *
 * The function snapshots the location's world and chunk coordinates before checking ownership.
 * It runs [block] immediately when the caller already owns that chunk. Otherwise, it queues the
 * block through the region scheduler and suspends until the block finishes. The block cannot
 * suspend. Cancelling the caller cancels queued work when it has not started, and the caller
 * resumes in its previous coroutine context.
 *
 * @throws IllegalArgumentException if [location] does not belong to a world
 */
public suspend fun <T> Plugin.withRegion(
    location: Location,
    block: context(RegionContext) () -> T,
): T {
    val world = requireNotNull(location.world) { "The region location must belong to a world" }
    return withRegion(world, location.blockX shr 4, location.blockZ shr 4, block)
}

/**
 * Runs [block] while the server owns the chunk at [chunkX], [chunkZ] in [world].
 *
 * It runs [block] immediately when the caller already owns that chunk. Otherwise, it queues the
 * block through the region scheduler and suspends until the block finishes. The block cannot
 * suspend. Cancelling the caller cancels queued work when it has not started, and the caller
 * resumes in its previous coroutine context.
 */
public suspend fun <T> Plugin.withRegion(
    world: World,
    chunkX: Int,
    chunkZ: Int,
    block: context(RegionContext) () -> T,
): T {
    val adapter = platformAdapter
    if (adapter.owns(this, world, chunkX, chunkZ)) {
        return runRegionBlock(adapter, this, world, chunkX, chunkZ, block)
    }

    return awaitSchedule(
        schedule = { run -> adapter.schedule(this, world, chunkX, chunkZ, run) },
        run = { runRegionBlock(adapter, this, world, chunkX, chunkZ, block) },
    )
}

/**
 * Runs [block] on the server's global region.
 *
 * It runs [block] immediately when the caller is already on the global tick thread. Otherwise,
 * it queues the block through the global region scheduler and suspends until the block finishes.
 * The block cannot suspend. Cancelling the caller cancels queued work when it has not started,
 * and the caller resumes in its previous coroutine context.
 */
public suspend fun <T> Plugin.withGlobal(
    block: context(GlobalContext) () -> T,
): T {
    val adapter = platformAdapter
    if (adapter.ownsGlobal(this)) {
        return runGlobalBlock(adapter, this, block)
    }

    return awaitSchedule(
        schedule = { run -> adapter.scheduleGlobal(this, run) },
        run = { runGlobalBlock(adapter, this, block) },
    )
}

private fun <T> runEntityBlock(
    adapter: PlatformAdapter,
    plugin: Plugin,
    entity: Entity,
    block: context(EntityContext) () -> T,
): EntityOutcome<T> {
    val capability = EntityContext(entity) {
        if (!adapter.owns(plugin, entity)) {
            throw OwnershipViolationException("The current execution domain no longer owns the entity")
        }
    }
    capability.checkOwnership()
    return context(capability) { EntityOutcome.Completed(block()) }
}

private fun <T> runRegionBlock(
    adapter: PlatformAdapter,
    plugin: Plugin,
    world: World,
    chunkX: Int,
    chunkZ: Int,
    block: context(RegionContext) () -> T,
): T {
    val capability = RegionContext(world, chunkX, chunkZ) {
        if (!adapter.owns(plugin, world, chunkX, chunkZ)) {
            throw OwnershipViolationException(
                "The current execution domain no longer owns chunk ($chunkX, $chunkZ)",
            )
        }
    }
    capability.checkOwnership()
    return context(capability) { block() }
}

private fun <T> runGlobalBlock(
    adapter: PlatformAdapter,
    plugin: Plugin,
    block: context(GlobalContext) () -> T,
): T {
    val capability = GlobalContext {
        if (!adapter.ownsGlobal(plugin)) {
            throw OwnershipViolationException("The current execution domain is not the global region")
        }
    }
    capability.checkOwnership()
    return context(capability) { block() }
}

internal suspend fun <T> awaitEntitySchedule(
    schedule: (run: () -> Unit, retired: () -> Unit) -> ScheduledCancellation?,
    run: () -> EntityOutcome<T>,
): EntityOutcome<T> = suspendCancellableCoroutine { continuation ->
    val callbackStarted = AtomicBoolean(false)
    val handle = AtomicReference<ScheduledCancellation?>()
    continuation.invokeOnCancellation { handle.get()?.cancel() }

    val scheduled = try {
        schedule(
            {
                if (callbackStarted.compareAndSet(false, true) && continuation.isActive) {
                    continuation.completeFrom(run)
                }
            },
            {
                if (callbackStarted.compareAndSet(false, true)) {
                    continuation.complete(EntityOutcome.Retired)
                }
            },
        )
    } catch (error: Throwable) {
        if (callbackStarted.compareAndSet(false, true)) {
            continuation.completeExceptionally(error)
        }
        return@suspendCancellableCoroutine
    }

    if (scheduled == null) {
        if (callbackStarted.compareAndSet(false, true)) {
            continuation.complete(EntityOutcome.Retired)
        }
        return@suspendCancellableCoroutine
    }

    handle.set(scheduled)
    if (continuation.isCancelled) scheduled.cancel()
}

internal suspend fun <T> awaitSchedule(
    schedule: (run: () -> Unit) -> ScheduledCancellation,
    run: () -> T,
): T = suspendCancellableCoroutine { continuation ->
    val callbackStarted = AtomicBoolean(false)
    val handle = AtomicReference<ScheduledCancellation?>()
    continuation.invokeOnCancellation { handle.get()?.cancel() }

    val scheduled = try {
        schedule {
            if (callbackStarted.compareAndSet(false, true) && continuation.isActive) {
                continuation.completeFrom(run)
            }
        }
    } catch (error: Throwable) {
        if (callbackStarted.compareAndSet(false, true)) {
            continuation.completeExceptionally(error)
        }
        return@suspendCancellableCoroutine
    }

    handle.set(scheduled)
    if (continuation.isCancelled) scheduled.cancel()
}

private inline fun <T> CancellableContinuation<T>.completeFrom(run: () -> T) {
    try {
        complete(run())
    } catch (error: Throwable) {
        completeExceptionally(error)
    }
}

private fun <T> CancellableContinuation<T>.complete(value: T) {
    resumeWith(Result.success(value))
}

private fun CancellableContinuation<*>.completeExceptionally(error: Throwable) {
    resumeWith(Result.failure(error))
}

internal fun interface ScheduledCancellation {
    fun cancel()
}

private interface PlatformAdapter {
    fun owns(plugin: Plugin, entity: Entity): Boolean

    fun owns(plugin: Plugin, world: World, chunkX: Int, chunkZ: Int): Boolean

    fun ownsGlobal(plugin: Plugin): Boolean

    fun schedule(
        plugin: Plugin,
        entity: Entity,
        run: () -> Unit,
        retired: () -> Unit,
    ): ScheduledCancellation?

    fun schedule(
        plugin: Plugin,
        world: World,
        chunkX: Int,
        chunkZ: Int,
        run: () -> Unit,
    ): ScheduledCancellation

    fun scheduleGlobal(plugin: Plugin, run: () -> Unit): ScheduledCancellation
}

private object PaperPlatformAdapter : PlatformAdapter {
    override fun owns(plugin: Plugin, entity: Entity): Boolean =
        plugin.server.isOwnedByCurrentRegion(entity)

    override fun owns(plugin: Plugin, world: World, chunkX: Int, chunkZ: Int): Boolean =
        plugin.server.isOwnedByCurrentRegion(world, chunkX, chunkZ)

    override fun ownsGlobal(plugin: Plugin): Boolean = plugin.server.isGlobalTickThread

    override fun schedule(
        plugin: Plugin,
        entity: Entity,
        run: () -> Unit,
        retired: () -> Unit,
    ): ScheduledCancellation? =
        entity.scheduler.run(plugin, { _ -> run() }, retired)?.asCancellationHandle()

    override fun schedule(
        plugin: Plugin,
        world: World,
        chunkX: Int,
        chunkZ: Int,
        run: () -> Unit,
    ): ScheduledCancellation =
        plugin.server.regionScheduler.run(plugin, world, chunkX, chunkZ) { _ -> run() }
            .asCancellationHandle()

    override fun scheduleGlobal(plugin: Plugin, run: () -> Unit): ScheduledCancellation =
        plugin.server.globalRegionScheduler.run(plugin) { _ -> run() }.asCancellationHandle()

    private fun ScheduledTask.asCancellationHandle(): ScheduledCancellation =
        ScheduledCancellation { cancel() }
}

@Volatile
private var platformAdapter: PlatformAdapter = PaperPlatformAdapter
