package dev.placeholder.framework.benchmarks

import java.util.concurrent.ConcurrentHashMap
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KProperty
import kotlinx.coroutines.asContextElement

/** A named collection of benchmark scenarios discovered as one source declaration. */
public class BenchmarkSuite internal constructor(
    public val namespace: String,
    scenarios: List<BenchmarkScenario>,
) {
    public val scenarios: List<BenchmarkScenario> = scenarios.toList()

    init {
        require(namespace.matches(NAMESPACE_PATTERN)) { "Invalid benchmark namespace '$namespace'" }
        require(this.scenarios.isNotEmpty()) { "Benchmark suite '$namespace' has no scenarios" }
        require(this.scenarios.map(BenchmarkScenario::id).distinct().size == this.scenarios.size) {
            "Benchmark suite '$namespace' contains duplicate scenario ids"
        }
    }

    private companion object {
        val NAMESPACE_PATTERN: Regex = Regex("[a-z][a-z0-9-]*")
    }
}

/** One executable scenario and its performance contract metadata. */
public class BenchmarkScenario internal constructor(
    public val id: BenchmarkId,
    public val status: PerformanceContractStatus,
    public val profiles: List<BenchmarkProfile>,
    public val layers: Set<BenchmarkLayer>,
    public val temperatures: Set<BenchmarkTemperature>,
    public val budgets: BenchmarkBudgets,
    internal val fixtures: List<FixtureDeclaration<*>>,
    internal val setup: suspend BenchmarkExecutionScope.() -> Unit,
    internal val measure: suspend BenchmarkExecutionScope.() -> Any?,
    internal val verify: suspend BenchmarkExecutionScope.(Any?) -> Unit,
    internal val cleanup: suspend BenchmarkExecutionScope.() -> Unit,
) {
    init {
        require(profiles.isNotEmpty()) { "Benchmark '$id' has no profiles" }
        require(profiles.map(BenchmarkProfile::name).distinct().size == profiles.size) {
            "Benchmark '$id' contains duplicate profile names"
        }
        require(layers.isNotEmpty()) { "Benchmark '$id' has no evidence layers" }
        require(temperatures.isNotEmpty()) { "Benchmark '$id' has no temperatures" }
        if (status >= PerformanceContractStatus.GUARDED) {
            require(budgets.relative.isNotEmpty()) { "Guarded benchmark '$id' needs a relative budget" }
        }
        if (status >= PerformanceContractStatus.CONTRACTUAL) {
            require(budgets.absolute.isNotEmpty()) { "Contractual benchmark '$id' needs an absolute budget" }
        }
    }
}

/** Runtime scope visible to scenario setup, measurement, and verification. */
public class BenchmarkExecutionScope internal constructor(
    public val scenario: BenchmarkScenario,
    public val profile: BenchmarkProfile,
    public val layer: BenchmarkLayer,
    public val temperature: BenchmarkTemperature,
) {
    private val fixtures: MutableMap<String, Any?> = ConcurrentHashMap()
    private val measurements: MutableMap<BenchmarkMetric, Double> = linkedMapOf()

    /** Records a layer-specific metric outside the core wall-time and allocation measurements. */
    public fun record(metric: BenchmarkMetric, value: Double) {
        require(value.isFinite() && value >= 0.0) { "Recorded metric must be finite and non-negative" }
        check(metric !in measurements) { "Metric $metric was recorded twice in one operation" }
        measurements[metric] = value
    }

    /** Prevents a measured result from becoming dead code in simple scenario runners. */
    public fun consume(value: Any?) {
        BenchmarkSink.consume(value)
    }

    internal fun installFixture(name: String, value: Any?) {
        check(name !in fixtures) { "Fixture '$name' was installed twice" }
        fixtures[name] = value
    }

    @Suppress("UNCHECKED_CAST")
    internal fun <T> fixture(name: String): T =
        checkNotNull(fixtures[name]) { "Fixture '$name' is not active" } as T

    @Suppress("UNCHECKED_CAST")
    internal fun <T> removeFixture(name: String): T =
        checkNotNull(fixtures.remove(name)) { "Fixture '$name' is not active" } as T

    internal fun recordedMetrics(): Map<BenchmarkMetric, Double> = measurements.toMap()

    internal fun clearRecordedMetrics() {
        measurements.clear()
    }
}

/** Delegated fixture resolved from the active scenario operation. */
public class BenchmarkFixture<T> internal constructor(private val name: String) : ReadOnlyProperty<Any?, T> {
    override fun getValue(thisRef: Any?, property: KProperty<*>): T =
        BenchmarkExecution.current().fixture(name)
}

/** Builds one collection of benchmark scenarios under [namespace]. */
public fun benchmarkSuite(
    namespace: String,
    block: BenchmarkSuiteBuilder.() -> Unit,
): BenchmarkSuite = BenchmarkSuiteBuilder(namespace).apply(block).build()

/** Builds one standalone scenario whose [id] already contains a namespace. */
public fun benchmarkScenario(
    id: String,
    block: BenchmarkScenarioBuilder.() -> Unit,
): BenchmarkScenario = BenchmarkScenarioBuilder(BenchmarkId(id)).apply(block).build()

/** Collects scenarios for one [BenchmarkSuite]. */
public class BenchmarkSuiteBuilder internal constructor(private val namespace: String) {
    private val scenarios: MutableList<BenchmarkScenario> = mutableListOf()

    /** Adds one scenario below this suite's namespace. */
    public fun benchmarkScenario(
        name: String,
        block: BenchmarkScenarioBuilder.() -> Unit,
    ) {
        scenarios += dev.placeholder.framework.benchmarks.benchmarkScenario("$namespace.$name", block)
    }

    internal fun build(): BenchmarkSuite = BenchmarkSuite(namespace, scenarios)
}

/** Builds one scenario and its contract. */
public class BenchmarkScenarioBuilder internal constructor(private val id: BenchmarkId) {
    private var statusValue: PerformanceContractStatus? = null
    private val profiles: MutableList<BenchmarkProfile> = mutableListOf()
    private val layers: MutableSet<BenchmarkLayer> = linkedSetOf(BenchmarkLayer.JVM)
    private val temperatures: MutableSet<BenchmarkTemperature> = linkedSetOf(BenchmarkTemperature.WARM)
    private val fixtures: MutableList<FixtureDeclaration<*>> = mutableListOf()
    private var setupBlock: suspend BenchmarkExecutionScope.() -> Unit = {}
    private var measureBlock: (suspend BenchmarkExecutionScope.() -> Any?)? = null
    private var verifyBlock: (suspend BenchmarkExecutionScope.(Any?) -> Unit)? = null
    private var cleanupBlock: suspend BenchmarkExecutionScope.() -> Unit = {}
    private var budgetsValue: BenchmarkBudgets = BenchmarkBudgets()

    /** Explicit maturity promise made by this scenario. */
    public var status: PerformanceContractStatus
        get() = checkNotNull(statusValue) { "Benchmark '$id' has no explicit status" }
        set(value) {
            statusValue = value
        }

    /** Replaces the default warmed JVM layer set. */
    public fun evidence(vararg layers: BenchmarkLayer) {
        this.layers.clear()
        this.layers += layers
    }

    /** Replaces the default warmed temperature set. */
    public fun temperatures(vararg temperatures: BenchmarkTemperature) {
        this.temperatures.clear()
        this.temperatures += temperatures
    }

    /** Declares explicit numeric workload profiles. */
    public fun profiles(block: BenchmarkProfilesBuilder.() -> Unit) {
        profiles.clear()
        profiles += BenchmarkProfilesBuilder().apply(block).build()
    }

    /** Declares one lifecycle-owned fixture available through a delegated property. */
    public fun <T : Any> fixture(
        name: String,
        close: suspend BenchmarkExecutionScope.(T) -> Unit = {},
        create: suspend BenchmarkExecutionScope.() -> T,
    ): BenchmarkFixture<T> {
        require(name.matches(FIXTURE_NAME)) { "Invalid benchmark fixture name '$name'" }
        require(fixtures.none { it.name == name }) { "Duplicate benchmark fixture '$name'" }
        fixtures += FixtureDeclaration(name, create, close)
        return BenchmarkFixture(name)
    }

    /** Runs before each measured operation and outside its timed boundary. */
    public fun setup(block: suspend BenchmarkExecutionScope.() -> Unit) {
        setupBlock = block
    }

    /** Declares the operation whose result and cost are measured. */
    public fun measure(block: suspend BenchmarkExecutionScope.() -> Any?) {
        measureBlock = block
    }

    /** Proves the measured operation produced its intended semantic result. */
    public fun verify(block: suspend BenchmarkExecutionScope.(Any?) -> Unit) {
        verifyBlock = block
    }

    /** Runs after verification and outside the timed boundary. */
    public fun cleanup(block: suspend BenchmarkExecutionScope.() -> Unit) {
        cleanupBlock = block
    }

    /** Declares relative and absolute performance budgets. */
    public fun budgets(block: BenchmarkBudgetsBuilder.() -> Unit) {
        budgetsValue = BenchmarkBudgetsBuilder().apply(block).build()
    }

    internal fun build(): BenchmarkScenario = BenchmarkScenario(
        id = id,
        status = checkNotNull(statusValue) { "Benchmark '$id' must declare its status" },
        profiles = profiles.ifEmpty { listOf(BenchmarkProfile("typical")) },
        layers = layers.toSet(),
        temperatures = temperatures.toSet(),
        budgets = budgetsValue,
        fixtures = fixtures.toList(),
        setup = setupBlock,
        measure = checkNotNull(measureBlock) { "Benchmark '$id' must declare measure" },
        verify = checkNotNull(verifyBlock) { "Benchmark '$id' must declare verify" },
        cleanup = cleanupBlock,
    )

    private companion object {
        val FIXTURE_NAME: Regex = Regex("[a-z][a-zA-Z0-9]*")
    }
}

/** Collects named numeric workload profiles. */
public class BenchmarkProfilesBuilder internal constructor() {
    private val profiles: MutableList<BenchmarkProfile> = mutableListOf()

    /** Adds one profile from key/value pairs. */
    public fun profile(name: String, vararg parameters: Pair<String, Number>) {
        profiles += BenchmarkProfile(name, parameters.associate { (key, value) -> key to value.toLong() })
    }

    internal fun build(): List<BenchmarkProfile> = profiles.toList()
}

internal class FixtureDeclaration<T : Any>(
    val name: String,
    val create: suspend BenchmarkExecutionScope.() -> T,
    val close: suspend BenchmarkExecutionScope.(T) -> Unit,
)

internal object BenchmarkExecution {
    private val active: ThreadLocal<BenchmarkExecutionScope?> = ThreadLocal()

    fun current(): BenchmarkExecutionScope =
        checkNotNull(active.get()) { "A benchmark fixture was accessed outside an active operation" }

    fun context(scope: BenchmarkExecutionScope): kotlinx.coroutines.ThreadContextElement<BenchmarkExecutionScope?> =
        active.asContextElement(scope)
}

private object BenchmarkSink {
    @Volatile
    private var value: Any? = null

    fun consume(next: Any?) {
        value = next
    }
}
