package pink.alex.ashlar.commands

import pink.alex.ashlar.benchmarks.BenchmarkLayer
import pink.alex.ashlar.benchmarks.BenchmarkTemperature
import pink.alex.ashlar.benchmarks.PerformanceContractStatus
import pink.alex.ashlar.benchmarks.benchmarkSuite
import pink.alex.ashlar.commands.codegen.CommandParameterDefinition
import pink.alex.ashlar.commands.codegen.CommandRouteDefinition
import pink.alex.ashlar.commands.codegen.CommandSegmentDefinition
import pink.alex.ashlar.commands.codegen.CommandSetBinding
import pink.alex.ashlar.commands.codegen.CommandSetDefinition
import pink.alex.ashlar.commands.testing.CommandTestHarness
import pink.alex.ashlar.di.DependencyGraph
import pink.alex.ashlar.di.DependencyResolver
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
