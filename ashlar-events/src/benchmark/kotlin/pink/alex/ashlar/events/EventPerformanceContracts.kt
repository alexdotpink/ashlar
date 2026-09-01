package pink.alex.ashlar.events

import pink.alex.ashlar.benchmarks.BenchmarkTemperature
import pink.alex.ashlar.benchmarks.PerformanceContractStatus
import pink.alex.ashlar.benchmarks.benchmarkSuite
import pink.alex.ashlar.di.DependencyGraph
import pink.alex.ashlar.events.testing.EventTestHarness
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.bukkit.event.Event
import org.bukkit.event.HandlerList

public val eventPerformanceContracts = benchmarkSuite("events") {
    benchmarkScenario("delivery") {
        status = PerformanceContractStatus.EXPLORATORY
        profiles {
            profile("small", "events" to 10)
            profile("typical", "events" to 1_000)
            profile("stress", "events" to 100_000)
        }
        temperatures(BenchmarkTemperature.COLD, BenchmarkTemperature.WARM)
        val runtime by fixture("runtime", close = { it.close() }) { EventBenchmarkRuntime() }

        setup { runtime.handlers.calls.set(0) }
        measure {
            repeat(profile["events"].toInt()) { index ->
                runtime.harness.dispatch(BenchmarkServerEvent(index)).checkSuccessful()
            }
            runtime.handlers.calls.get()
        }
        verify { value -> check(value == profile["events"].toInt()) }
    }

    benchmarkScenario("queries") {
        status = PerformanceContractStatus.EXPLORATORY
        profiles {
            profile("small", "queries" to 10)
            profile("typical", "queries" to 1_000)
            profile("stress", "queries" to 10_000)
        }
        temperatures(BenchmarkTemperature.COLD, BenchmarkTemperature.WARM)
        val runtime by fixture("runtime", close = { it.close() }) { EventBenchmarkRuntime() }

        measure {
            coroutineScope {
                var checksum = 0
                repeat(profile["queries"].toInt()) { index ->
                    val awaiting = async(start = CoroutineStart.UNDISPATCHED) {
                        runtime.harness.serverEvents.await<BenchmarkServerEvent, Int> { value }
                    }
                    runtime.harness.dispatch(BenchmarkServerEvent(index)).checkSuccessful()
                    checksum += awaiting.await()
                }
                checksum
            }
        }
        verify { value ->
            val count = profile["queries"].toInt()
            check(value == count * (count - 1) / 2)
        }
    }
}

private class EventBenchmarkRuntime : AutoCloseable {
    private val graph = DependencyGraph(BenchmarkEventHandlers::class.java.classLoader)
    val harness = EventTestHarness(graph)
    val handlers: BenchmarkEventHandlers = graph.get(BenchmarkEventHandlers::class)

    override fun close() {
        harness.close()
        graph.close()
    }
}

@Events
internal class BenchmarkEventHandlers {
    val calls = AtomicInteger()

    @On
    fun BenchmarkServerEvent.record() {
        calls.incrementAndGet()
    }
}

internal class BenchmarkServerEvent(val value: Int) : Event() {
    override fun getHandlers(): HandlerList = HANDLERS

    private companion object {
        val HANDLERS = HandlerList()
    }
}
