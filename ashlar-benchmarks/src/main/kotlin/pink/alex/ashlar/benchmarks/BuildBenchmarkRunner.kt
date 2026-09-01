package pink.alex.ashlar.benchmarks

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.streams.asSequence

/** Measures the repository's real Gradle and KSP work in child builds. */
public class BuildBenchmarkRunner(
    private val projectDirectory: Path,
    private val environment: MeasurementEnvironment,
    private val iterations: Int = 1,
) {
    init {
        require(iterations > 0) { "Build benchmark iterations must be positive" }
        require(projectDirectory.resolve("gradlew").isRegularFile()) { "No Gradle wrapper exists in $projectDirectory" }
    }

    /** Runs small, typical, and stress builds as cold reruns and warmed incremental builds. */
    public fun run(revision: String): BenchmarkRunResult {
        val cases = BuildProfile.entries.flatMap { profile ->
            BenchmarkTemperature.entries.map { temperature -> runCase(profile, temperature) }
        }
        return BenchmarkRunResult(
            revision = revision,
            environment = environment,
            configuration = BenchmarkRunConfiguration(
                warmupIterations = 1,
                measurementIterations = iterations,
                forks = 1,
                collectAllocation = false,
            ),
            cases = cases,
        )
    }

    private fun runCase(profile: BuildProfile, temperature: BenchmarkTemperature): BenchmarkCaseResult {
        if (temperature == BenchmarkTemperature.WARM) execute(profile, rerun = false)
        val samples = List(iterations) {
            val started = System.nanoTime()
            execute(profile, rerun = temperature == BenchmarkTemperature.COLD)
            BenchmarkSample(System.nanoTime() - started)
        }
        val generatedBytes = treeBytes(projectDirectory, "generated")
        val artifactBytes = treeBytes(projectDirectory, "libs")
        val supplemental = mapOf(
            BenchmarkMetric.GENERATED_BYTES to List(iterations) { generatedBytes.toDouble() },
            BenchmarkMetric.ARTIFACT_BYTES to List(iterations) { artifactBytes.toDouble() },
        )
        return BenchmarkCaseResult(
            id = BenchmarkCaseId(
                BenchmarkId("build.toolchain"),
                profile.id,
                BenchmarkLayer.BUILD,
                temperature,
            ),
            status = PerformanceContractStatus.EXPLORATORY,
            metrics = BenchmarkStatistics.aggregate(samples) + listOf(
                BenchmarkMetricValue(BenchmarkMetric.GENERATED_BYTES, generatedBytes.toDouble()),
                BenchmarkMetricValue(BenchmarkMetric.ARTIFACT_BYTES, artifactBytes.toDouble()),
            ),
            samples = samples,
            supplementalSamples = supplemental,
        )
    }

    private fun execute(profile: BuildProfile, rerun: Boolean) {
        val log = Files.createTempFile("ashlar-build-benchmark", ".log")
        try {
            val command = buildList {
                add(projectDirectory.resolve("gradlew").toString())
                addAll(profile.tasks)
                add("--configuration-cache")
                add("--console=plain")
                add("--daemon")
                if (rerun) add("--rerun-tasks")
            }
            val exit = ProcessBuilder(command)
                .directory(projectDirectory.toFile())
                .redirectErrorStream(true)
                .redirectOutput(log.toFile())
                .start()
                .waitFor()
            check(exit == 0) {
                "Build benchmark '${profile.id}' failed:\n${Files.readString(log).takeLast(8_000)}"
            }
        } finally {
            Files.deleteIfExists(log)
        }
    }

    private fun treeBytes(root: Path, directoryName: String): Long = Files.walk(root).use { paths ->
        paths.asSequence()
            .filter(Path::isRegularFile)
            .filter { path -> path.parent?.name == directoryName || directoryName in path.map(Path::toString) }
            .sumOf { path -> Files.size(path) }
    }

    private enum class BuildProfile(val id: String, val tasks: List<String>) {
        SMALL("small", listOf(":ashlar-di-ksp:compileKotlin")),
        TYPICAL("typical", listOf(":integration-test-fixture:kspKotlin")),
        STRESS("stress", listOf(":integration-test-fixture:shadowJar", ":sample-plugin:shadowJar")),
    }
}
