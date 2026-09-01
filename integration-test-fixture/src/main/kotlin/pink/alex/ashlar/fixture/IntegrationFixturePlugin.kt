package pink.alex.ashlar.fixture

import pink.alex.ashlar.ComponentContext
import pink.alex.ashlar.AshlarComponent
import pink.alex.ashlar.AshlarPlugin
import pink.alex.ashlar.PluginComponent
import pink.alex.ashlar.commands.Commands
import pink.alex.ashlar.commands.CommandFragment
import pink.alex.ashlar.commands.ConfigureCommandGraph
import pink.alex.ashlar.commands.Group
import pink.alex.ashlar.commands.GreedyText
import pink.alex.ashlar.commands.Option
import pink.alex.ashlar.commands.Options
import pink.alex.ashlar.commands.Scope
import pink.alex.ashlar.commands.reference.BlockRef
import pink.alex.ashlar.commands.policy.CommandPolicy
import pink.alex.ashlar.commands.policy.CommandPolicyContext
import pink.alex.ashlar.commands.policy.CommandPolicyInterceptor
import pink.alex.ashlar.commands.policy.CommandPolicyPhase
import pink.alex.ashlar.commands.graph.CommandGraph
import pink.alex.ashlar.benchmarks.BenchmarkCaseId
import pink.alex.ashlar.benchmarks.BenchmarkCaseResult
import pink.alex.ashlar.benchmarks.BenchmarkJson
import pink.alex.ashlar.benchmarks.BenchmarkLayer
import pink.alex.ashlar.benchmarks.BenchmarkMetric
import pink.alex.ashlar.benchmarks.BenchmarkMetricValue
import pink.alex.ashlar.benchmarks.BenchmarkProfile
import pink.alex.ashlar.benchmarks.BenchmarkRunConfiguration
import pink.alex.ashlar.benchmarks.BenchmarkRunResult
import pink.alex.ashlar.benchmarks.BenchmarkSample
import pink.alex.ashlar.benchmarks.BenchmarkStatistics
import pink.alex.ashlar.benchmarks.BenchmarkTemperature
import pink.alex.ashlar.benchmarks.BenchmarkId
import pink.alex.ashlar.benchmarks.MeasurementEnvironment
import pink.alex.ashlar.benchmarks.PerformanceContractStatus
import pink.alex.ashlar.di.Binds
import pink.alex.ashlar.di.Inject
import pink.alex.ashlar.di.PluginScoped
import pink.alex.ashlar.execution.EntityContext
import pink.alex.ashlar.execution.EntityOutcome
import pink.alex.ashlar.execution.PlayerRef
import pink.alex.ashlar.execution.RegionContext
import pink.alex.ashlar.execution.withEntity
import pink.alex.ashlar.execution.withGlobal
import pink.alex.ashlar.execution.withRegion
import pink.alex.ashlar.events.ApplicationEvent
import pink.alex.ashlar.events.ApplicationEvents
import pink.alex.ashlar.events.ConfigureLifecycleEvents
import pink.alex.ashlar.events.Events
import pink.alex.ashlar.events.LifecycleEventRegistry
import pink.alex.ashlar.events.Observe
import pink.alex.ashlar.events.On
import pink.alex.ashlar.events.OnApplication
import pink.alex.ashlar.events.ServerEvents
import pink.alex.ashlar.events.capture
import pink.alex.ashlar.events.await
import pink.alex.ashlar.events.publish
import pink.alex.ashlar.events.stream
import pink.alex.ashlar.input.PlayerInput
import pink.alex.ashlar.menus.PlayerMenus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicInteger
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

/** Executed by the Gradle Paper and Folia integration-test tasks. */
public class IntegrationFixturePlugin : AshlarPlugin() {
    private val probeResults by inject<ProbeResults>()
    private val automaticProbe by inject<AutomaticProbe>()
    private val serverEvents by inject<ServerEvents>()
    private val applicationEvents by inject<ApplicationEvents>()
    private val playerInput by inject<PlayerInput>()
    private val playerMenus by inject<PlayerMenus>()
    private val commandBenchmarkProbe by inject<CommandBenchmarkProbe>()
    private val lifecycleProbe by component { ParentProbe(probeResults) }
    private val benchmarkCases = CopyOnWriteArrayList<BenchmarkCaseResult>()
    override fun ComponentContext.enable(): Unit {
        check(automaticProbe.started) { "The automatic DI component was not started before the plug-in" }
        probeResults.record("automatic:injected")
        check(!playerInput.cancel(PlayerRef(UUID(0L, 0L))))
        probeResults.record("input:available")
        check(playerMenus.inspect(PlayerRef(UUID(0L, 0L))) == null)
        probeResults.record("menus:available")
        probeResults.record("plugin:enable")
        own(AutoCloseable { probeResults.writeReceipt() })

        task("fixture") {
            try {
                lifecycleProbe.awaitOrdinaryTask()
                benchmarkCases += exerciseExecutionContexts()
                withGlobal { runItemIntegrationChecks() }
                exerciseEvents()
                exerciseCommands()
                exerciseCommandBenchmark()
                exerciseLoadBenchmark()
                exerciseSoakBenchmark()
                writeBenchmarkResults()
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

    private suspend fun exerciseCommandBenchmark(): Unit {
        val probe = commandBenchmarkProbe
        repeat(COMMAND_BENCHMARK_WARMUPS) { sequence ->
            dispatchMeasuredCommand(probe, sequence)
        }
        val endToEndSamples = mutableListOf<BenchmarkSample>()
        val callbackSamples = mutableListOf<Double>()
        repeat(COMMAND_BENCHMARK_ITERATIONS) { offset ->
            val sequence = COMMAND_BENCHMARK_WARMUPS + offset
            val completion = probe.expect(sequence)
            val started = System.nanoTime()
            val callbackNanos = withGlobal {
                val callbackStarted = System.nanoTime()
                check(server.dispatchCommand(server.consoleSender, "frameworkfixture benchmark $sequence"))
                System.nanoTime() - callbackStarted
            }
            completion.await()
            endToEndSamples += BenchmarkSample(System.nanoTime() - started)
            callbackSamples += callbackNanos.toDouble()
        }
        val platform = if (server.name.contains("Folia", ignoreCase = true)) {
            BenchmarkLayer.FOLIA
        } else {
            BenchmarkLayer.PAPER
        }
        val metrics = BenchmarkStatistics.aggregate(endToEndSamples).toMutableList().apply {
            add(
                BenchmarkMetricValue(
                    BenchmarkMetric.NATIVE_CALLBACK,
                    BenchmarkStatistics.percentile(callbackSamples, 0.99),
                ),
            )
        }
        benchmarkCases += BenchmarkCaseResult(
            id = BenchmarkCaseId(
                BenchmarkId("commands.dispatch"),
                BenchmarkProfile("typical").name,
                platform,
                BenchmarkTemperature.WARM,
            ),
            status = PerformanceContractStatus.EXPLORATORY,
            metrics = metrics,
            samples = endToEndSamples,
            supplementalSamples = mapOf(BenchmarkMetric.NATIVE_CALLBACK to callbackSamples),
        )
        probeResults.record("benchmark:commands:${platform.name.lowercase()}")
    }

    private suspend fun dispatchMeasuredCommand(probe: CommandBenchmarkProbe, sequence: Int) {
        val completion = probe.expect(sequence)
        withGlobal {
            check(server.dispatchCommand(server.consoleSender, "frameworkfixture benchmark $sequence"))
        }
        completion.await()
    }

    private suspend fun exerciseLoadBenchmark() {
        val samples = CopyOnWriteArrayList<BenchmarkSample>()
        val callbacks = CopyOnWriteArrayList<Double>()
        val scheduling = CopyOnWriteArrayList<Double>()
        val sequence = AtomicInteger(1_000_000)
        coroutineScope {
            repeat(LOAD_ACTORS) {
                launch {
                    repeat(LOAD_OPERATIONS_PER_ACTOR) {
                        val current = sequence.incrementAndGet()
                        val completion = commandBenchmarkProbe.expect(current)
                        val started = System.nanoTime()
                        val callback = withGlobal {
                            val callbackStarted = System.nanoTime()
                            check(server.dispatchCommand(server.consoleSender, "frameworkfixture benchmark $current"))
                            System.nanoTime() - callbackStarted
                        }
                        val callbackReturned = System.nanoTime()
                        completion.await()
                        samples += BenchmarkSample(System.nanoTime() - started)
                        callbacks += callback.toDouble()
                        scheduling += (System.nanoTime() - callbackReturned).toDouble()
                    }
                }
            }
        }
        benchmarkCases += BenchmarkCaseResult(
            id = BenchmarkCaseId(
                BenchmarkId("load.multiplayer"),
                "typical",
                BenchmarkLayer.LOAD,
                BenchmarkTemperature.WARM,
            ),
            status = PerformanceContractStatus.EXPLORATORY,
            metrics = BenchmarkStatistics.aggregate(samples) + listOf(
                BenchmarkMetricValue(
                    BenchmarkMetric.NATIVE_CALLBACK,
                    BenchmarkStatistics.percentile(callbacks, 0.99),
                ),
                BenchmarkMetricValue(
                    BenchmarkMetric.SCHEDULING,
                    BenchmarkStatistics.percentile(scheduling, 0.99),
                ),
            ),
            samples = samples,
            supplementalSamples = mapOf(
                BenchmarkMetric.NATIVE_CALLBACK to callbacks,
                BenchmarkMetric.SCHEDULING to scheduling,
            ),
        )
        probeResults.record("benchmark:load")
    }

    private suspend fun exerciseSoakBenchmark() {
        val requestedSeconds = System.getProperty("ashlar.benchmark.soakSeconds", "1").toLong()
        require(requestedSeconds > 0) { "Soak duration must be positive" }
        val deadline = System.nanoTime() + requestedSeconds * 1_000_000_000L
        val samples = mutableListOf<BenchmarkSample>()
        val memoryBefore = usedHeap()
        var sequence = 2_000_000
        do {
            val started = System.nanoTime()
            dispatchMeasuredCommand(commandBenchmarkProbe, sequence++)
            samples += BenchmarkSample(System.nanoTime() - started)
        } while (System.nanoTime() < deadline)
        val retained = (usedHeap() - memoryBefore).coerceAtLeast(0L).toDouble()
        val profile = when {
            requestedSeconds >= 3_600 -> "stress"
            requestedSeconds >= 60 -> "typical"
            else -> "small"
        }
        benchmarkCases += BenchmarkCaseResult(
            id = BenchmarkCaseId(
                BenchmarkId("soak.lifecycle"),
                profile,
                BenchmarkLayer.SOAK,
                BenchmarkTemperature.WARM,
            ),
            status = PerformanceContractStatus.EXPLORATORY,
            metrics = BenchmarkStatistics.aggregate(samples) +
                BenchmarkMetricValue(BenchmarkMetric.RETAINED_HEAP, retained),
            samples = samples,
            supplementalSamples = mapOf(BenchmarkMetric.RETAINED_HEAP to listOf(retained)),
        )
        probeResults.record("benchmark:soak")
    }

    private fun writeBenchmarkResults() {
        val platform = benchmarkPlatform()
        BenchmarkJson.write(
            Path.of("benchmark-result.json"),
            BenchmarkRunResult(
                revision = System.getProperty("ashlar.benchmark.revision", "working-tree"),
                environment = MeasurementEnvironment.local(
                    ashlarVersion = pluginMeta.version,
                    environmentId = "integration-local",
                    platform = platform.name,
                    platformVersion = server.version,
                    attributes = mapOf("soakSeconds" to System.getProperty("ashlar.benchmark.soakSeconds", "1")),
                ),
                configuration = BenchmarkRunConfiguration(
                    warmupIterations = COMMAND_BENCHMARK_WARMUPS,
                    measurementIterations = COMMAND_BENCHMARK_ITERATIONS,
                    forks = 1,
                    collectAllocation = false,
                ),
                cases = benchmarkCases.toList(),
            ),
        )
    }

    private fun benchmarkPlatform(): BenchmarkLayer = if (server.name.contains("Folia", ignoreCase = true)) {
        BenchmarkLayer.FOLIA
    } else {
        BenchmarkLayer.PAPER
    }

    private fun usedHeap(): Long {
        val runtime = Runtime.getRuntime()
        return runtime.totalMemory() - runtime.freeMemory()
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

    private suspend fun exerciseExecutionContexts(): BenchmarkCaseResult {
        val started = System.nanoTime()
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
        val sample = BenchmarkSample(System.nanoTime() - started)
        return BenchmarkCaseResult(
            id = BenchmarkCaseId(
                BenchmarkId("kernel.scheduler-handoff"),
                "small",
                benchmarkPlatform(),
                BenchmarkTemperature.WARM,
            ),
            status = PerformanceContractStatus.EXPLORATORY,
            metrics = BenchmarkStatistics.aggregate(listOf(sample)),
            samples = listOf(sample),
        )
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

    private companion object {
        const val COMMAND_BENCHMARK_WARMUPS: Int = 20
        const val COMMAND_BENCHMARK_ITERATIONS: Int = 100
        const val LOAD_ACTORS: Int = 16
        const val LOAD_OPERATIONS_PER_ACTOR: Int = 4
    }
}

/** Interface binding used to prove that generated roots can contribute abstractions. */
public interface AutomaticProbe {
    public val started: Boolean
}

/** Generated DI constructs and installs this root without an explicit component declaration. */
@AshlarComponent
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
    private val benchmarkProbe: CommandBenchmarkProbe,
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

    /** Minimal handler used to measure native admission and asynchronous completion separately. */
    public fun benchmark(sequence: Int) {
        benchmarkProbe.complete(sequence)
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

@Inject
@PluginScoped
internal class CommandBenchmarkProbe {
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<Unit>>()

    fun expect(sequence: Int): CompletableDeferred<Unit> = CompletableDeferred<Unit>().also { completion ->
        check(pending.putIfAbsent(sequence, completion) == null) { "Benchmark sequence $sequence is already pending" }
    }

    fun complete(sequence: Int) {
        checkNotNull(pending.remove(sequence)) { "Benchmark sequence $sequence was not prepared" }.complete(Unit)
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
                "menus:available",
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
