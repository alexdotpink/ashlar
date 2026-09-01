package pink.alex.ashlar.benchmarks

import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class BenchmarkDslTest {
    @Test
    fun `scenario requires explicit status measurement and verification`() {
        assertFailsWith<IllegalStateException> {
            benchmarkScenario("test.missing") {
                measure { Unit }
                verify {}
            }
        }
        assertFailsWith<IllegalStateException> {
            benchmarkScenario("test.missing") {
                status = PerformanceContractStatus.EXPLORATORY
                verify {}
            }
        }
        assertFailsWith<IllegalStateException> {
            benchmarkScenario("test.missing") {
                status = PerformanceContractStatus.EXPLORATORY
                measure { Unit }
            }
        }
    }

    @Test
    fun `guarded and contractual statuses require their promised budgets`() {
        assertFailsWith<IllegalArgumentException> {
            benchmarkScenario("test.guarded") {
                status = PerformanceContractStatus.GUARDED
                measure { Unit }
                verify {}
            }
        }
        assertFailsWith<IllegalArgumentException> {
            benchmarkScenario("test.contractual") {
                status = PerformanceContractStatus.CONTRACTUAL
                measure { Unit }
                verify {}
                budgets { p99.regression atMost 5.percent }
            }
        }
    }

    @Test
    fun `suite qualifies scenario ids and retains explicit profiles`() {
        val suite = benchmarkSuite("commands") {
            benchmarkScenario("dispatch") {
                status = PerformanceContractStatus.EXPLORATORY
                profiles {
                    profile("small", "routes" to 10)
                    profile("stress", "routes" to 2_000)
                }
                temperatures(BenchmarkTemperature.COLD, BenchmarkTemperature.WARM)
                measure { profile["routes"] }
                verify { result -> assertTrue(result is Long) }
            }
        }

        assertEquals(BenchmarkId("commands.dispatch"), suite.scenarios.single().id)
        assertEquals(listOf("small", "stress"), suite.scenarios.single().profiles.map(BenchmarkProfile::name))
        assertEquals(setOf(BenchmarkTemperature.COLD, BenchmarkTemperature.WARM), suite.scenarios.single().temperatures)
    }

    @Test
    fun `runner owns fixtures and excludes setup verify cleanup from sample count`() = runTest {
        val creates = AtomicInteger()
        val closes = AtomicInteger()
        val setups = AtomicInteger()
        val verifies = AtomicInteger()
        val cleanups = AtomicInteger()
        val suite = benchmarkSuite("calibration") {
            benchmarkScenario("fixture") {
                status = PerformanceContractStatus.EXPLORATORY
                evidence(BenchmarkLayer.CALIBRATION)
                val counter by fixture(
                    name = "counter",
                    create = { creates.incrementAndGet(); AtomicInteger() },
                    close = { closes.incrementAndGet() },
                )
                setup { setups.incrementAndGet(); counter.set(0) }
                measure { counter.incrementAndGet() }
                verify { result -> verifies.incrementAndGet(); assertEquals(1, result) }
                cleanup { cleanups.incrementAndGet() }
            }
        }
        val result = BenchmarkRunner(
            localEnvironment(),
            BenchmarkRunConfiguration(warmupIterations = 1, measurementIterations = 3, forks = 2),
        ).run(suite, revision = "candidate")

        assertEquals(6, result.cases.single().samples.size)
        assertEquals(2, creates.get())
        assertEquals(2, closes.get())
        assertEquals(8, setups.get())
        assertEquals(8, verifies.get())
        assertEquals(8, cleanups.get())
        assertTrue(result.cases.single().metric(BenchmarkMetric.LATENCY_P99)!! >= 0.0)
    }

    private fun localEnvironment(): MeasurementEnvironment = MeasurementEnvironment(
        environmentId = "test",
        operatingSystem = "test",
        architecture = "x86_64",
        availableProcessors = 1,
        cpuModel = "test",
        jvmVendor = "test",
        jvmVersion = "25",
        jvmArguments = emptyList(),
        garbageCollectors = listOf("test"),
        kotlinVersion = KotlinVersion.CURRENT.toString(),
        ashlarVersion = "test",
    )
}
