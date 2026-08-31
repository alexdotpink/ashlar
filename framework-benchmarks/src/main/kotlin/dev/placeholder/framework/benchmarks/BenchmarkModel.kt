package dev.placeholder.framework.benchmarks

import kotlinx.serialization.Serializable

/** Maturity of one source-controlled performance contract. */
@Serializable
public enum class PerformanceContractStatus {
    /** Collects evidence without failing a performance gate. */
    EXPLORATORY,

    /** Enforces relative regression budgets against a compatible baseline. */
    GUARDED,

    /** Enforces relative regression budgets and absolute release ceilings. */
    CONTRACTUAL,
}

/** Evidence layer responsible for one benchmark measurement. */
@Serializable
public enum class BenchmarkLayer {
    JVM,
    PAPER,
    FOLIA,
    CLIENT,
    BUILD,
    LOAD,
    SOAK,
    CALIBRATION,
}

/** Whether a measurement includes a fresh first use or warmed steady state. */
@Serializable
public enum class BenchmarkTemperature { COLD, WARM }

/** Direction in which a metric improves. */
@Serializable
public enum class MetricDirection { LOWER_IS_BETTER, HIGHER_IS_BETTER }

/** Stable unit carried by a metric value and its budgets. */
@Serializable
public enum class BenchmarkUnit {
    NANOSECONDS,
    OPERATIONS_PER_SECOND,
    BYTES_PER_OPERATION,
    BYTES,
    COUNT,
    PERCENT,
}

/** Metrics understood by the comparison and reporting engine. */
@Serializable
public enum class BenchmarkMetric(
    public val unit: BenchmarkUnit,
    public val direction: MetricDirection,
) {
    LATENCY_MEAN(BenchmarkUnit.NANOSECONDS, MetricDirection.LOWER_IS_BETTER),
    LATENCY_P50(BenchmarkUnit.NANOSECONDS, MetricDirection.LOWER_IS_BETTER),
    LATENCY_P95(BenchmarkUnit.NANOSECONDS, MetricDirection.LOWER_IS_BETTER),
    LATENCY_P99(BenchmarkUnit.NANOSECONDS, MetricDirection.LOWER_IS_BETTER),
    THROUGHPUT(BenchmarkUnit.OPERATIONS_PER_SECOND, MetricDirection.HIGHER_IS_BETTER),
    ALLOCATION(BenchmarkUnit.BYTES_PER_OPERATION, MetricDirection.LOWER_IS_BETTER),
    RETAINED_HEAP(BenchmarkUnit.BYTES, MetricDirection.LOWER_IS_BETTER),
    NATIVE_CALLBACK(BenchmarkUnit.NANOSECONDS, MetricDirection.LOWER_IS_BETTER),
    ADMISSION(BenchmarkUnit.NANOSECONDS, MetricDirection.LOWER_IS_BETTER),
    SCHEDULING(BenchmarkUnit.NANOSECONDS, MetricDirection.LOWER_IS_BETTER),
    END_TO_END(BenchmarkUnit.NANOSECONDS, MetricDirection.LOWER_IS_BETTER),
    TICK_P99(BenchmarkUnit.NANOSECONDS, MetricDirection.LOWER_IS_BETTER),
    CLIENT_FRAME_P99(BenchmarkUnit.NANOSECONDS, MetricDirection.LOWER_IS_BETTER),
    PACKET_BYTES(BenchmarkUnit.BYTES, MetricDirection.LOWER_IS_BETTER),
    DISK_BYTES(BenchmarkUnit.BYTES, MetricDirection.LOWER_IS_BETTER),
    GENERATED_BYTES(BenchmarkUnit.BYTES, MetricDirection.LOWER_IS_BETTER),
    ARTIFACT_BYTES(BenchmarkUnit.BYTES, MetricDirection.LOWER_IS_BETTER),
    GC_PAUSE(BenchmarkUnit.NANOSECONDS, MetricDirection.LOWER_IS_BETTER),
    DROPPED_VALUES(BenchmarkUnit.COUNT, MetricDirection.LOWER_IS_BETTER),
}

/** One named numeric workload used by a benchmark scenario. */
@Serializable
public data class BenchmarkProfile(
    public val name: String,
    public val parameters: Map<String, Long> = emptyMap(),
) {
    init {
        require(name.matches(PROFILE_NAME)) { "Invalid benchmark profile name '$name'" }
        require(parameters.keys.all { it.matches(PARAMETER_NAME) }) { "Invalid benchmark profile parameter" }
        require(parameters.values.all { it >= 0 }) { "Benchmark profile values cannot be negative" }
    }

    /** Returns one required numeric workload parameter. */
    public operator fun get(name: String): Long =
        parameters[name] ?: error("Profile '${this.name}' has no '$name' parameter")

    private companion object {
        val PROFILE_NAME: Regex = Regex("[a-z][a-z0-9-]*")
        val PARAMETER_NAME: Regex = Regex("[a-z][a-zA-Z0-9]*")
    }
}

/** Stable identity for one scenario in results and the performance catalogue. */
@Serializable
public data class BenchmarkId(public val value: String) {
    init {
        require(value.matches(ID_PATTERN)) {
            "Benchmark id '$value' must contain lowercase dot-separated segments"
        }
    }

    override fun toString(): String = value

    private companion object {
        val ID_PATTERN: Regex = Regex("[a-z][a-z0-9-]*(\\.[a-z][a-z0-9-]*)+")
    }
}

/** One observed metric in a benchmark result. */
@Serializable
public data class BenchmarkMetricValue(
    public val metric: BenchmarkMetric,
    public val value: Double,
) {
    public val unit: BenchmarkUnit
        get() = metric.unit

    init {
        require(value.isFinite() && value >= 0.0) { "Benchmark metric values must be finite and non-negative" }
    }
}

/** Raw observation retained for statistical comparison. */
@Serializable
public data class BenchmarkSample(
    public val durationNanos: Long,
    public val allocatedBytes: Long? = null,
) {
    init {
        require(durationNanos >= 0) { "Benchmark duration cannot be negative" }
        require(allocatedBytes == null || allocatedBytes >= 0) { "Allocated bytes cannot be negative" }
    }
}
