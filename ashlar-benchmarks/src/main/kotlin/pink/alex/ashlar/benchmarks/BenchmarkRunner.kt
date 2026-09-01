package pink.alex.ashlar.benchmarks

import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory
import kotlinx.coroutines.withContext

/** Runs Kotlin benchmark scenarios and preserves raw samples for paired comparison. */
public class BenchmarkRunner(
    private val environment: MeasurementEnvironment,
    private val configuration: BenchmarkRunConfiguration = BenchmarkRunConfiguration(),
) {
    /** Runs every selected scenario case in [suite]. */
    public suspend fun run(
        suite: BenchmarkSuite,
        revision: String,
        select: (BenchmarkCaseId) -> Boolean = { true },
    ): BenchmarkRunResult {
        val cases = buildList {
            suite.scenarios.forEach { scenario ->
                scenario.profiles.forEach { profile ->
                    scenario.layers.forEach { layer ->
                        scenario.temperatures.forEach { temperature ->
                            val id = BenchmarkCaseId(scenario.id, profile.name, layer, temperature)
                            if (select(id)) add(runCase(scenario, profile, layer, temperature))
                        }
                    }
                }
            }
        }
        require(cases.isNotEmpty()) { "Benchmark selection produced no cases" }
        return BenchmarkRunResult(
            revision = revision,
            environment = environment,
            configuration = configuration,
            cases = cases,
        )
    }

    private suspend fun runCase(
        scenario: BenchmarkScenario,
        profile: BenchmarkProfile,
        layer: BenchmarkLayer,
        temperature: BenchmarkTemperature,
    ): BenchmarkCaseResult {
        val samples = mutableListOf<BenchmarkSample>()
        val supplemental = linkedMapOf<BenchmarkMetric, MutableList<Double>>()
        repeat(configuration.forks) {
            if (temperature == BenchmarkTemperature.WARM) {
                val scope = createScope(scenario, profile, layer, temperature)
                try {
                    repeat(configuration.warmupIterations) { execute(scope, measured = false, samples, supplemental) }
                    repeat(configuration.measurementIterations) { execute(scope, measured = true, samples, supplemental) }
                } finally {
                    closeScope(scope)
                }
            } else {
                repeat(configuration.warmupIterations) {
                    val scope = createScope(scenario, profile, layer, temperature)
                    try {
                        execute(scope, measured = false, samples, supplemental)
                    } finally {
                        closeScope(scope)
                    }
                }
                repeat(configuration.measurementIterations) {
                    val scope = createScope(scenario, profile, layer, temperature)
                    try {
                        execute(scope, measured = true, samples, supplemental)
                    } finally {
                        closeScope(scope)
                    }
                }
            }
        }
        val aggregate = BenchmarkStatistics.aggregate(samples).associateBy(BenchmarkMetricValue::metric).toMutableMap()
        supplemental.forEach { (metric, values) ->
            aggregate[metric] = BenchmarkMetricValue(metric, BenchmarkStatistics.percentile(values, 0.99))
        }
        return BenchmarkCaseResult(
            id = BenchmarkCaseId(scenario.id, profile.name, layer, temperature),
            status = scenario.status,
            metrics = aggregate.values.sortedBy { it.metric.ordinal },
            samples = samples,
            supplementalSamples = supplemental.mapValues { it.value.toList() },
            budgets = SerializableBenchmarkBudgets.from(scenario.budgets),
        )
    }

    private suspend fun createScope(
        scenario: BenchmarkScenario,
        profile: BenchmarkProfile,
        layer: BenchmarkLayer,
        temperature: BenchmarkTemperature,
    ): BenchmarkExecutionScope {
        val scope = BenchmarkExecutionScope(scenario, profile, layer, temperature)
        withContext(BenchmarkExecution.context(scope)) {
            scenario.fixtures.forEach { declaration -> installFixture(scope, declaration) }
        }
        return scope
    }

    private suspend fun execute(
        scope: BenchmarkExecutionScope,
        measured: Boolean,
        samples: MutableList<BenchmarkSample>,
        supplemental: MutableMap<BenchmarkMetric, MutableList<Double>>,
    ) {
        withContext(BenchmarkExecution.context(scope)) {
            scope.clearRecordedMetrics()
            scope.scenario.setup(scope)
            val allocationBefore = allocatedBytes()
            val started = System.nanoTime()
            val result = scope.scenario.measure(scope)
            scope.consume(result)
            val duration = System.nanoTime() - started
            val allocationAfter = allocatedBytes()
            scope.scenario.verify(scope, result)
            scope.scenario.cleanup(scope)
            if (measured) {
                samples += BenchmarkSample(
                    durationNanos = duration,
                    allocatedBytes = if (allocationBefore == null || allocationAfter == null) {
                        null
                    } else {
                        (allocationAfter - allocationBefore).coerceAtLeast(0L)
                    },
                )
                scope.recordedMetrics().forEach { (metric, value) ->
                    supplemental.getOrPut(metric) { mutableListOf() } += value
                }
            }
        }
    }

    private suspend fun closeScope(scope: BenchmarkExecutionScope) {
        withContext(BenchmarkExecution.context(scope)) {
            scope.scenario.fixtures.asReversed().forEach { declaration -> closeFixture(scope, declaration) }
        }
    }

    private suspend fun <T : Any> installFixture(
        scope: BenchmarkExecutionScope,
        declaration: FixtureDeclaration<T>,
    ) {
        scope.installFixture(declaration.name, declaration.create(scope))
    }

    private suspend fun <T : Any> closeFixture(
        scope: BenchmarkExecutionScope,
        declaration: FixtureDeclaration<T>,
    ) {
        declaration.close(scope, scope.removeFixture(declaration.name))
    }

    private fun allocatedBytes(): Long? {
        if (!configuration.collectAllocation) return null
        val bean = ManagementFactory.getThreadMXBean() as? ThreadMXBean ?: return null
        if (!bean.isThreadAllocatedMemorySupported) return null
        if (!bean.isThreadAllocatedMemoryEnabled) bean.isThreadAllocatedMemoryEnabled = true
        return bean.getThreadAllocatedBytes(Thread.currentThread().threadId())
    }
}
