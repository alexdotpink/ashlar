package dev.placeholder.framework.commands

import dev.placeholder.framework.benchmarks.BenchmarkLayer
import dev.placeholder.framework.benchmarks.BenchmarkTemperature
import dev.placeholder.framework.benchmarks.PerformanceContractStatus
import dev.placeholder.framework.benchmarks.benchmarkSuite
import dev.placeholder.framework.commands.codegen.CommandParameterDefinition
import dev.placeholder.framework.commands.codegen.CommandRouteDefinition
import dev.placeholder.framework.commands.codegen.CommandSegmentDefinition
import dev.placeholder.framework.commands.codegen.CommandSetBinding
import dev.placeholder.framework.commands.codegen.CommandSetDefinition
import dev.placeholder.framework.commands.testing.CommandTestHarness
import dev.placeholder.framework.di.DependencyGraph
import dev.placeholder.framework.di.DependencyResolver
import java.util.Locale
import kotlin.reflect.KClass
import net.kyori.adventure.audience.Audience

val commandPerformanceContracts = benchmarkSuite("commands") {
    benchmarkScenario("direct-control") {
        status = PerformanceContractStatus.EXPLORATORY
        evidence(BenchmarkLayer.JVM)
        temperatures(BenchmarkTemperature.COLD, BenchmarkTemperature.WARM)
        profiles {
            profile("small", "routes" to 10)
            profile("typical", "routes" to 250)
            profile("stress", "routes" to 2_000)
        }
        val target by fixture("target") { DispatchTarget() }
        setup { target.lastValue = null }
        measure { target.invoke(profile["routes"].toInt() - 1, "market") }
        verify { result ->
            check(result == "market")
            check(target.lastValue == "market")
        }
    }

    benchmarkScenario("dispatch") {
        status = PerformanceContractStatus.EXPLORATORY
        evidence(BenchmarkLayer.JVM)
        temperatures(BenchmarkTemperature.COLD, BenchmarkTemperature.WARM)
        profiles {
            profile("small", "routes" to 10)
            profile("typical", "routes" to 250)
            profile("stress", "routes" to 2_000)
        }
        val fixture by fixture("commandRuntime") {
            val routes = profile["routes"].toInt()
            val target = DispatchTarget()
            DispatchFixture(
                target = target,
                harness = CommandTestHarness(DispatchBinding(routes), target, DependencyGraph(javaClass.classLoader)),
                command = "/bench route${routes - 1} market",
            )
        }
        setup { fixture.target.lastValue = null }
        measure { fixture.harness.execute(fixture.command, invocation) }
        verify { result ->
            check(result is CommandResult)
            check(result.responses.size == 1)
            check(fixture.target.lastValue == "market")
        }
    }
}

private class DispatchTarget {
    var lastValue: String? = null

    fun invoke(route: Int, value: String): String {
        check(route >= 0)
        lastValue = value
        return value
    }
}

private class DispatchBinding(routeCount: Int) : CommandSetBinding<DispatchTarget> {
    override val targetType: KClass<DispatchTarget> = DispatchTarget::class
    override val definition: CommandSetDefinition = CommandSetDefinition(
        name = "bench",
        aliases = emptyList(),
        permission = null,
        routes = List(routeCount) { route ->
            CommandRouteDefinition(
                name = "route$route",
                parameters = listOf(CommandParameterDefinition("value", optional = false)),
                segments = listOf(
                    CommandSegmentDefinition.Literal(listOf("route$route")),
                    CommandSegmentDefinition.Argument(0),
                ),
            )
        },
    )

    override suspend fun invokeTyped(
        target: DispatchTarget,
        route: Int,
        arguments: List<Any?>,
        dependencies: DependencyResolver,
    ): Any = target.invoke(route, arguments.single() as String)
}

private data class DispatchFixture(
    val target: DispatchTarget,
    val harness: CommandTestHarness,
    val command: String,
)

private val invocation = CommandInvocation(
    sender = CommandSender("benchmark", null, Locale.ENGLISH, Audience.empty()) { true },
    executor = CommandExecutor("benchmark", null),
    route = "benchmark",
)
