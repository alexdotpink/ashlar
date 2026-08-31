package dev.placeholder.framework.commands.minecraft

import dev.placeholder.framework.commands.reference.BlockRef
import org.bukkit.block.data.BlockData
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin
import java.util.concurrent.CompletableFuture
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import net.kyori.adventure.chat.SignedMessage

/** Result of evaluating a native block predicate without synchronously loading a chunk. */
public enum class BlockPredicateResult {
    MATCH,
    NO_MATCH,
    UNLOADED_CHUNK,
}

/** A native Minecraft block predicate whose server access remains explicit and region-owned. */
public fun interface BlockPredicate {
    public suspend fun test(
        plugin: Plugin,
        block: BlockRef,
        loadChunk: Boolean,
    ): BlockPredicateResult
}

/** Tests without synchronously loading an absent chunk. */
public suspend fun BlockPredicate.test(
    plugin: Plugin,
    block: BlockRef,
): BlockPredicateResult = test(plugin, block, loadChunk = false)

/** A parsed block state, copied behind a framework-owned asynchronous placement interface. */
public interface BlockStateInput {
    /** Returns a detached copy of the parsed block data. */
    public fun blockData(): BlockData

    /** Places the parsed state while owning the target block's region. */
    public suspend fun place(
        plugin: Plugin,
        block: BlockRef,
        force: Boolean = false,
        applyPhysics: Boolean = true,
    ): Boolean
}

/** A detached copy of an item stack parsed with Minecraft's item syntax. */
public class ItemStackSnapshot(itemStack: ItemStack) {
    private val snapshot: ItemStack = itemStack.clone()

    public fun copy(): ItemStack = snapshot.clone()

    override fun equals(other: Any?): Boolean =
        other is ItemStackSnapshot && snapshot.amount == other.snapshot.amount && snapshot.isSimilar(other.snapshot)

    override fun hashCode(): Int = snapshot.hashCode()

    override fun toString(): String = snapshot.toString()
}

/** A native Minecraft item predicate detached from Brigadier's provider type. */
public fun interface ItemPredicate {
    public fun matches(itemStack: ItemStack): Boolean
}

/** Signed chat input whose asynchronous signature resolution is explicit. */
public class SignedMessageInput internal constructor(
    public val content: String,
    private val resolved: CompletableFuture<SignedMessage>,
) {
    public suspend fun resolve(): SignedMessage = suspendCancellableCoroutine { continuation ->
        resolved.whenComplete { value, failure ->
            if (failure == null) continuation.resume(value)
            else continuation.resumeWithException(failure)
        }
        continuation.invokeOnCancellation { resolved.cancel(false) }
    }
}
