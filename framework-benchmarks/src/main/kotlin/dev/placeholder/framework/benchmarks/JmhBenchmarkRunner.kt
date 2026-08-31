package dev.placeholder.framework.benchmarks

import dev.placeholder.framework.benchmarks.internal.JmhScenarioBenchmark
import java.io.File
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import org.openjdk.jmh.profile.GCProfiler
import org.openjdk.jmh.results.RunResult
import org.openjdk.jmh.runner.Runner
import org.openjdk.jmh.runner.options.OptionsBuilder
import org.openjdk.jmh.runner.options.TimeValue

/** Runs isolated scenarios through OpenJDK JMH in forked JVMs. */
public class JmhBenchmarkRunner(
    private val environment: MeasurementEnvironment,
    private val configuration: BenchmarkRunConfiguration = BenchmarkRunConfiguration(),
) {
    /** Runs selected JVM cases discovered from [classDirectories]. */
    public fun run(
        suites: List<BenchmarkSuite>,
        classDirectories: List<Path>,
        revision: String,
        select: (BenchmarkCaseId) -> Boolean = { true },
    ): BenchmarkRunResult {
        val cases = suites.flatMap(BenchmarkSuite::scenarios).flatMap { scenario ->
            scenario.profiles.flatMap { profile ->
                scenario.layers.filter { it == BenchmarkLayer.JVM || it == BenchmarkLayer.CALIBRATION }
                    .flatMap { layer ->
                        scenario.temperatures.mapNotNull { temperature ->
                            val id = BenchmarkCaseId(scenario.id, profile.name, layer, temperature)
                            id.takeIf(select)?.let {
                                runCase(scenario, profile, layer, temperature, classDirectories)
                            }
                        }
                    }
            }
        }
        require(cases.isNotEmpty()) { "JMH benchmark selection produced no cases" }
        return BenchmarkRunResult(
            revision = revision,
            environment = environment,
            configuration = configuration,
            cases = cases,
        )
    }

    private fun runCase(
        scenario: BenchmarkScenario,
        profile: BenchmarkProfile,
        layer: BenchmarkLayer,
        temperature: BenchmarkTemperature,
        classDirectories: List<Path>,
    ): BenchmarkCaseResult {
        val properties = mapOf(
            "framework.benchmark.classDirs" to classDirectories.joinToString(File.pathSeparator),
            "framework.benchmark.scenario" to scenario.id.value,
            "framework.benchmark.profile" to profile.name,
            "framework.benchmark.layer" to layer.name,
            "framework.benchmark.temperature" to temperature.name,
        )
        val options = OptionsBuilder()
            .include("^${Regex.escape(JmhScenarioBenchmark::class.java.name)}\\.measure$")
            .warmupIterations(configuration.warmupIterations)
            .warmupTime(TimeValue.milliseconds(configuration.warmupTimeMillis.toLong()))
            .measurementIterations(configuration.measurementIterations)
            .measurementTime(TimeValue.milliseconds(configuration.measurementTimeMillis.toLong()))
            .forks(configuration.forks)
            .timeUnit(TimeUnit.NANOSECONDS)
            .shouldFailOnError(true)
            .jvmArgsAppend(*properties.map { (key, value) -> "-D$key=$value" }.toTypedArray())
            .apply {
                if (configuration.collectAllocation) addProfiler(GCProfiler::class.java)
            }
            .build()
        val result = Runner(options).runSingle()
        return result.toCaseResult(scenario, profile, layer, temperature)
    }

    private fun RunResult.toCaseResult(
        scenario: BenchmarkScenario,
        profile: BenchmarkProfile,
        layer: BenchmarkLayer,
        temperature: BenchmarkTemperature,
    ): BenchmarkCaseResult {
        val statistics = primaryResult.statistics
        val retainedSamples = statistics.n.coerceIn(1L, MAX_RETAINED_SAMPLES.toLong()).toInt()
        val rawDurations = List(retainedSamples) { index ->
            statistics.getPercentile((index + 0.5) * 100.0 / retainedSamples)
                .takeIf(Double::isFinite)
                ?.coerceAtLeast(0.0)
                ?.toLong()
                ?: 0L
        }
        require(rawDurations.isNotEmpty()) { "JMH returned no raw samples for '${scenario.id}'" }
        val allocation = secondaryResults["gc.alloc.rate.norm"]?.score?.takeIf(Double::isFinite)?.toLong()
        val samples = rawDurations.map { duration -> BenchmarkSample(duration, allocation) }
        return BenchmarkCaseResult(
            id = BenchmarkCaseId(scenario.id, profile.name, layer, temperature),
            status = scenario.status,
            metrics = BenchmarkStatistics.aggregate(samples),
            samples = samples,
            budgets = SerializableBenchmarkBudgets.from(scenario.budgets),
        )
    }

    private companion object {
        const val MAX_RETAINED_SAMPLES: Int = 10_000
    }
}
