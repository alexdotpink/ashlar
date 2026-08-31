package dev.placeholder.framework.fixture

import dev.placeholder.framework.ComponentContext
import dev.placeholder.framework.FrameworkComponent
import dev.placeholder.framework.FrameworkPlugin
import dev.placeholder.framework.PluginComponent
import dev.placeholder.framework.commands.Commands
import dev.placeholder.framework.commands.CommandFragment
import dev.placeholder.framework.commands.ConfigureCommandGraph
import dev.placeholder.framework.commands.Group
import dev.placeholder.framework.commands.GreedyText
import dev.placeholder.framework.commands.Option
import dev.placeholder.framework.commands.Options
import dev.placeholder.framework.commands.Scope
import dev.placeholder.framework.commands.reference.BlockRef
import dev.placeholder.framework.commands.policy.CommandPolicy
import dev.placeholder.framework.commands.policy.CommandPolicyContext
import dev.placeholder.framework.commands.policy.CommandPolicyInterceptor
import dev.placeholder.framework.commands.policy.CommandPolicyPhase
import dev.placeholder.framework.commands.graph.CommandGraph
import dev.placeholder.framework.di.Binds
import dev.placeholder.framework.di.Inject
import dev.placeholder.framework.di.PluginScoped
import dev.placeholder.framework.execution.EntityContext
import dev.placeholder.framework.execution.EntityOutcome
import dev.placeholder.framework.execution.PlayerRef
import dev.placeholder.framework.execution.RegionContext
import dev.placeholder.framework.execution.withEntity
import dev.placeholder.framework.execution.withGlobal
import dev.placeholder.framework.execution.withRegion
import dev.placeholder.framework.events.ApplicationEvent
import dev.placeholder.framework.events.ApplicationEvents
import dev.placeholder.framework.events.ConfigureLifecycleEvents
import dev.placeholder.framework.events.Events
import dev.placeholder.framework.events.LifecycleEventRegistry
import dev.placeholder.framework.events.Observe
import dev.placeholder.framework.events.On
import dev.placeholder.framework.events.OnApplication
import dev.placeholder.framework.events.ServerEvents
import dev.placeholder.framework.events.capture
import dev.placeholder.framework.events.await
import dev.placeholder.framework.events.publish
import dev.placeholder.framework.events.stream
import dev.placeholder.framework.input.PlayerInput
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.yield
import kotlinx.coroutines.withTimeout
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents
import org.bukkit.Location
import org.bukkit.entity.ArmorStand
import org.bukkit.event.Cancellable
import org.bukkit.event.Event
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

/** Executed by the Gradle Paper and Folia integration-test tasks. */
public class IntegrationFixturePlugin : FrameworkPlugin() {
    private val probeResults by inject<ProbeResults>()
    private val automaticProbe by inject<AutomaticProbe>()
    private val serverEvents by inject<ServerEvents>()
    private val applicationEvents by inject<ApplicationEvents>()
    private val playerInput by inject<PlayerInput>()
    private val lifecycleProbe by component { ParentProbe(probeResults) }
    override fun ComponentContext.enable(): Unit {
        check(automaticProbe.started) { "The automatic DI component was not started before the plug-in" }
        probeResults.record("automatic:injected")
        check(!playerInput.cancel(PlayerRef(UUID(0L, 0L))))
        probeResults.record("input:available")
        probeResults.record("plugin:enable")
        own(AutoCloseable { probeResults.writeReceipt() })

        task("fixture") {
            try {
                lifecycleProbe.awaitOrdinaryTask()
                exerciseExecutionContexts()
                exerciseEvents()
                exerciseCommands()
            } catch (failure: Throwable) {
                probeResults.fail(failure)
            } finally {
                requestServerShutdown()
            }
        }
    }

    private suspend fun exerciseCommands(): Unit {
        withGlobal {
            check(server.dispatchCommand(server.consoleSender, "frameworkfixture greet"))
            check(server.dispatchCommand(server.consoleSender, "frameworkfixture greet Paper"))
            check(server.dispatchCommand(server.consoleSender, "frameworkfixture admin echo Folia"))
            check(server.dispatchCommand(server.consoleSender, "frameworkfixture scoped Kotlin"))
            check(server.dispatchCommand(server.consoleSender, "frameworkfixture say market square"))
            check(
                server.dispatchCommand(
                    server.consoleSender,
                    "frameworkfixture search market square --limit 2 --verbose",
                ),
            )
            check(server.dispatchCommand(server.consoleSender, "frameworkfixture block 1 64 2"))
            check(server.dispatchCommand(server.consoleSender, "frameworkfixture extra fragment"))
            check(server.dispatchCommand(server.consoleSender, "frameworkfixture redirected"))
        }
        withTimeout(5.seconds) {
            while (
                !probeResults.hasEvent("command:scoped:Kotlin") ||
                !probeResults.hasEvent("command:say:market square") ||
                !probeResults.hasEvent("command:search:market square:2:true") ||
                !probeResults.hasEvent("command:block:1:64:2") ||
                !probeResults.hasEvent("command:extra:fragment") ||
                !probeResults.hasEvent("command:greet:graph")
            ) {
                delay(10)
            }
        }
    }

    private suspend fun exerciseEvents(): Unit = coroutineScope {
        val serverEvent = FixtureServerEvent("static")
        callEvent(serverEvent)
        check(serverEvent.isCancelled) { "The generated synchronous event handler did not cancel" }

        val awaited = async(start = CoroutineStart.UNDISPATCHED) {
            serverEvents.await<FixtureQueryEvent, String> { value }
        }
        callEvent(FixtureQueryEvent("awaited"))
        check(awaited.await() == "awaited")

        val capturedEvent = FixtureQueryEvent("captured")
        val captured = async(start = CoroutineStart.UNDISPATCHED) {
            serverEvents.capture<FixtureQueryEvent, String> { value }
        }
        callEvent(capturedEvent)
        check(captured.await() == "captured")
        check(capturedEvent.isCancelled) { "The event capture did not cancel its selected event" }

        val streamed = async(start = CoroutineStart.UNDISPATCHED) {
            serverEvents.stream<FixtureQueryEvent, String>(
                capacity = 1,
                overflow = BufferOverflow.DROP_OLDEST,
            ) { value }.first()
        }
        yield()
        callEvent(FixtureQueryEvent("streamed"))
        check(streamed.await() == "streamed")

        with(applicationEvents) {
            FixtureApplicationEvent("published").publish()
        }

        withTimeout(5.seconds) {
            while (
                !probeResults.hasEvent("event:observer:static") ||
                !probeResults.hasEvent("event:application:published") ||
                !probeResults.hasEvent("event:application-suspend:published") ||
                !probeResults.hasEvent("event:lifecycle")
            ) {
                delay(10)
            }
        }
    }

    private suspend fun callEvent(event: Event) {
        withGlobal { server.pluginManager.callEvent(event) }
    }

    override fun ComponentContext.disable(): Unit {
        probeResults.record("plugin:disable")
    }

    private suspend fun exerciseExecutionContexts(): Unit {
        val location: Location =
            withGlobal {
                probeResults.record("execution:global")
                server.worlds.first().spawnLocation.clone()
            }

        val armorStand: ArmorStand =
            withRegion(location) {
                spawnProbeEntity(location)
            }

        when (
            withEntity(armorStand) {
                removeProbeEntity(armorStand)
            }
        ) {
            is EntityOutcome.Completed -> Unit
            EntityOutcome.Retired -> error("The integration-test entity retired before its ownership check")
        }
    }

    context(region: RegionContext)
    private fun spawnProbeEntity(location: Location): ArmorStand {
        check(region.world === location.world) { "Region context selected the wrong world" }
        check(region.chunkX == location.blockX shr 4) { "Region context selected the wrong chunk X" }
        check(region.chunkZ == location.blockZ shr 4) { "Region context selected the wrong chunk Z" }
        region.world.getChunkAt(region.chunkX, region.chunkZ)
        probeResults.record("execution:region")
        return region.world.spawn(location, ArmorStand::class.java)
    }

    context(entityContext: EntityContext)
    private fun removeProbeEntity(expected: ArmorStand): Unit {
        check(entityContext.entity === expected) { "Entity context selected the wrong entity" }
        entityContext.entity.remove()
        probeResults.record("execution:entity")
    }

    private suspend fun requestServerShutdown(): Unit {
        val scheduled =
            runCatching {
                withGlobal {
                    server.shutdown()
                }
            }
        if (scheduled.isFailure) {
            probeResults.fail(scheduled.exceptionOrNull()!!)
            server.globalRegionScheduler.execute(this) { server.shutdown() }
        }
    }
}

/** Interface binding used to prove that generated roots can contribute abstractions. */
public interface AutomaticProbe {
    public val started: Boolean
}

/** Generated DI constructs and installs this root without an explicit component declaration. */
@FrameworkComponent
@Binds(AutomaticProbe::class)
@Inject
public class AutomaticProbeComponent(
    private val results: ProbeResults,
) : PluginComponent(), AutomaticProbe {
    override var started: Boolean = false
        private set

    override fun ComponentContext.start(): Unit {
        started = true
        results.record("automatic:start")
    }

    override fun ComponentContext.stop(): Unit {
        results.record("automatic:stop")
        started = false
    }
}

private class ParentProbe(
    private val results: ProbeResults,
) : PluginComponent() {
    private val child by component { ChildProbe(results) }

    override fun ComponentContext.start(): Unit {
        check(child.started) { "A child component was not ready when its parent started" }
        results.record("parent:start")
        own(AutoCloseable { results.record("parent:close") })
    }

    override fun ComponentContext.stop(): Unit {
        results.record("parent:stop")
    }

    suspend fun awaitOrdinaryTask(): Unit = child.taskFinished.await()
}

private class ChildProbe(
    private val results: ProbeResults,
) : PluginComponent() {
    val taskFinished: CompletableDeferred<Unit> = CompletableDeferred()
    var started: Boolean = false
        private set

    override fun ComponentContext.start(): Unit {
        started = true
        results.record("child:start")
        own(AutoCloseable { results.record("child:close") })
        task("ordinary") {
            results.record("child:task")
            taskFinished.complete(Unit)
        }
    }

    override fun ComponentContext.stop(): Unit {
        results.record("child:stop")
        started = false
    }
}

@Events
internal class FixtureEvents(
    private val results: ProbeResults,
) {
    @On(priority = EventPriority.HIGH)
    internal fun FixtureServerEvent.handle() {
        results.record("event:sync:$value")
        isCancelled = true
    }

    @Observe
    internal suspend fun FixtureServerEvent.observe() {
        val copied = value
        results.record("event:observer-prefix:$copied")
        yield()
        results.record("event:observer:$copied")
    }

    @OnApplication
    internal fun FixtureApplicationEvent.handle() {
        results.record("event:application:$value")
    }

    @OnApplication
    internal suspend fun FixtureApplicationEvent.handleSuspend() {
        yield()
        results.record("event:application-suspend:$value")
    }

    @ConfigureLifecycleEvents
    internal fun LifecycleEventRegistry.configureFixtureEvents() {
        on(LifecycleEvents.COMMANDS, priority = -100) {
            results.record("event:lifecycle")
        }
    }
}

internal data class FixtureApplicationEvent(val value: String) : ApplicationEvent

internal class FixtureServerEvent(val value: String) : Event(), Cancellable {
    private var cancelled = false

    override fun isCancelled(): Boolean = cancelled

    override fun setCancelled(cancelled: Boolean) {
        this.cancelled = cancelled
    }

    override fun getHandlers(): HandlerList = HANDLERS

    companion object {
        @JvmStatic
        private val HANDLERS = HandlerList()

        @JvmStatic
        fun getHandlerList(): HandlerList = HANDLERS
    }
}

internal class FixtureQueryEvent(val value: String) : Event(), Cancellable {
    private var cancelled = false

    override fun isCancelled(): Boolean = cancelled

    override fun setCancelled(cancelled: Boolean) {
        this.cancelled = cancelled
    }

    override fun getHandlers(): HandlerList = HANDLERS

    companion object {
        @JvmStatic
        private val HANDLERS = HandlerList()

        @JvmStatic
        fun getHandlerList(): HandlerList = HANDLERS
    }
}

@Commands(name = "frameworkfixture")
internal class FixtureCommands(
    private val results: ProbeResults,
    private val routes: FixtureCommandsRoutes,
) {
    @ConfigureCommandGraph
    public fun configureGraph(graph: CommandGraph) {
        graph.redirect(routes.redirected(), routes.greet("graph"))
    }

    /** Records a generated command with an optional positional argument. */
    public fun greet(name: String = "world"): String {
        results.record("command:greet:$name")
        return "Hello, $name!"
    }

    /** Proves recursive typealias annotation handling for decoded greedy text. */
    @FixturePolicy("say")
    public fun say(message: GreedyText): String {
        results.record("command:say:$message")
        return message
    }

    /** Proves interleaved named options and generated options defaults. */
    public fun search(query: GreedyText, options: FixtureSearchOptions): String {
        results.record("command:search:$query:${options.limit}:${options.verbose}")
        return query
    }

    /** Proves native Paper block-position parsing becomes a stable asynchronous reference. */
    public fun block(block: BlockRef): String {
        results.record("command:block:${block.x}:${block.y}:${block.z}")
        return "${block.x}, ${block.y}, ${block.z}"
    }

    /** This handler is bypassed by a generated typed graph redirect. */
    public fun redirected(): String = error("The graph redirect did not run")

    @Group(permission = "framework.fixture.admin")
    public inner class Admin {
        /** Proves nested group construction and inherited permissions. */
        public fun echo(value: String): String {
            results.record("command:admin:$value")
            return value
        }
    }

    @Scope(permission = "framework.fixture.scoped")
    public inner class Personal(
        private val scopedResults: ProbeResults,
    ) {
        /** Proves scope injection and suspending direct handler calls. */
        public suspend fun scoped(value: String): String {
            scopedResults.record("command:scoped:$value")
            return value
        }
    }
}

@CommandFragment(FixtureCommands::class)
internal class FixtureCommandFragment(
    private val results: ProbeResults,
) {
    /** Proves an automatically discovered fragment merges into its owning root. */
    public fun extra(value: String): String {
        results.record("command:extra:$value")
        return value
    }
}

@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
@CommandPolicy(
    interceptor = FixturePolicyInterceptor::class,
    phase = CommandPolicyPhase.BEFORE_HANDLER,
)
internal annotation class FixturePolicy(val value: String)

@Inject
internal class FixturePolicyInterceptor(
    private val results: ProbeResults,
) : CommandPolicyInterceptor<FixturePolicy> {
    override suspend fun intercept(
        annotation: FixturePolicy,
        context: CommandPolicyContext,
        next: suspend () -> Any?,
    ): Any? {
        results.record("policy:${annotation.value}:${context.invocation.route}")
        return next()
    }
}

@Options
internal data class FixtureSearchOptions(
    @Option(short = 'l') val limit: Int = 10,
    @Option(short = 'v') val verbose: Boolean = false,
)

@Inject
@PluginScoped
public class ProbeResults {
    private val events: MutableList<String> = CopyOnWriteArrayList()
    private val failure: AtomicReference<Throwable?> = AtomicReference()

    public fun record(event: String): Unit {
        events += event
    }

    public fun fail(cause: Throwable): Unit {
        failure.compareAndSet(null, cause)
    }

    public fun hasEvent(event: String): Boolean = event in events

    public fun writeReceipt(): Unit {
        val failure = failure.get() ?: runCatching(::checkEvents).exceptionOrNull()
        val status = if (failure == null) "PASS" else "FAIL"
        val detail = failure?.stackTraceToString().orEmpty()
        val receipt = buildString {
            appendLine(status)
            events.forEach(::appendLine)
            if (detail.isNotEmpty()) append(detail)
        }
        Files.writeString(Path.of("fixture-result.txt"), receipt)
    }

    private fun checkEvents(): Unit {
        val required =
            listOf(
                "child:start",
                "parent:start",
                "automatic:start",
                "automatic:injected",
                "input:available",
                "plugin:enable",
                "child:task",
                "execution:global",
                "execution:region",
                "execution:entity",
                "event:sync:static",
                "event:observer-prefix:static",
                "event:observer:static",
                "event:application:published",
                "event:application-suspend:published",
                "event:lifecycle",
                "command:greet:world",
                "command:greet:Paper",
                "command:admin:Folia",
                "command:scoped:Kotlin",
                "command:say:market square",
                "command:search:market square:2:true",
                "command:block:1:64:2",
                "command:extra:fragment",
                "command:greet:graph",
                "policy:say:frameworkfixture say",
                "plugin:disable",
                "automatic:stop",
                "parent:stop",
                "child:stop",
                "child:close",
                "parent:close",
            )
        required.forEach { event -> check(event in events) { "Missing fixture event '$event': $events" } }

        assertBefore("child:start", "parent:start")
        assertBefore("parent:start", "plugin:enable")
        assertBefore("automatic:start", "automatic:injected")
        assertBefore("automatic:injected", "plugin:enable")
        assertBefore("plugin:enable", "execution:global")
        assertBefore("execution:global", "execution:region")
        assertBefore("execution:region", "execution:entity")
        assertBefore("event:observer-prefix:static", "event:observer:static")
        assertBefore("policy:say:frameworkfixture say", "command:say:market square")
        assertBefore("plugin:disable", "parent:stop")
        assertBefore("plugin:disable", "automatic:stop")
        assertBefore("parent:stop", "child:stop")
        assertBefore("child:stop", "child:close")
        assertBefore("child:close", "parent:close")
    }

    private fun assertBefore(first: String, second: String): Unit {
        check(events.indexOf(first) < events.indexOf(second)) {
            "Expected '$first' before '$second': $events"
        }
    }
}
