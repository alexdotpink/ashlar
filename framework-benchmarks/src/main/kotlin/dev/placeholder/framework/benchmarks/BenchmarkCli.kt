package dev.placeholder.framework.benchmarks

import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString

/** Command-line entry point used by Gradle benchmark tasks. */
public object BenchmarkCli {
    @JvmStatic
    public fun main(arguments: Array<String>) {
        val exit = runCatching { execute(arguments.toList(), System.out, System.err) }
            .fold(
                onSuccess = { it },
                onFailure = { failure ->
                    failure.printStackTrace(System.err)
                    2
                },
            )
        if (exit != 0) exitProcess(exit)
    }

    /** Executes one CLI command and returns its process exit code. */
    public fun execute(
        arguments: List<String>,
        output: PrintStream,
        error: PrintStream,
    ): Int = when (val command = arguments.firstOrNull()) {
        "run" -> run(arguments.drop(1), output)
        "jmh" -> runJmh(arguments.drop(1), output)
        "compare" -> compare(arguments.drop(1), output, error)
        "report" -> report(arguments.drop(1), output)
        "catalogue" -> catalogue(arguments.drop(1), output, error)
        else -> {
            error.println(
                "Unknown benchmark command '${command.orEmpty()}'. Expected run, jmh, compare, report, or catalogue.",
            )
            2
        }
    }

    private fun run(arguments: List<String>, output: PrintStream): Int {
        val options = CliOptions(arguments)
        val classDirectories = options.values("class-dir").ifEmpty { configuredClassDirectories() }.map(Path::of)
        val suites = BenchmarkDiscovery.discover(classDirectories)
        val configuration = BenchmarkRunConfiguration(
            warmupIterations = options.int("warmups", 5),
            measurementIterations = options.int("iterations", 20),
            forks = options.int("forks", 3),
            warmupTimeMillis = options.int("warmup-millis", 250),
            measurementTimeMillis = options.int("measurement-millis", 500),
            collectAllocation = !options.flag("no-allocation"),
            authoritative = options.flag("authoritative"),
        )
        val selectedProfiles = options.values("profile").toSet()
        val selectedScenarios = options.values("scenario").toSet()
        val environment = MeasurementEnvironment.local(
            frameworkVersion = options.value("framework-version") ?: "development",
            environmentId = options.value("environment") ?: "local",
            platform = options.value("platform"),
            platformVersion = options.value("platform-version"),
        )
        val runner = BenchmarkRunner(environment, configuration)
        val cases = runBlocking {
            suites.map { suite ->
                runner.run(suite, options.value("revision") ?: "working-tree") { id ->
                    (selectedProfiles.isEmpty() || id.profile in selectedProfiles) &&
                        (selectedScenarios.isEmpty() || id.scenario.value in selectedScenarios)
                }
            }
        }.flatMap(BenchmarkRunResult::cases)
        val result = BenchmarkRunResult(
            revision = options.value("revision") ?: "working-tree",
            environment = environment,
            configuration = configuration,
            cases = cases,
        )
        val destination = Path.of(options.value("output") ?: "build/reports/benchmarks/run.json")
        BenchmarkJson.write(destination, result)
        output.println("Wrote ${result.cases.size} benchmark cases to $destination")
        return 0
    }

    private fun runJmh(arguments: List<String>, output: PrintStream): Int {
        val options = CliOptions(arguments)
        val classDirectories = options.values("class-dir").ifEmpty { configuredClassDirectories() }.map(Path::of)
        val suites = BenchmarkDiscovery.discover(classDirectories)
        val configuration = BenchmarkRunConfiguration(
            warmupIterations = options.int("warmups", 5),
            measurementIterations = options.int("iterations", 20),
            forks = options.int("forks", 3),
            warmupTimeMillis = options.int("warmup-millis", 250),
            measurementTimeMillis = options.int("measurement-millis", 500),
            collectAllocation = !options.flag("no-allocation"),
            authoritative = options.flag("authoritative"),
        )
        val selectedProfiles = options.values("profile").toSet()
        val selectedScenarios = options.values("scenario").toSet()
        val environment = MeasurementEnvironment.local(
            frameworkVersion = options.value("framework-version") ?: "development",
            environmentId = options.value("environment") ?: "local",
        )
        val result = JmhBenchmarkRunner(environment, configuration).run(
            suites,
            classDirectories,
            options.value("revision") ?: "working-tree",
        ) { id ->
            (selectedProfiles.isEmpty() || id.profile in selectedProfiles) &&
                (selectedScenarios.isEmpty() || id.scenario.value in selectedScenarios)
        }
        val destination = Path.of(options.value("output") ?: "build/reports/benchmarks/jmh.json")
        BenchmarkJson.write(destination, result)
        output.println("Wrote ${result.cases.size} JMH benchmark cases to $destination")
        return 0
    }

    private fun compare(arguments: List<String>, output: PrintStream, error: PrintStream): Int {
        val options = CliOptions(arguments)
        val baseline = readRun(options.required("baseline"))
        val candidate = readRun(options.required("candidate"))
        val comparison = BenchmarkComparator(
            BenchmarkComparisonConfiguration(
                confidence = options.double("confidence", 0.95),
                resamples = options.int("resamples", 4_000),
                noiseFloorFraction = options.double("noise-floor", 0.01),
            ),
        ).compare(baseline, candidate)
        options.value("json")?.let { BenchmarkJson.write(Path.of(it), comparison) }
        options.value("markdown")?.let { destination ->
            Path.of(destination).also { path ->
                path.parent?.let(Files::createDirectories)
                Files.writeString(path, BenchmarkReports.markdown(comparison))
            }
        }
        output.println(BenchmarkReports.text(comparison))
        return when (comparison.status) {
            BenchmarkGateStatus.PASSED -> 0
            BenchmarkGateStatus.INCONCLUSIVE -> {
                error.println("Benchmark comparison is inconclusive and needs a confirmation run.")
                3
            }
            BenchmarkGateStatus.FAILED,
            BenchmarkGateStatus.INCOMPATIBLE,
            BenchmarkGateStatus.MISSING_CASES,
            -> 1
        }
    }

    private fun report(arguments: List<String>, output: PrintStream): Int {
        val options = CliOptions(arguments)
        val comparison = BenchmarkJson.decodeComparison(Files.readString(Path.of(options.required("comparison"))))
        output.println(
            when (options.value("format") ?: "text") {
                "text" -> BenchmarkReports.text(comparison)
                "markdown" -> BenchmarkReports.markdown(comparison)
                else -> error("Report format must be text or markdown")
            },
        )
        return 0
    }

    private fun catalogue(arguments: List<String>, output: PrintStream, error: PrintStream): Int {
        val options = CliOptions(arguments)
        val classDirectories = options.values("class-dir").ifEmpty { configuredClassDirectories() }.map(Path::of)
        val suites = BenchmarkDiscovery.discover(classDirectories)
        val external = options.values("external").mapTo(linkedSetOf(), ::BenchmarkId)
        val report = FrameworkPerformanceCatalogue.catalogue.validate(
            suites = suites,
            externalContracts = external,
            releaseReady = options.flag("release-ready"),
        )
        options.value("output")?.let { destination ->
            Path.of(destination).also { path ->
                path.parent?.let(Files::createDirectories)
                Files.writeString(path, BenchmarkJson.format.encodeToString(report))
            }
        }
        if (report.complete) {
            output.println(
                "Performance catalogue covers ${report.capabilityCount} capabilities with " +
                    "${report.contractCount} contracts.",
            )
            return 0
        }
        report.problems.forEach(error::println)
        return 1
    }

    private fun readRun(path: String): BenchmarkRunResult = BenchmarkJson.decodeRun(Files.readString(Path.of(path)))

    private fun configuredClassDirectories(): List<String> =
        System.getProperty("framework.benchmark.classDirs")
            ?.split(java.io.File.pathSeparator)
            ?.filter(String::isNotBlank)
            .orEmpty()
}

private class CliOptions(arguments: List<String>) {
    private val values: Map<String, List<String>>
    private val flags: Set<String>

    init {
        val collected = linkedMapOf<String, MutableList<String>>()
        val foundFlags = linkedSetOf<String>()
        var index = 0
        while (index < arguments.size) {
            val token = arguments[index]
            require(token.startsWith("--")) { "Unexpected benchmark argument '$token'" }
            val name = token.removePrefix("--")
            val next = arguments.getOrNull(index + 1)
            if (next == null || next.startsWith("--")) {
                foundFlags += name
                index++
            } else {
                collected.getOrPut(name) { mutableListOf() } += next
                index += 2
            }
        }
        values = collected.mapValues { it.value.toList() }
        flags = foundFlags
    }

    fun value(name: String): String? = values[name]?.lastOrNull()
    fun values(name: String): List<String> = values[name].orEmpty()
    fun flag(name: String): Boolean = name in flags
    fun required(name: String): String = value(name) ?: error("Missing required --$name")
    fun int(name: String, default: Int): Int = value(name)?.toInt() ?: default
    fun double(name: String, default: Double): Double = value(name)?.toDouble() ?: default
}
