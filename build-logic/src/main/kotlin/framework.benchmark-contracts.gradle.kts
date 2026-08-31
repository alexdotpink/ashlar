import org.gradle.jvm.toolchain.JavaLanguageVersion

plugins {
    kotlin("jvm")
}

val mainSourceSet = sourceSets.named("main").get()
val benchmarkSourceSet = sourceSets.create("benchmark") {
    compileClasspath += mainSourceSet.output
    runtimeClasspath += output + compileClasspath
}

configurations.named(benchmarkSourceSet.implementationConfigurationName) {
    extendsFrom(configurations.getByName("implementation"))
}
configurations.named(benchmarkSourceSet.compileOnlyConfigurationName) {
    extendsFrom(configurations.getByName("compileOnly"))
}
configurations.named(benchmarkSourceSet.runtimeOnlyConfigurationName) {
    extendsFrom(configurations.getByName("runtimeOnly"))
}

dependencies {
    add(benchmarkSourceSet.implementationConfigurationName, project(":framework-benchmarks"))
}

val benchmarkReports = layout.buildDirectory.dir("reports/benchmarks")
val benchmarkResult = benchmarkReports.map { it.file("run.json") }
val jmhResult = benchmarkReports.map { it.file("jmh.json") }
val diagnosticResult = benchmarkReports.map { it.file("diagnostic.json") }
val diagnosticRecording = benchmarkReports.map { it.file("diagnostic.jfr") }

fun RunFrameworkBenchmarks.configureBenchmarkCommand(command: String, destination: Provider<RegularFile>) {
    group = "verification"
    dependsOn(benchmarkSourceSet.classesTaskName)
    classpath = benchmarkSourceSet.runtimeClasspath
    mainClass.set("dev.placeholder.framework.benchmarks.BenchmarkCli")
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) })
    commandName.set(command)
    resultFile.set(destination)
    revision.set(providers.gradleProperty("benchmarkRevision").orElse("working-tree"))
    frameworkVersion.set(project.version.toString())
    warmups.set(providers.gradleProperty("benchmarkWarmups").map(String::toInt).orElse(5))
    iterations.set(providers.gradleProperty("benchmarkIterations").map(String::toInt).orElse(20))
    forks.set(providers.gradleProperty("benchmarkForks").map(String::toInt).orElse(3))
    warmupMillis.set(providers.gradleProperty("benchmarkWarmupMillis").map(String::toInt).orElse(250))
    measurementMillis.set(providers.gradleProperty("benchmarkMeasurementMillis").map(String::toInt).orElse(500))
    profiles.set(
        providers.gradleProperty("benchmarkProfiles")
            .map { value -> value.split(',').filter(String::isNotBlank) }
            .orElse(emptyList()),
    )
    scenarios.set(
        providers.gradleProperty("benchmarkScenarios")
            .map { value -> value.split(',').filter(String::isNotBlank) }
            .orElse(emptyList()),
    )
    benchmarkClassDirectories.from(benchmarkSourceSet.output.classesDirs)
}

tasks.register<RunFrameworkBenchmarks>("benchmark") {
    description = "Runs exploratory benchmark scenarios owned by this module."
    configureBenchmarkCommand("run", benchmarkResult)
}

tasks.register<RunFrameworkBenchmarks>("benchmarkJmh") {
    description = "Runs isolated benchmark scenarios owned by this module through OpenJDK JMH."
    configureBenchmarkCommand("jmh", jmhResult)
}

tasks.register<RunFrameworkBenchmarks>("benchmarkDiagnose") {
    description = "Profiles selected benchmark scenarios in a separate JFR diagnostic pass."
    configureBenchmarkCommand("diagnose", diagnosticResult)
    recordingFile.set(diagnosticRecording)
}
