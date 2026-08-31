package dev.placeholder.framework.items

import dev.placeholder.framework.benchmarks.BenchmarkTemperature
import dev.placeholder.framework.benchmarks.PerformanceContractStatus
import dev.placeholder.framework.benchmarks.benchmarkSuite
import java.nio.ByteBuffer
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.NamespacedKey

public val itemPerformanceContracts = benchmarkSuite("items") {
    benchmarkScenario("specification") {
        status = PerformanceContractStatus.EXPLORATORY
        profiles {
            profile("small", "items" to 1, "loreLines" to 1)
            profile("typical", "items" to 100, "loreLines" to 5)
            profile("stress", "items" to 10_000, "loreLines" to 20)
        }
        temperatures(BenchmarkTemperature.COLD, BenchmarkTemperature.WARM)

        measure {
            List(profile["items"].toInt()) { index ->
                item(Material.COMPASS) {
                    amount = index % 64 + 1
                    name = Component.text("Waypoint $index")
                    lore { repeat(profile["loreLines"].toInt()) { line(Component.text("Line $it")) } }
                    persistent(NamespacedKey("benchmark", "id"), index, IntItemCodec)
                }.edit { glint(index % 2 == 0) }
            }
        }
        verify { value -> check((value as List<*>).size == profile["items"].toInt()) }
    }

    benchmarkScenario("persistent-codecs") {
        status = PerformanceContractStatus.EXPLORATORY
        profiles {
            profile("small", "values" to 10)
            profile("typical", "values" to 10_000)
            profile("stress", "values" to 1_000_000)
        }
        temperatures(BenchmarkTemperature.COLD, BenchmarkTemperature.WARM)

        measure {
            var checksum = 0
            repeat(profile["values"].toInt()) { value ->
                checksum += IntItemCodec.decode(IntItemCodec.encode(value))
            }
            checksum
        }
        verify { value ->
            val count = profile["values"].toInt()
            check(value == count * (count - 1) / 2)
        }
    }
}

private object IntItemCodec : PersistentValueCodec<Int> {
    override val id: String = "benchmark-int-v1"
    override fun encode(value: Int): ByteArray = ByteBuffer.allocate(Int.SIZE_BYTES).putInt(value).array()
    override fun decode(bytes: ByteArray): Int = ByteBuffer.wrap(bytes).int
}
