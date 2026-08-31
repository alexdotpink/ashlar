package dev.placeholder.framework.benchmarks.internal

import dev.placeholder.framework.benchmarks.BenchmarkDiscovery
import dev.placeholder.framework.benchmarks.BenchmarkExecution
import dev.placeholder.framework.benchmarks.BenchmarkExecutionScope
import dev.placeholder.framework.benchmarks.BenchmarkId
import dev.placeholder.framework.benchmarks.BenchmarkLayer
import dev.placeholder.framework.benchmarks.BenchmarkScenario
import dev.placeholder.framework.benchmarks.BenchmarkTemperature
import dev.placeholder.framework.benchmarks.FixtureDeclaration
import java.io.File
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

internal class JmhScenarioBridge private constructor(
    private val scenario: BenchmarkScenario,
    private val scope: BenchmarkExecutionScope,
) : AutoCloseable {
    private var fixturesActive: Boolean = false
    private var result: Any? = null

    init {
        if (scope.temperature == BenchmarkTemperature.WARM) createFixtures()
    }

    fun setupInvocation() {
        runBlocking {
            withContext(BenchmarkExecution.context(scope)) {
                if (scope.temperature == BenchmarkTemperature.COLD) createFixtures()
                scope.clearRecordedMetrics()
                scenario.setup(scope)
            }
        }
    }

    fun measure(): Any? = runBlocking {
        withContext(BenchmarkExecution.context(scope)) {
            scenario.measure(scope).also { measured ->
                result = measured
                scope.consume(measured)
            }
        }
    }

    fun verifyInvocation() {
        runBlocking {
            withContext(BenchmarkExecution.context(scope)) {
                scenario.verify(scope, result)
                scenario.cleanup(scope)
                result = null
                if (scope.temperature == BenchmarkTemperature.COLD) closeFixtures()
            }
        }
    }

    override fun close() {
        if (fixturesActive) runBlocking { withContext(BenchmarkExecution.context(scope)) { closeFixtures() } }
    }

    private fun createFixtures() {
        check(!fixturesActive) { "JMH scenario fixtures are already active" }
        runBlocking {
            withContext(BenchmarkExecution.context(scope)) {
                scenario.fixtures.forEach { declaration -> install(declaration) }
            }
        }
        fixturesActive = true
    }

    private suspend fun <T : Any> install(declaration: FixtureDeclaration<T>) {
        scope.installFixture(declaration.name, declaration.create(scope))
    }

    private suspend fun closeFixtures() {
        if (!fixturesActive) return
        scenario.fixtures.asReversed().forEach { declaration -> close(declaration) }
        fixturesActive = false
    }

    private suspend fun <T : Any> close(declaration: FixtureDeclaration<T>) {
        declaration.close(scope, scope.removeFixture(declaration.name))
    }

    companion object {
        @JvmStatic
        fun fromSystemProperties(): JmhScenarioBridge {
            val classDirectories = requireProperty("framework.benchmark.classDirs")
                .split(File.pathSeparator)
                .filter(String::isNotBlank)
                .map(Path::of)
            val scenarioId = BenchmarkId(requireProperty("framework.benchmark.scenario"))
            val profileName = requireProperty("framework.benchmark.profile")
            val layer = BenchmarkLayer.valueOf(requireProperty("framework.benchmark.layer"))
            val temperature = BenchmarkTemperature.valueOf(requireProperty("framework.benchmark.temperature"))
            val scenario = BenchmarkDiscovery.discover(classDirectories)
                .flatMap { it.scenarios }
                .singleOrNull { it.id == scenarioId }
                ?: error("JMH could not find benchmark scenario '$scenarioId'")
            val profile = scenario.profiles.singleOrNull { it.name == profileName }
                ?: error("Benchmark '$scenarioId' has no profile '$profileName'")
            require(layer in scenario.layers) { "Benchmark '$scenarioId' does not declare layer $layer" }
            require(temperature in scenario.temperatures) {
                "Benchmark '$scenarioId' does not declare temperature $temperature"
            }
            return JmhScenarioBridge(
                scenario,
                BenchmarkExecutionScope(scenario, profile, layer, temperature),
            )
        }

        private fun requireProperty(name: String): String =
            System.getProperty(name)?.takeIf(String::isNotBlank) ?: error("Missing JVM property '$name'")
    }
}
