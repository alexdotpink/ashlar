package dev.placeholder.framework

import dev.placeholder.framework.benchmarks.BenchmarkTemperature
import dev.placeholder.framework.benchmarks.PerformanceContractStatus
import dev.placeholder.framework.benchmarks.benchmarkSuite
import dev.placeholder.framework.testkit.ComponentTestResult
import dev.placeholder.framework.testkit.componentTest
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineStart

public val kernelPerformanceContracts = benchmarkSuite("kernel") {
    benchmarkScenario("lifecycle") {
        status = PerformanceContractStatus.EXPLORATORY
        profiles {
            profile("small", "components" to 1)
            profile("typical", "components" to 16)
            profile("stress", "components" to 256)
        }
        temperatures(BenchmarkTemperature.COLD, BenchmarkTemperature.WARM)

        measure {
            List(profile["components"].toInt()) {
                val component = LifecycleBenchmarkComponent()
                val result = componentTest(factory = { component }).use { harness ->
                    harness.start()
                    harness.stop()
                }
                LifecycleOutcome(result, component.resourceClosed.get())
            }
        }
        verify { value ->
            val outcomes = value as List<*>
            check(outcomes.size == profile["components"].toInt())
            outcomes.filterIsInstance<LifecycleOutcome>().forEach { outcome ->
                outcome.result.checkSuccessful()
                check(outcome.resourceClosed)
            }
        }
    }

    benchmarkScenario("rollback") {
        status = PerformanceContractStatus.EXPLORATORY
        profiles {
            profile("small", "attempts" to 1)
            profile("typical", "attempts" to 16)
            profile("stress", "attempts" to 256)
        }
        temperatures(BenchmarkTemperature.COLD, BenchmarkTemperature.WARM)

        measure {
            List(profile["attempts"].toInt()) {
                runCatching {
                    componentTest(factory = ::FailingLifecycleBenchmarkComponent).use { harness -> harness.start() }
                }.isFailure
            }
        }
        verify { value -> check((value as List<*>).all { it == true }) }
    }
}

private data class LifecycleOutcome(
    val result: ComponentTestResult,
    val resourceClosed: Boolean,
)

private class LifecycleBenchmarkComponent : PluginComponent() {
    val resourceClosed = AtomicBoolean()

    override fun ComponentContext.start() {
        own(AutoCloseable { resourceClosed.set(true) })
        task("benchmark", CoroutineStart.UNDISPATCHED) { /* completes synchronously */ }
    }
}

private class FailingLifecycleBenchmarkComponent : PluginComponent() {
    override fun ComponentContext.start(): Unit = error("benchmark rollback")
}
