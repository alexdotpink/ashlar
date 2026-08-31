package dev.placeholder.framework.gradle

import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

/** Runs discovered framework scenarios without capturing Gradle project state at execution time. */
@DisableCachingByDefault(because = "Benchmark measurements must execute on every invocation")
public abstract class RunFrameworkBenchmarks : JavaExec() {
    @get:Input
    public abstract val commandName: Property<String>

    @get:Input
    public abstract val revision: Property<String>

    @get:Input
    public abstract val frameworkVersion: Property<String>

    @get:Input
    public abstract val warmups: Property<Int>

    @get:Input
    public abstract val iterations: Property<Int>

    @get:Input
    public abstract val forks: Property<Int>

    @get:Input
    public abstract val warmupMillis: Property<Int>

    @get:Input
    public abstract val measurementMillis: Property<Int>

    @get:Input
    public abstract val profiles: ListProperty<String>

    @get:Input
    public abstract val scenarios: ListProperty<String>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    public abstract val benchmarkClassDirectories: ConfigurableFileCollection

    @get:OutputFile
    public abstract val resultFile: RegularFileProperty

    @TaskAction
    override fun exec() {
        val arguments = mutableListOf(
            commandName.get(),
            "--output", resultFile.get().asFile.absolutePath,
            "--revision", revision.get(),
            "--framework-version", frameworkVersion.get(),
            "--warmups", warmups.get().toString(),
            "--iterations", iterations.get().toString(),
            "--forks", forks.get().toString(),
            "--warmup-millis", warmupMillis.get().toString(),
            "--measurement-millis", measurementMillis.get().toString(),
        )
        profiles.get().forEach { profile -> arguments += listOf("--profile", profile) }
        scenarios.get().forEach { scenario -> arguments += listOf("--scenario", scenario) }
        setArgs(arguments)
        systemProperty("framework.benchmark.classDirs", benchmarkClassDirectories.asPath)
        super.exec()
    }
}
