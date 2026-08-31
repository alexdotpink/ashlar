package dev.placeholder.framework.benchmarks

import kotlin.math.max
import kotlin.random.Random
import kotlinx.serialization.Serializable

/** Policy for paired statistical comparison. */
@Serializable
public data class BenchmarkComparisonConfiguration(
    public val confidence: Double = 0.95,
    public val resamples: Int = 4_000,
    public val noiseFloorFraction: Double = 0.01,
) {
    init {
        require(confidence in 0.5..<1.0) { "Comparison confidence must be at least 0.5 and below 1.0" }
        require(resamples >= 100) { "Comparison needs at least 100 resamples" }
        require(noiseFloorFraction >= 0.0 && noiseFloorFraction.isFinite()) {
            "Comparison noise floor must be finite and non-negative"
        }
    }
}

/** Overall outcome of a benchmark comparison. */
@Serializable
public enum class BenchmarkGateStatus { PASSED, FAILED, INCONCLUSIVE, INCOMPATIBLE, MISSING_CASES }

/** Outcome of one relative or absolute budget. */
@Serializable
public enum class BudgetEvaluationStatus { PASSED, FAILED, INCONCLUSIVE, NOT_ENFORCED }

/** Kind of source-controlled budget being evaluated. */
@Serializable
public enum class BudgetKind { RELATIVE, ABSOLUTE }

/** Statistical comparison for one metric. */
@Serializable
public data class BenchmarkMetricComparison(
    public val metric: BenchmarkMetric,
    public val baseline: Double,
    public val candidate: Double,
    public val regressionFraction: Double,
    public val confidenceLower: Double,
    public val confidenceUpper: Double,
)

/** One budget decision with enough evidence to explain the result. */
@Serializable
public data class BudgetEvaluation(
    public val kind: BudgetKind,
    public val metric: BenchmarkMetric,
    public val limit: Double,
    public val status: BudgetEvaluationStatus,
    public val observed: Double,
    public val confidenceLower: Double? = null,
    public val confidenceUpper: Double? = null,
    public val reason: String,
)

/** Comparison and budget decisions for one matching benchmark case. */
@Serializable
public data class BenchmarkCaseComparison(
    public val id: BenchmarkCaseId,
    public val status: PerformanceContractStatus,
    public val metrics: List<BenchmarkMetricComparison>,
    public val evaluations: List<BudgetEvaluation>,
) {
    public val failed: Boolean
        get() = evaluations.any { it.status == BudgetEvaluationStatus.FAILED }

    public val inconclusive: Boolean
        get() = evaluations.any { it.status == BudgetEvaluationStatus.INCONCLUSIVE }
}

/** Complete same-environment comparison between a baseline and candidate revision. */
@Serializable
public data class BenchmarkComparisonReport(
    public val schemaVersion: Int = 1,
    public val baselineRevision: String,
    public val candidateRevision: String,
    public val environmentCompatible: Boolean,
    public val missingBaselineCases: List<BenchmarkCaseId>,
    public val missingCandidateCases: List<BenchmarkCaseId>,
    public val cases: List<BenchmarkCaseComparison>,
    public val status: BenchmarkGateStatus,
)

/** Compares paired runs and applies their embedded source-controlled budgets. */
public class BenchmarkComparator(
    private val configuration: BenchmarkComparisonConfiguration = BenchmarkComparisonConfiguration(),
) {
    public fun compare(
        baseline: BenchmarkRunResult,
        candidate: BenchmarkRunResult,
    ): BenchmarkComparisonReport {
        val compatible = baseline.environment.compatibilityKey() == candidate.environment.compatibilityKey()
        val baselineCases = baseline.cases.associateBy(BenchmarkCaseResult::id)
        val candidateCases = candidate.cases.associateBy(BenchmarkCaseResult::id)
        val missingBaseline = (candidateCases.keys - baselineCases.keys).sortedBy(::caseSortKey)
        val missingCandidate = (baselineCases.keys - candidateCases.keys).sortedBy(::caseSortKey)
        val comparisons = if (compatible) {
            (baselineCases.keys intersect candidateCases.keys)
                .sortedBy(::caseSortKey)
                .map { id -> compareCase(requireNotNull(baselineCases[id]), requireNotNull(candidateCases[id])) }
        } else {
            emptyList()
        }
        val status = when {
            !compatible -> BenchmarkGateStatus.INCOMPATIBLE
            missingBaseline.isNotEmpty() || missingCandidate.isNotEmpty() -> BenchmarkGateStatus.MISSING_CASES
            comparisons.any(BenchmarkCaseComparison::failed) -> BenchmarkGateStatus.FAILED
            comparisons.any(BenchmarkCaseComparison::inconclusive) -> BenchmarkGateStatus.INCONCLUSIVE
            else -> BenchmarkGateStatus.PASSED
        }
        return BenchmarkComparisonReport(
            baselineRevision = baseline.revision,
            candidateRevision = candidate.revision,
            environmentCompatible = compatible,
            missingBaselineCases = missingBaseline,
            missingCandidateCases = missingCandidate,
            cases = comparisons,
            status = status,
        )
    }

    private fun compareCase(
        baseline: BenchmarkCaseResult,
        candidate: BenchmarkCaseResult,
    ): BenchmarkCaseComparison {
        val commonMetrics = baseline.metrics.map(BenchmarkMetricValue::metric).toSet() intersect
            candidate.metrics.map(BenchmarkMetricValue::metric).toSet()
        val comparisons = commonMetrics.sortedBy(BenchmarkMetric::ordinal).map { metric ->
            compareMetric(baseline, candidate, metric)
        }
        val comparisonByMetric = comparisons.associateBy(BenchmarkMetricComparison::metric)
        val budgets = candidate.budgets.model()
        val evaluations = buildList {
            budgets.relative.forEach { budget ->
                val metric = comparisonByMetric[budget.metric]
                add(evaluateRelative(candidate.status, budget, metric))
            }
            budgets.absolute.forEach { budget ->
                add(evaluateAbsolute(candidate, budget))
            }
        }
        return BenchmarkCaseComparison(candidate.id, candidate.status, comparisons, evaluations)
    }

    private fun compareMetric(
        baseline: BenchmarkCaseResult,
        candidate: BenchmarkCaseResult,
        metric: BenchmarkMetric,
    ): BenchmarkMetricComparison {
        val baselineValue = requireNotNull(baseline.metric(metric))
        val candidateValue = requireNotNull(candidate.metric(metric))
        val regression = regressionFraction(metric, baselineValue, candidateValue)
        val bootstrap = bootstrapRegression(metric, rawValues(baseline, metric), rawValues(candidate, metric))
        return BenchmarkMetricComparison(
            metric = metric,
            baseline = baselineValue,
            candidate = candidateValue,
            regressionFraction = regression,
            confidenceLower = bootstrap.first,
            confidenceUpper = bootstrap.second,
        )
    }

    private fun evaluateRelative(
        status: PerformanceContractStatus,
        budget: RelativeMetricBudget,
        comparison: BenchmarkMetricComparison?,
    ): BudgetEvaluation {
        if (status == PerformanceContractStatus.EXPLORATORY) {
            return BudgetEvaluation(
                BudgetKind.RELATIVE,
                budget.metric,
                budget.limit.fraction,
                BudgetEvaluationStatus.NOT_ENFORCED,
                comparison?.regressionFraction ?: 0.0,
                reason = "Exploratory contracts do not gate",
            )
        }
        if (comparison == null) {
            return BudgetEvaluation(
                BudgetKind.RELATIVE,
                budget.metric,
                budget.limit.fraction,
                BudgetEvaluationStatus.INCONCLUSIVE,
                0.0,
                reason = "The metric was not collected by both runs",
            )
        }
        val effectiveLimit = max(budget.limit.fraction, configuration.noiseFloorFraction)
        val decision = when {
            comparison.confidenceLower > effectiveLimit -> BudgetEvaluationStatus.FAILED
            comparison.regressionFraction > effectiveLimit -> BudgetEvaluationStatus.INCONCLUSIVE
            else -> BudgetEvaluationStatus.PASSED
        }
        return BudgetEvaluation(
            BudgetKind.RELATIVE,
            budget.metric,
            budget.limit.fraction,
            decision,
            comparison.regressionFraction,
            comparison.confidenceLower,
            comparison.confidenceUpper,
            when (decision) {
                BudgetEvaluationStatus.FAILED -> "Regression exceeds the budget with the required confidence"
                BudgetEvaluationStatus.INCONCLUSIVE -> "Point estimate exceeds the budget but confidence is insufficient"
                BudgetEvaluationStatus.PASSED -> "Regression stays within the effective budget"
                BudgetEvaluationStatus.NOT_ENFORCED -> error("Handled above")
            },
        )
    }

    private fun evaluateAbsolute(
        candidate: BenchmarkCaseResult,
        budget: AbsoluteMetricBudget,
    ): BudgetEvaluation {
        val observed = candidate.metric(budget.metric)
        if (candidate.status != PerformanceContractStatus.CONTRACTUAL) {
            return BudgetEvaluation(
                BudgetKind.ABSOLUTE,
                budget.metric,
                budget.limit,
                BudgetEvaluationStatus.NOT_ENFORCED,
                observed ?: 0.0,
                reason = "Only contractual scenarios enforce absolute ceilings",
            )
        }
        if (observed == null) {
            return BudgetEvaluation(
                BudgetKind.ABSOLUTE,
                budget.metric,
                budget.limit,
                BudgetEvaluationStatus.INCONCLUSIVE,
                0.0,
                reason = "The candidate did not collect this metric",
            )
        }
        val raw = rawValues(candidate, budget.metric)
        val bounds = bootstrapStatistic(budget.metric, raw)
        val failed = when (budget.metric.direction) {
            MetricDirection.LOWER_IS_BETTER -> bounds.first > budget.limit
            MetricDirection.HIGHER_IS_BETTER -> bounds.second < budget.limit
        }
        val pointViolates = when (budget.metric.direction) {
            MetricDirection.LOWER_IS_BETTER -> observed > budget.limit
            MetricDirection.HIGHER_IS_BETTER -> observed < budget.limit
        }
        val decision = when {
            failed -> BudgetEvaluationStatus.FAILED
            pointViolates -> BudgetEvaluationStatus.INCONCLUSIVE
            else -> BudgetEvaluationStatus.PASSED
        }
        return BudgetEvaluation(
            BudgetKind.ABSOLUTE,
            budget.metric,
            budget.limit,
            decision,
            observed,
            bounds.first,
            bounds.second,
            when (decision) {
                BudgetEvaluationStatus.FAILED -> "Absolute limit is violated with the required confidence"
                BudgetEvaluationStatus.INCONCLUSIVE -> "Point estimate violates the limit but confidence is insufficient"
                BudgetEvaluationStatus.PASSED -> "Absolute limit is satisfied"
                BudgetEvaluationStatus.NOT_ENFORCED -> error("Handled above")
            },
        )
    }

    private fun bootstrapRegression(
        metric: BenchmarkMetric,
        baseline: List<Double>,
        candidate: List<Double>,
    ): Pair<Double, Double> {
        if (baseline.size < 2 || candidate.size < 2) {
            val point = regressionFraction(metric, statistic(metric, baseline), statistic(metric, candidate))
            return point to point
        }
        val random = Random((metric.ordinal + 1) * 31 + baseline.size * 17 + candidate.size)
        val values = DoubleArray(configuration.resamples) {
            val baseSample = List(baseline.size) { baseline[random.nextInt(baseline.size)] }
            val candidateSample = List(candidate.size) { candidate[random.nextInt(candidate.size)] }
            regressionFraction(metric, statistic(metric, baseSample), statistic(metric, candidateSample))
        }.sorted()
        return confidenceBounds(values)
    }

    private fun bootstrapStatistic(metric: BenchmarkMetric, values: List<Double>): Pair<Double, Double> {
        if (values.size < 2) {
            val point = statistic(metric, values)
            return point to point
        }
        val random = Random((metric.ordinal + 1) * 47 + values.size)
        val estimates = DoubleArray(configuration.resamples) {
            statistic(metric, List(values.size) { values[random.nextInt(values.size)] })
        }.sorted()
        return confidenceBounds(estimates)
    }

    private fun confidenceBounds(values: List<Double>): Pair<Double, Double> {
        val tail = (1.0 - configuration.confidence) / 2.0
        return BenchmarkStatistics.percentile(values, tail) to
            BenchmarkStatistics.percentile(values, 1.0 - tail)
    }

    private fun statistic(metric: BenchmarkMetric, values: List<Double>): Double {
        require(values.isNotEmpty()) { "A benchmark metric has no raw observations" }
        return when (metric) {
            BenchmarkMetric.LATENCY_MEAN,
            BenchmarkMetric.THROUGHPUT,
            BenchmarkMetric.ALLOCATION,
            -> if (metric == BenchmarkMetric.THROUGHPUT) {
                values.size * 1_000_000_000.0 / values.sum()
            } else {
                values.average()
            }
            BenchmarkMetric.LATENCY_P50 -> BenchmarkStatistics.percentile(values, 0.50)
            BenchmarkMetric.LATENCY_P95 -> BenchmarkStatistics.percentile(values, 0.95)
            else -> BenchmarkStatistics.percentile(values, 0.99)
        }
    }

    private fun rawValues(result: BenchmarkCaseResult, metric: BenchmarkMetric): List<Double> = when (metric) {
        BenchmarkMetric.LATENCY_MEAN,
        BenchmarkMetric.LATENCY_P50,
        BenchmarkMetric.LATENCY_P95,
        BenchmarkMetric.LATENCY_P99,
        BenchmarkMetric.THROUGHPUT,
        -> result.samples.map { it.durationNanos.toDouble() }
        BenchmarkMetric.ALLOCATION -> result.samples.mapNotNull { it.allocatedBytes?.toDouble() }
        else -> result.supplementalSamples[metric].orEmpty()
    }.ifEmpty { listOf(requireNotNull(result.metric(metric))) }

    private fun regressionFraction(metric: BenchmarkMetric, baseline: Double, candidate: Double): Double {
        if (baseline == 0.0 || candidate == 0.0) {
            return when {
                baseline == candidate -> 0.0
                metric.direction == MetricDirection.LOWER_IS_BETTER && candidate > baseline -> Double.MAX_VALUE
                metric.direction == MetricDirection.HIGHER_IS_BETTER && candidate < baseline -> Double.MAX_VALUE
                else -> -1.0
            }
        }
        return when (metric.direction) {
            MetricDirection.LOWER_IS_BETTER -> candidate / baseline - 1.0
            MetricDirection.HIGHER_IS_BETTER -> baseline / candidate - 1.0
        }
    }

    private fun caseSortKey(id: BenchmarkCaseId): String =
        "${id.scenario.value}:${id.profile}:${id.layer}:${id.temperature}"
}
