package dev.placeholder.framework.menus

import dev.placeholder.framework.benchmarks.BenchmarkTemperature
import dev.placeholder.framework.benchmarks.PerformanceContractStatus
import dev.placeholder.framework.benchmarks.benchmarkSuite
import dev.placeholder.framework.items.ItemSnapshot
import dev.placeholder.framework.items.item
import dev.placeholder.framework.menus.storage.MenuSlotAddress
import dev.placeholder.framework.menus.storage.MenuStorageGesture
import dev.placeholder.framework.menus.storage.MenuStorageId
import dev.placeholder.framework.menus.storage.MenuStorageRules
import dev.placeholder.framework.menus.storage.MenuStorageSnapshot
import dev.placeholder.framework.menus.storage.MenuTransactionEngine
import dev.placeholder.framework.menus.storage.MenuTransactionId
import dev.placeholder.framework.menus.storage.MenuTransactionPlan
import dev.placeholder.framework.menus.storage.MenuTransactionState
import dev.placeholder.framework.menus.testing.menuTest
import java.util.UUID
import org.bukkit.Material

public val menuPerformanceContracts = benchmarkSuite("menus") {
    benchmarkScenario("runtime") {
        status = PerformanceContractStatus.EXPLORATORY
        profiles {
            profile("small", "slots" to 9, "actions" to 1)
            profile("typical", "slots" to 45, "actions" to 100)
            profile("stress", "slots" to 54, "actions" to 10_000)
        }
        temperatures(BenchmarkTemperature.COLD, BenchmarkTemperature.WARM)

        measure {
            var calls = 0
            menuTest {
                val icon = item(Material.COMPASS)
                val menu = open {
                    chest("Benchmark", rows = 6) {
                        repeat(profile["slots"].toInt()) { index ->
                            slot(index) {
                                item = icon
                                onPrimary { calls++ }
                            }
                        }
                    }
                }
                repeat(profile["actions"].toInt()) { menu.primaryClick(it % profile["slots"].toInt()) }
                menu.close()
            }
            calls
        }
        verify { value -> check(value == profile["actions"].toInt()) }
    }

    benchmarkScenario("storage") {
        status = PerformanceContractStatus.EXPLORATORY
        profiles {
            profile("small", "slots" to 9, "operations" to 10)
            profile("typical", "slots" to 54, "operations" to 1_000)
            profile("stress", "slots" to 216, "operations" to 100_000)
        }
        temperatures(BenchmarkTemperature.COLD, BenchmarkTemperature.WARM)

        measure {
            val size = profile["slots"].toInt()
            val id = MenuStorageId("benchmark", "storage")
            val snapshot = MenuStorageSnapshot(
                id,
                revision = 0,
                slots = List(size) { index ->
                    if (index % 2 == 0) ItemSnapshot.detached(Material.DIAMOND, 32, 64, "diamond") else null
                },
            )
            val engine = MenuTransactionEngine(mapOf(id to MenuStorageRules.uniform(size)))
            val state = MenuTransactionState(mapOf(id to snapshot))
            val transactionId = MenuTransactionId(UUID(0, 1))
            var proposals = 0
            repeat(profile["operations"].toInt()) { index ->
                if (engine.plan(
                        state,
                        MenuStorageGesture.Primary(MenuSlotAddress(id, index % size)),
                        transactionId,
                    ) is MenuTransactionPlan.Proposed
                ) {
                    proposals++
                }
            }
            proposals
        }
        verify { value -> check((value as Int) > 0) }
    }
}
