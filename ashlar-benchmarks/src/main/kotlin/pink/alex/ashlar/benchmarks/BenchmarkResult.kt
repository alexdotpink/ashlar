package pink.alex.ashlar.benchmarks

import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.Serializable

/** Complete fingerprint attached to one benchmark run. */
@Serializable
public data class MeasurementEnvironment(
    public val environmentId: String,
    public val operatingSystem: String,
    public val architecture: String,
    public val availableProcessors: Int,
    public val cpuModel: String,
    public val jvmVendor: String,
    public val jvmVersion: String,
    public val jvmArguments: List<String>,
    public val garbageCollectors: List<String>,
    public val kotlinVersion: String,
    public val ashlarVersion: String,
    public val platform: String? = null,
    public val platformVersion: String? = null,
    public val attributes: Map<String, String> = emptyMap(),
) {
    init {
        require(environmentId.isNotBlank()) { "Measurement environment id cannot be blank" }
        require(availableProcessors > 0) { "Measurement environment needs at least one processor" }
    }

    /** Fields which must match before two runs may produce an authoritative comparison. */
    public fun compatibilityKey(): String = listOf(
        environmentId,
        operatingSystem,
        architecture,
        availableProcessors.toString(),
        cpuModel,
        jvmVendor,
        jvmVersion,
        jvmArguments.sorted().joinToString("\u0000"),
        garbageCollectors.sorted().joinToString("\u0000"),
        kotlinVersion,
        ashlarVersion,
        platform.orEmpty(),
        platformVersion.orEmpty(),
        attributes.toSortedMap().entries.joinToString("\u0000") { "${it.key}=${it.value}" },
    ).joinToString("\u0001")

    public companion object {
        /** Captures the current JVM and host without claiming it is authoritative hardware. */
        public fun local(
            ashlarVersion: String,
            environmentId: String = "local",
            platform: String? = null,
            platformVersion: String? = null,
            attributes: Map<String, String> = emptyMap(),
        ): MeasurementEnvironment = MeasurementEnvironment(
            environmentId = environmentId,
            operatingSystem = "${System.getProperty("os.name")} ${System.getProperty("os.version")}",
            architecture = System.getProperty("os.arch"),
            availableProcessors = Runtime.getRuntime().availableProcessors(),
            cpuModel = detectCpuModel(),
            jvmVendor = System.getProperty("java.vendor"),
            jvmVersion = System.getProperty("java.runtime.version"),
            jvmArguments = ManagementFactory.getRuntimeMXBean().inputArguments
                .filterNot { argument -> argument.startsWith("-Dashlar.benchmark.") },
            garbageCollectors = ManagementFactory.getGarbageCollectorMXBeans().map { it.name }.sorted(),
            kotlinVersion = KotlinVersion.CURRENT.toString(),
            ashlarVersion = ashlarVersion,
            platform = platform,
            platformVersion = platformVersion,
            attributes = attributes.toSortedMap(),
        )

        private fun detectCpuModel(): String = runCatching {
            val cpuInfo = Path.of("/proc/cpuinfo")
            if (!Files.isRegularFile(cpuInfo)) return@runCatching "unknown"
            Files.readAllLines(cpuInfo)
                .firstOrNull { line -> line.startsWith("model name") }
                ?.substringAfter(':')
                ?.trim()
                .orEmpty()
                .ifBlank { "unknown" }
        }.getOrDefault("unknown")
    }
}

/** Runner policy used for one local or authoritative execution. */
@Serializable
public data class BenchmarkRunConfiguration(
    public val warmupIterations: Int = 5,
    public val measurementIterations: Int = 20,
    public val forks: Int = 3,
    public val warmupTimeMillis: Int = 250,
    public val measurementTimeMillis: Int = 500,
    public val collectAllocation: Boolean = true,
    public val authoritative: Boolean = false,
) {
    init {
        require(warmupIterations >= 0) { "Warmup iterations cannot be negative" }
        require(measurementIterations > 0) { "Measurement iterations must be positive" }
        require(forks > 0) { "Benchmark forks must be positive" }
        require(warmupTimeMillis > 0) { "Warmup time must be positive" }
        require(measurementTimeMillis > 0) { "Measurement time must be positive" }
    }
}

/** Identity of one scenario/profile/layer/temperature result. */
@Serializable
public data class BenchmarkCaseId(
    public val scenario: BenchmarkId,
    public val profile: String,
    public val layer: BenchmarkLayer,
    public val temperature: BenchmarkTemperature,
)

/** Aggregated and raw evidence for one benchmark case. */
@Serializable
public data class BenchmarkCaseResult(
    public val id: BenchmarkCaseId,
    public val status: PerformanceContractStatus,
    public val metrics: List<BenchmarkMetricValue>,
    public val samples: List<BenchmarkSample>,
    public val supplementalSamples: Map<BenchmarkMetric, List<Double>> = emptyMap(),
    public val budgets: SerializableBenchmarkBudgets = SerializableBenchmarkBudgets(),
) {
    init {
        require(samples.isNotEmpty()) { "Benchmark case '${id.scenario}' has no samples" }
        require(metrics.map(BenchmarkMetricValue::metric).distinct().size == metrics.size) {
            "Benchmark case '${id.scenario}' has duplicate aggregate metrics"
        }
    }

    /** Returns one aggregate metric or null when the runner did not collect it. */
    public fun metric(metric: BenchmarkMetric): Double? = metrics.firstOrNull { it.metric == metric }?.value
}

/** Serializable form of source-level budgets embedded in a result artifact. */
@Serializable
public data class SerializableBenchmarkBudgets(
    public val relative: Map<BenchmarkMetric, Double> = emptyMap(),
    public val absolute: Map<BenchmarkMetric, Double> = emptyMap(),
) {
    internal fun model(): BenchmarkBudgets = BenchmarkBudgets(
        relative.map { (metric, fraction) -> RelativeMetricBudget(metric, RegressionLimit(fraction)) },
        absolute.map { (metric, limit) -> AbsoluteMetricBudget(metric, limit) },
    )

    internal companion object {
        fun from(model: BenchmarkBudgets): SerializableBenchmarkBudgets = SerializableBenchmarkBudgets(
            relative = model.relative.associate { it.metric to it.limit.fraction },
            absolute = model.absolute.associate { it.metric to it.limit },
        )
    }
}

/** One complete run suitable for JSON persistence and later paired comparison. */
@Serializable
public data class BenchmarkRunResult(
    public val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    public val runId: String = UUID.randomUUID().toString(),
    public val revision: String,
    public val startedAtEpochMillis: Long = Instant.now().toEpochMilli(),
    public val environment: MeasurementEnvironment,
    public val configuration: BenchmarkRunConfiguration,
    public val cases: List<BenchmarkCaseResult>,
) {
    init {
        require(schemaVersion == CURRENT_SCHEMA_VERSION) { "Unsupported benchmark result schema $schemaVersion" }
        require(revision.isNotBlank()) { "Benchmark revision cannot be blank" }
        require(cases.map(BenchmarkCaseResult::id).distinct().size == cases.size) {
            "Benchmark run contains duplicate case ids"
        }
    }

    public companion object {
        public const val CURRENT_SCHEMA_VERSION: Int = 2
    }
}
