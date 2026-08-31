package dev.placeholder.framework.events

import dev.placeholder.framework.ComponentContext
import dev.placeholder.framework.di.DependencyGraph
import dev.placeholder.framework.di.Inject
import dev.placeholder.framework.di.PluginScoped
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.reflect.KClass
import kotlin.time.Duration
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import org.bukkit.event.Cancellable
import org.bukkit.event.Event
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.plugin.EventExecutor
import org.bukkit.plugin.Plugin

/** One closeable dynamic server event registration. */
public fun interface EventRegistration : AutoCloseable {
    override fun close()
}

/** Plug-in-scoped entrypoint for dynamic and temporal server event operations. */
@Inject
@PluginScoped
public class ServerEvents(
    private val plugin: Plugin,
    graph: DependencyGraph,
) {
    @PublishedApi
    internal val reporter: ServerEventFailureReporter = graph.serverEventFailureReporter(plugin)

    @PublishedApi
    internal fun <E : Event> register(
        type: KClass<E>,
        priority: EventPriority,
        ignoreCancelled: Boolean,
        name: String,
        handler: E.() -> Unit,
    ): EventRegistration {
        val listener = object : Listener {}
        val closed = AtomicBoolean()
        plugin.server.pluginManager.registerEvent(
            type.java,
            listener,
            priority,
            EventExecutor { _, event ->
                runCatching { type.java.cast(event).handler() }
                    .onFailure { cause ->
                        reporter.report(
                            ServerEventFailure(
                                eventSet = ServerEvents::class,
                                handler = name,
                                eventType = type,
                                cause = cause,
                            ),
                        )
                    }
            },
            plugin,
            ignoreCancelled,
        )
        return EventRegistration {
            if (closed.compareAndSet(false, true)) HandlerList.unregisterAll(listener)
        }
    }
}

/** Registers one synchronous dynamic listener owned by the current component. */
context(owner: ComponentContext)
public inline fun <reified E : Event> ServerEvents.listen(
    priority: EventPriority = EventPriority.NORMAL,
    ignoreCancelled: Boolean = false,
    noinline handler: E.() -> Unit,
): EventRegistration = owner.own(
    register(E::class, priority, ignoreCancelled, "dynamic ${E::class.qualifiedName}", handler),
)

/** Waits for the first server event selector that returns a value. */
public suspend inline fun <reified E : Event, R> ServerEvents.await(
    within: Duration? = null,
    priority: EventPriority = EventPriority.MONITOR,
    ignoreCancelled: Boolean = false,
    noinline select: E.() -> R,
): R = withOptionalTimeout(within) {
    await(E::class, priority, ignoreCancelled, select)
}

/** Captures matching cancellable events until one selector returns a value. */
public suspend inline fun <reified E, R> ServerEvents.capture(
    within: Duration? = null,
    priority: EventPriority = EventPriority.HIGHEST,
    ignoreCancelled: Boolean = true,
    noinline select: E.() -> R,
): R where E : Event, E : Cancellable = withOptionalTimeout(within) {
    capture(E::class, priority, ignoreCancelled, select)
}

/** Creates a bounded Flow of values synchronously selected from server events. */
public inline fun <reified E : Event, R> ServerEvents.stream(
    capacity: Int,
    overflow: BufferOverflow,
    priority: EventPriority = EventPriority.MONITOR,
    ignoreCancelled: Boolean = false,
    noinline select: E.() -> R,
): Flow<R> {
    require(capacity > 0) { "A server event stream needs a positive capacity" }
    require(overflow != BufferOverflow.SUSPEND) { "Server event streams cannot suspend event dispatch" }
    return stream(E::class, priority, ignoreCancelled, select).buffer(capacity, overflow)
}

/** Ignores the current event without completing a temporal operation. */
public fun skip(): Nothing = throw SkipEvent

/** Keeps a capture active and schedules feedback outside the live event callback. */
public fun retry(action: suspend () -> Unit): Nothing = throw RetryEvent(action)

@PublishedApi
internal suspend fun <E : Event, R> ServerEvents.await(
    type: KClass<E>,
    priority: EventPriority,
    ignoreCancelled: Boolean,
    select: E.() -> R,
): R = suspendCancellableCoroutine { continuation ->
    val completed = AtomicBoolean()
    val registration = AtomicReference<EventRegistration?>()
    val created = register(type, priority, ignoreCancelled, "await ${type.qualifiedName}") {
        val selected = try {
            Result.success(select())
        } catch (_: SkipEvent) {
            return@register
        } catch (failure: Throwable) {
            Result.failure(failure)
        }
        if (!completed.compareAndSet(false, true)) return@register
        registration.get()?.close()
        selected.fold(continuation::resume, continuation::resumeWithException)
    }
    registration.set(created)
    if (completed.get()) created.close()
    continuation.invokeOnCancellation {
        if (completed.compareAndSet(false, true)) created.close()
    }
}

@PublishedApi
internal suspend fun <E, R> ServerEvents.capture(
    type: KClass<E>,
    priority: EventPriority,
    ignoreCancelled: Boolean,
    select: E.() -> R,
): R where E : Event, E : Cancellable = coroutineScope {
    val result = CompletableDeferred<R>()
    val retryActions = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    val lock = Any()
    var completed = false
    val retryWorker = launch {
        for (action in retryActions) action()
    }
    val registration = register(type, priority, ignoreCancelled, "capture ${type.qualifiedName}") {
        val selected: Result<R>? = try {
            Result.success(select())
        } catch (_: SkipEvent) {
            null
        } catch (retry: RetryEvent) {
            synchronized(lock) {
                if (!completed) {
                    isCancelled = true
                    retryActions.trySend(retry.action)
                }
            }
            null
        } catch (failure: Throwable) {
            Result.failure(failure)
        }
        if (selected == null) return@register
        synchronized(lock) {
            if (completed) return@synchronized
            completed = true
            selected.onSuccess { value ->
                isCancelled = true
                result.complete(value)
            }.onFailure(result::completeExceptionally)
        }
    }
    try {
        result.await().also {
            retryActions.close()
            listOf(retryWorker).joinAll()
        }
    } finally {
        synchronized(lock) { completed = true }
        registration.close()
        retryActions.cancel()
        retryWorker.cancel()
    }
}

@PublishedApi
internal fun <E : Event, R> ServerEvents.stream(
    type: KClass<E>,
    priority: EventPriority,
    ignoreCancelled: Boolean,
    select: E.() -> R,
): Flow<R> = callbackFlow {
    val registration = register(type, priority, ignoreCancelled, "stream ${type.qualifiedName}") {
        try {
            trySend(select())
        } catch (_: SkipEvent) {
        } catch (failure: Throwable) {
            close(failure)
        }
    }
    awaitClose(registration::close)
}

@PublishedApi
internal suspend fun <T> withOptionalTimeout(
    within: Duration?,
    block: suspend () -> T,
): T = if (within == null) block() else withTimeout(within) { block() }

private data object SkipEvent : RuntimeException(null, null, false, false)

private class RetryEvent(
    val action: suspend () -> Unit,
) : RuntimeException(null, null, false, false)

internal fun DependencyGraph.serverEventFailureReporter(plugin: Plugin): ServerEventFailureReporter {
    bindDefault(
        ServerEventFailureReporter::class,
        ServerEventFailureReporter { failure ->
            plugin.componentLogger.error(
                "[${failure.eventSet.qualifiedName}] Event handler '${failure.handler}' failed for " +
                    failure.eventType.qualifiedName,
                failure.cause,
            )
        },
    )
    return get(ServerEventFailureReporter::class)
}
