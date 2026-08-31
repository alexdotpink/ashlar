package dev.placeholder.framework.benchmarks

import kotlin.time.Duration

/** Maximum allowed relative change expressed as a fraction, where `0.05` is five percent. */
public data class RegressionLimit(public val fraction: Double) {
    init {
        require(fraction >= 0.0 && fraction.isFinite()) { "Regression limit must be finite and non-negative" }
    }
}

/** Absolute byte count used by allocation and retained-size budgets. */
public data class ByteLimit(public val bytes: Long) {
    init {
        require(bytes >= 0) { "Byte limit cannot be negative" }
    }
}

/** Creates a relative regression limit. */
public val Int.percent: RegressionLimit
    get() = RegressionLimit(this / 100.0)

/** Creates an allocation or size limit in bytes. */
public val Int.bytesPerOperation: ByteLimit
    get() = ByteLimit(toLong())

/** Creates an allocation or size limit in kibibytes. */
public val Int.kibibytesPerOperation: ByteLimit
    get() = ByteLimit(toLong() * 1024L)

/** Relative allowance for one metric in a same-worker comparison. */
public data class RelativeMetricBudget(
    public val metric: BenchmarkMetric,
    public val limit: RegressionLimit,
)

/** Absolute ceiling for one metric in the canonical environment. */
public data class AbsoluteMetricBudget(
    public val metric: BenchmarkMetric,
    public val limit: Double,
) {
    init {
        require(limit >= 0.0 && limit.isFinite()) { "Absolute budget must be finite and non-negative" }
    }
}

/** Relative pull-request budgets and absolute release ceilings for one scenario. */
public data class BenchmarkBudgets(
    public val relative: List<RelativeMetricBudget> = emptyList(),
    public val absolute: List<AbsoluteMetricBudget> = emptyList(),
) {
    init {
        require(relative.map(RelativeMetricBudget::metric).distinct().size == relative.size) {
            "A metric can have only one relative budget"
        }
        require(absolute.map(AbsoluteMetricBudget::metric).distinct().size == absolute.size) {
            "A metric can have only one absolute budget"
        }
    }
}

/** Builds source-visible performance budgets. */
public class BenchmarkBudgetsBuilder internal constructor() {
    private val relative: MutableList<RelativeMetricBudget> = mutableListOf()
    private val absolute: MutableList<AbsoluteMetricBudget> = mutableListOf()

    public val mean: MetricBudgetSelector = MetricBudgetSelector(BenchmarkMetric.LATENCY_MEAN, relative, absolute)
    public val p50: MetricBudgetSelector = MetricBudgetSelector(BenchmarkMetric.LATENCY_P50, relative, absolute)
    public val p95: MetricBudgetSelector = MetricBudgetSelector(BenchmarkMetric.LATENCY_P95, relative, absolute)
    public val p99: MetricBudgetSelector = MetricBudgetSelector(BenchmarkMetric.LATENCY_P99, relative, absolute)
    public val throughput: MetricBudgetSelector = MetricBudgetSelector(BenchmarkMetric.THROUGHPUT, relative, absolute)
    public val allocation: MetricBudgetSelector = MetricBudgetSelector(BenchmarkMetric.ALLOCATION, relative, absolute)
    public val retainedHeap: MetricBudgetSelector = MetricBudgetSelector(BenchmarkMetric.RETAINED_HEAP, relative, absolute)
    public val nativeCallback: MetricBudgetSelector = MetricBudgetSelector(BenchmarkMetric.NATIVE_CALLBACK, relative, absolute)
    public val admission: MetricBudgetSelector = MetricBudgetSelector(BenchmarkMetric.ADMISSION, relative, absolute)
    public val scheduling: MetricBudgetSelector = MetricBudgetSelector(BenchmarkMetric.SCHEDULING, relative, absolute)
    public val endToEnd: MetricBudgetSelector = MetricBudgetSelector(BenchmarkMetric.END_TO_END, relative, absolute)
    public val tickP99: MetricBudgetSelector = MetricBudgetSelector(BenchmarkMetric.TICK_P99, relative, absolute)
    public val clientFrameP99: MetricBudgetSelector =
        MetricBudgetSelector(BenchmarkMetric.CLIENT_FRAME_P99, relative, absolute)
    public val packetBytes: MetricBudgetSelector = MetricBudgetSelector(BenchmarkMetric.PACKET_BYTES, relative, absolute)
    public val artifactBytes: MetricBudgetSelector = MetricBudgetSelector(BenchmarkMetric.ARTIFACT_BYTES, relative, absolute)

    internal fun build(): BenchmarkBudgets = BenchmarkBudgets(relative.toList(), absolute.toList())
}

/** Selects relative or absolute limits for one metric. */
public class MetricBudgetSelector internal constructor(
    public val metric: BenchmarkMetric,
    private val relative: MutableList<RelativeMetricBudget>,
    private val absolute: MutableList<AbsoluteMetricBudget>,
) {
    /** Selects the relative regression budget for this metric. */
    public val regression: RelativeBudgetSelector
        get() = RelativeBudgetSelector(metric, relative)

    /** Adds an absolute duration ceiling. */
    public infix fun atMost(limit: Duration) {
        require(metric.unit == BenchmarkUnit.NANOSECONDS) { "$metric does not use a duration unit" }
        require(metric.direction == MetricDirection.LOWER_IS_BETTER) { "$metric needs an atLeast budget" }
        absolute += AbsoluteMetricBudget(metric, limit.inWholeNanoseconds.toDouble())
    }

    /** Adds an absolute byte ceiling. */
    public infix fun atMost(limit: ByteLimit) {
        require(metric.unit == BenchmarkUnit.BYTES || metric.unit == BenchmarkUnit.BYTES_PER_OPERATION) {
            "$metric does not use a byte unit"
        }
        require(metric.direction == MetricDirection.LOWER_IS_BETTER) { "$metric needs an atLeast budget" }
        absolute += AbsoluteMetricBudget(metric, limit.bytes.toDouble())
    }

    /** Adds an absolute numeric ceiling in the metric's declared unit. */
    public infix fun atMost(maximum: Double) {
        require(metric.direction == MetricDirection.LOWER_IS_BETTER) { "$metric needs an atLeast budget" }
        absolute += AbsoluteMetricBudget(metric, maximum)
    }

    /** Adds an absolute minimum for a higher-is-better metric such as throughput. */
    public infix fun atLeast(minimum: Double) {
        require(metric.direction == MetricDirection.HIGHER_IS_BETTER) { "$metric needs an atMost budget" }
        absolute += AbsoluteMetricBudget(metric, minimum)
    }
}

/** Adds the relative budget selected through [MetricBudgetSelector.regression]. */
public class RelativeBudgetSelector internal constructor(
    private val metric: BenchmarkMetric,
    private val relative: MutableList<RelativeMetricBudget>,
) {
    public infix fun atMost(limit: RegressionLimit) {
        relative += RelativeMetricBudget(metric, limit)
    }
}
