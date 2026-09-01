package pink.alex.ashlar.sample

import pink.alex.ashlar.benchmarks.BenchmarkLayer
import pink.alex.ashlar.benchmarks.BenchmarkTemperature
import pink.alex.ashlar.benchmarks.PerformanceContractStatus
import pink.alex.ashlar.benchmarks.benchmarkSuite
import pink.alex.ashlar.input.accept
import pink.alex.ashlar.input.testing.InputTestHarness
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

val samplePerformance = benchmarkSuite("sample") {
    benchmarkScenario("typed-chat") {
        status = PerformanceContractStatus.EXPLORATORY
        evidence(BenchmarkLayer.JVM)
        temperatures(BenchmarkTemperature.COLD, BenchmarkTemperature.WARM)
        profiles {
            profile("small", "answers" to 1)
            profile("typical", "answers" to 10)
            profile("stress", "answers" to 100)
        }
        val input by fixture("input", close = { it.close() }) { InputTestHarness() }
        val player by fixture("player") { input.player("Alex") }
        setup { input.clear(player) }
        measure {
            buildList<String> {
                repeat(profile["answers"].toInt()) { index ->
                    val value = coroutineScope {
                        val answer = async<String>(start = CoroutineStart.UNDISPATCHED) {
                            input.playerInput.chat(
                                player = player,
                                prompt = "Search query",
                                idleTimeout = null,
                            ) { accept(text) }
                        }
                        input.answer(player, "market-$index")
                        answer.await()
                    }
                    add(value)
                }
            }
        }
        verify { result ->
            check(result is List<*>)
            check(result.size == profile["answers"].toInt())
            check(result.last() == "market-${profile["answers"] - 1}")
        }
    }
}
