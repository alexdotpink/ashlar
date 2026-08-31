package dev.placeholder.framework.benchmarks

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.nanoseconds

class BenchmarkComparisonTest {
    @Test
    fun `confirmed relative regression fails a guarded contract`() {
        val budget = BenchmarkBudgetsBuilder().apply { p99.regression atMost 5.percent }.build()
        val baseline = run("main", case(100, PerformanceContractStatus.GUARDED, budget))
        val candidate = run("branch", case(120, PerformanceContractStatus.GUARDED, budget))

        val report = BenchmarkComparator().compare(baseline, candidate)

        assertEquals(BenchmarkGateStatus.FAILED, report.status)
        assertEquals(BudgetEvaluationStatus.FAILED, report.cases.single().evaluations.single().status)
        assertTrue(report.cases.single().metrics.first { it.metric == BenchmarkMetric.LATENCY_P99 }.regressionFraction > 0.19)
    }

    @Test
    fun `exploratory contract reports but does not enforce budgets`() {
        val budget = BenchmarkBudgetsBuilder().apply { p99.regression atMost 1.percent }.build()
        val report = BenchmarkComparator().compare(
            run("main", case(100, PerformanceContractStatus.EXPLORATORY, budget)),
            run("branch", case(500, PerformanceContractStatus.EXPLORATORY, budget)),
        )

        assertEquals(BenchmarkGateStatus.PASSED, report.status)
        assertEquals(BudgetEvaluationStatus.NOT_ENFORCED, report.cases.single().evaluations.single().status)
    }

    @Test
    fun `contractual absolute ceiling uses confidence before failing`() {
        val budget = BenchmarkBudgetsBuilder().apply {
            p99.regression atMost 100.percent
            p99 atMost 110.nanoseconds
        }.build()
        val report = BenchmarkComparator().compare(
            run("main", case(100, PerformanceContractStatus.CONTRACTUAL, budget)),
            run("branch", case(120, PerformanceContractStatus.CONTRACTUAL, budget)),
        )

        assertEquals(BenchmarkGateStatus.FAILED, report.status)
        assertEquals(
            BudgetEvaluationStatus.FAILED,
            report.cases.single().evaluations.single { it.kind == BudgetKind.ABSOLUTE }.status,
        )
    }

    @Test
    fun `environment mismatch cannot produce a gate decision`() {
        val baseline = run("main", case(100), environment = environment("one"))
        val candidate = run("branch", case(100), environment = environment("two"))

        assertEquals(BenchmarkGateStatus.INCOMPATIBLE, BenchmarkComparator().compare(baseline, candidate).status)
    }

    @Test
    fun `run and comparison JSON round trip`() {
        val baseline = run("main", case(100))
        val candidate = run("branch", case(101))
        val report = BenchmarkComparator().compare(baseline, candidate)

        assertEquals(baseline, BenchmarkJson.decodeRun(BenchmarkJson.encode(baseline)))
        assertEquals(report, BenchmarkJson.decodeComparison(BenchmarkJson.encode(report)))
        assertTrue(BenchmarkReports.text(report).contains("Benchmark comparison"))
        assertTrue(BenchmarkReports.markdown(report).contains("| Scenario |"))
    }

    private fun case(
        nanos: Long,
        status: PerformanceContractStatus = PerformanceContractStatus.EXPLORATORY,
        budgets: BenchmarkBudgets = BenchmarkBudgets(),
    ): BenchmarkCaseResult {
        val samples = List(30) { BenchmarkSample(nanos, allocatedBytes = 64) }
        return BenchmarkCaseResult(
            id = BenchmarkCaseId(
                BenchmarkId("commands.dispatch"),
                "typical",
                BenchmarkLayer.JVM,
                BenchmarkTemperature.WARM,
            ),
            status = status,
            metrics = BenchmarkStatistics.aggregate(samples),
            samples = samples,
            budgets = SerializableBenchmarkBudgets.from(budgets),
        )
    }

    private fun run(
        revision: String,
        case: BenchmarkCaseResult,
        environment: MeasurementEnvironment = environment("canonical"),
    ): BenchmarkRunResult = BenchmarkRunResult(
        revision = revision,
        environment = environment,
        configuration = BenchmarkRunConfiguration(),
        cases = listOf(case),
    )

    private fun environment(id: String): MeasurementEnvironment = MeasurementEnvironment(
        environmentId = id,
        operatingSystem = "test",
        architecture = "x86_64",
        availableProcessors = 1,
        cpuModel = "test",
        jvmVendor = "test",
        jvmVersion = "25",
        jvmArguments = emptyList(),
        garbageCollectors = listOf("test"),
        kotlinVersion = "2.4.10",
        frameworkVersion = "test",
    )
}
