package pink.alex.ashlar.input

import pink.alex.ashlar.benchmarks.BenchmarkTemperature
import pink.alex.ashlar.benchmarks.PerformanceContractStatus
import pink.alex.ashlar.benchmarks.benchmarkSuite
import pink.alex.ashlar.input.testing.InputTestHarness
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

public val inputPerformanceContracts = benchmarkSuite("input") {
    benchmarkScenario("prompt") {
        status = PerformanceContractStatus.EXPLORATORY
        profiles {
            profile("small", "prompts" to 1, "retries" to 0)
            profile("typical", "prompts" to 100, "retries" to 1)
            profile("stress", "prompts" to 10_000, "retries" to 3)
        }
        temperatures(BenchmarkTemperature.COLD, BenchmarkTemperature.WARM)
        val input by fixture("input", close = { it.close() }) { InputTestHarness() }
        val player by fixture("player") { input.player("BenchmarkPlayer") }

        setup { input.clear(player) }
        measure {
            coroutineScope {
                var accepted = 0
                repeat(profile["prompts"].toInt()) {
                    val pending = async(start = CoroutineStart.UNDISPATCHED) {
                        input.playerInput.chat(player, "Answer", idleTimeout = null) {
                            if (text == "yes") accept(1) else retry("Again")
                        }
                    }
                    repeat(profile["retries"].toInt()) { input.answer(player, "no") }
                    input.answer(player, "yes")
                    accepted += pending.await()
                }
                accepted
            }
        }
        verify { value -> check(value == profile["prompts"].toInt()) }
    }
}
