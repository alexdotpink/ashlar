package dev.placeholder.framework.gradle

import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

/** Compares one benchmark result with its compatible baseline. */
@DisableCachingByDefault(because = "Benchmark comparisons are cheap and report current evidence")
public abstract class CompareFrameworkBenchmarks : JavaExec() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    public abstract val baselineFile: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    public abstract val candidateFile: RegularFileProperty

    @get:OutputFile
    public abstract val jsonReport: RegularFileProperty

    @get:OutputFile
    public abstract val markdownReport: RegularFileProperty

    @TaskAction
    override fun exec() {
        setArgs(
            listOf(
                "compare",
                "--baseline", baselineFile.get().asFile.absolutePath,
                "--candidate", candidateFile.get().asFile.absolutePath,
                "--json", jsonReport.get().asFile.absolutePath,
                "--markdown", markdownReport.get().asFile.absolutePath,
            ),
        )
        super.exec()
    }
}
