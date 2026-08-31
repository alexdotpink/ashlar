import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

@DisableCachingByDefault(because = "Benchmark measurements must execute on every invocation")
abstract class RunFrameworkBenchmarks : JavaExec() {
    @get:Input
    abstract val commandName: Property<String>

    @get:Input
    abstract val revision: Property<String>

    @get:Input
    abstract val frameworkVersion: Property<String>

    @get:Input
    abstract val warmups: Property<Int>

    @get:Input
    abstract val iterations: Property<Int>

    @get:Input
    abstract val forks: Property<Int>

    @get:Input
    abstract val warmupMillis: Property<Int>

    @get:Input
    abstract val measurementMillis: Property<Int>

    @get:Input
    abstract val profiles: ListProperty<String>

    @get:Input
    abstract val scenarios: ListProperty<String>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val benchmarkClassDirectories: ConfigurableFileCollection

    @get:OutputFile
    abstract val resultFile: RegularFileProperty

    @get:Optional
    @get:OutputFile
    abstract val recordingFile: RegularFileProperty

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
        recordingFile.orNull?.let { recording ->
            arguments += listOf("--recording", recording.asFile.absolutePath)
        }
        setArgs(arguments)
        systemProperty("framework.benchmark.classDirs", benchmarkClassDirectories.asPath)
        super.exec()
    }
}
