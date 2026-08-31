package dev.placeholder.framework.benchmarks

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

val discoveryBenchmarks: BenchmarkSuite = benchmarkSuite("discovery") {
    benchmarkScenario("finds-suite") {
        status = PerformanceContractStatus.EXPLORATORY
        evidence(BenchmarkLayer.CALIBRATION)
        measure { 1 }
        verify { result -> assertEquals(1, result) }
    }
}

class BenchmarkDiscoveryTest {
    @Test
    fun `discovers public top-level suite getters`() {
        val classes = Path.of(requireNotNull(javaClass.protectionDomain.codeSource.location).toURI())

        val discovered = BenchmarkDiscovery.discover(listOf(classes))

        assertTrue(discovered.any { it.namespace == "discovery" })
        assertTrue(discovered.flatMap(BenchmarkSuite::scenarios).any { it.id == BenchmarkId("discovery.finds-suite") })
    }
}
