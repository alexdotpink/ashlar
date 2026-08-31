package dev.placeholder.framework.di

import dev.placeholder.framework.benchmarks.BenchmarkTemperature
import dev.placeholder.framework.benchmarks.PerformanceContractStatus
import dev.placeholder.framework.benchmarks.benchmarkSuite

public val diPerformanceContracts = benchmarkSuite("di") {
    benchmarkScenario("graph") {
        status = PerformanceContractStatus.EXPLORATORY
        profiles {
            profile("small", "resolutions" to 10)
            profile("typical", "resolutions" to 1_000)
            profile("stress", "resolutions" to 100_000)
        }
        temperatures(BenchmarkTemperature.COLD, BenchmarkTemperature.WARM)

        measure {
            DependencyGraph(DiBenchmarkService::class.java.classLoader).use { graph ->
                val service = graph.get(DiBenchmarkService::class)
                var checksum = 0
                repeat(profile["resolutions"].toInt()) {
                    checksum += graph.get(DiBenchmarkService::class).value
                }
                check(service === graph.get(DiBenchmarkService::class))
                checksum
            }
        }
        verify { value -> check(value == profile["resolutions"].toInt() * 42) }
    }

    benchmarkScenario("invocation-scopes") {
        status = PerformanceContractStatus.EXPLORATORY
        profiles {
            profile("small", "scopes" to 10)
            profile("typical", "scopes" to 1_000)
            profile("stress", "scopes" to 100_000)
        }
        temperatures(BenchmarkTemperature.COLD, BenchmarkTemperature.WARM)
        val graph by fixture("graph", close = { it.close() }) {
            DependencyGraph(DiBenchmarkService::class.java.classLoader)
        }

        measure {
            var checksum = 0
            repeat(profile["scopes"].toInt()) { index ->
                graph.invocation(DiInvocationValue(index)).use { invocation ->
                    checksum += invocation.get(DiInvocationValue::class).value
                }
            }
            checksum
        }
        verify { value ->
            val count = profile["scopes"].toInt()
            check(value == count * (count - 1) / 2)
        }
    }
}

@Inject
@PluginScoped
internal class DiBenchmarkService {
    val value: Int = 42
}

internal data class DiInvocationValue(val value: Int)
