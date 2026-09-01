package pink.alex.ashlar.benchmarks

import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Stable JSON codec for benchmark runs and comparisons. */
public object BenchmarkJson {
    public val format: Json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = false
    }

    public fun encode(result: BenchmarkRunResult): String = format.encodeToString(result)

    public fun encode(report: BenchmarkComparisonReport): String = format.encodeToString(report)

    public fun decodeRun(json: String): BenchmarkRunResult = format.decodeFromString(json)

    public fun decodeComparison(json: String): BenchmarkComparisonReport = format.decodeFromString(json)

    public fun write(path: Path, result: BenchmarkRunResult) {
        path.parent?.let(Files::createDirectories)
        Files.writeString(path, encode(result))
    }

    public fun write(path: Path, report: BenchmarkComparisonReport) {
        path.parent?.let(Files::createDirectories)
        Files.writeString(path, encode(report))
    }
}

/** Human-readable CLI and CI summaries. */
public object BenchmarkReports {
    /** Renders a compact terminal comparison with one row per enforced budget. */
    public fun text(report: BenchmarkComparisonReport): String = buildString {
        appendLine("Benchmark comparison ${report.baselineRevision} -> ${report.candidateRevision}")
        appendLine("Status: ${report.status}")
        if (!report.environmentCompatible) appendLine("Measurement environments are incompatible.")
        report.cases.forEach { case ->
            appendLine()
            appendLine("${case.id.scenario} [${case.id.profile}/${case.id.layer}/${case.id.temperature}]")
            case.evaluations.forEach { evaluation ->
                appendLine(
                    "  ${evaluation.status.toString().padEnd(14)} " +
                        "${evaluation.kind.toString().lowercase(Locale.ROOT)} " +
                        "${evaluation.metric}: ${format(evaluation.observed)} " +
                        "limit ${format(evaluation.limit)}",
                )
            }
        }
        if (report.missingBaselineCases.isNotEmpty()) {
            appendLine("Missing from baseline: ${report.missingBaselineCases.joinToString()}")
        }
        if (report.missingCandidateCases.isNotEmpty()) {
            appendLine("Missing from candidate: ${report.missingCandidateCases.joinToString()}")
        }
    }

    /** Renders a GitHub-compatible Markdown summary. */
    public fun markdown(report: BenchmarkComparisonReport): String = buildString {
        appendLine("## Benchmark comparison")
        appendLine()
        appendLine("`${report.baselineRevision}` -> `${report.candidateRevision}`: **${report.status}**")
        appendLine()
        appendLine("| Scenario | Profile | Layer | Metric | Budget | Outcome |")
        appendLine("| --- | --- | --- | --- | ---: | --- |")
        report.cases.forEach { case ->
            case.evaluations.forEach { evaluation ->
                appendLine(
                    "| `${case.id.scenario}` | ${case.id.profile} | ${case.id.layer} | " +
                        "${evaluation.metric} | ${format(evaluation.limit)} | ${evaluation.status} |",
                )
            }
        }
    }

    private fun format(value: Double): String = when {
        value == Double.MAX_VALUE -> "infinite"
        value >= 1_000_000.0 -> String.format(Locale.ROOT, "%.3fM", value / 1_000_000.0)
        value >= 1_000.0 -> String.format(Locale.ROOT, "%.3fk", value / 1_000.0)
        else -> String.format(Locale.ROOT, "%.4f", value)
    }
}
