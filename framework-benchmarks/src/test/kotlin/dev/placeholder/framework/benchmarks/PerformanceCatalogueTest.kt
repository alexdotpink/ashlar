package dev.placeholder.framework.benchmarks

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PerformanceCatalogueTest {
    @Test
    fun `catalogue reports all missing profiles and contracts`() {
        val catalogue = PerformanceCatalogue(
            listOf(
                PerformanceCapability("Test", "complete", BenchmarkId("test.complete")),
                PerformanceCapability("Test", "external", BenchmarkId("test.external")),
            ),
        )
        val suite = benchmarkSuite("test") {
            benchmarkScenario("complete") {
                status = PerformanceContractStatus.EXPLORATORY
                profiles { profile("typical", "operations" to 1) }
                measure { Unit }
                verify { }
            }
        }

        val missing = catalogue.validate(listOf(suite))
        assertFalse(missing.complete)
        assertTrue(missing.problems.any { "small" in it && "stress" in it })
        assertTrue(missing.problems.any { "COLD" in it })
        assertTrue(missing.problems.any { "test.external" in it })

        val external = catalogue.validate(listOf(suite), setOf(BenchmarkId("test.external")))
        assertEquals(2, external.problems.size)
    }

    @Test
    fun `release validation requires contractual maturity`() {
        val scenario = benchmarkScenario("test.release") {
            status = PerformanceContractStatus.EXPLORATORY
            profiles {
                profile("small")
                profile("typical")
                profile("stress")
            }
            temperatures(BenchmarkTemperature.COLD, BenchmarkTemperature.WARM)
            measure { Unit }
            verify { }
        }
        val suite = BenchmarkSuite("test", listOf(scenario))
        val catalogue = PerformanceCatalogue(
            listOf(PerformanceCapability("Test", "release", BenchmarkId("test.release"))),
        )

        assertTrue(catalogue.validate(listOf(suite)).complete)
        assertFalse(catalogue.validate(listOf(suite), releaseReady = true).complete)
    }
}
