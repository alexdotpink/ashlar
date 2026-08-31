package dev.placeholder.framework.gradle

import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

/** Renders an existing benchmark comparison in the console. */
@DisableCachingByDefault(because = "The task writes its report to the console")
public abstract class ReportFrameworkBenchmarks : JavaExec() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    public abstract val comparisonFile: RegularFileProperty

    @TaskAction
    override fun exec() {
        setArgs(
            listOf(
                "report",
                "--comparison", comparisonFile.get().asFile.absolutePath,
                "--format", "text",
            ),
        )
        super.exec()
    }
}
