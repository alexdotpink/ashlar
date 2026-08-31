package dev.placeholder.framework.benchmarks

import kotlin.math.ceil

/** Deterministic aggregate calculations shared by runners and reports. */
public object BenchmarkStatistics {
    /** Computes standard core metrics from raw operation samples. */
    public fun aggregate(samples: List<BenchmarkSample>): List<BenchmarkMetricValue> {
        require(samples.isNotEmpty()) { "Cannot aggregate an empty sample set" }
        val durations = samples.map { it.durationNanos.toDouble() }.sorted()
        val totalNanos = durations.sum()
        val allocated = samples.mapNotNull(BenchmarkSample::allocatedBytes)
        return buildList {
            add(BenchmarkMetricValue(BenchmarkMetric.LATENCY_MEAN, durations.average()))
            add(BenchmarkMetricValue(BenchmarkMetric.LATENCY_P50, percentile(durations, 0.50)))
            add(BenchmarkMetricValue(BenchmarkMetric.LATENCY_P95, percentile(durations, 0.95)))
            add(BenchmarkMetricValue(BenchmarkMetric.LATENCY_P99, percentile(durations, 0.99)))
            add(
                BenchmarkMetricValue(
                    BenchmarkMetric.THROUGHPUT,
                    if (totalNanos == 0.0) Double.MAX_VALUE else samples.size * 1_000_000_000.0 / totalNanos,
                ),
            )
            if (allocated.size == samples.size) {
                add(BenchmarkMetricValue(BenchmarkMetric.ALLOCATION, allocated.average()))
            }
        }
    }

    /** Nearest-rank percentile over an already sorted or unsorted value list. */
    public fun percentile(values: List<Double>, quantile: Double): Double {
        require(values.isNotEmpty()) { "Cannot compute a percentile of no values" }
        require(quantile in 0.0..1.0) { "Quantile must be from zero through one" }
        val sorted = values.sorted()
        val rank = ceil(quantile * sorted.size).toInt().coerceIn(1, sorted.size)
        return sorted[rank - 1]
    }
}
