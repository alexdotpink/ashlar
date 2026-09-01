import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.JavaExec
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService

plugins {
    base
    `java-base`
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.ksp) apply false
}

group = providers.gradleProperty("ashlar.group").get()
version = providers.gradleProperty("ashlar.version").get()

allprojects {
    group = rootProject.group
    version = rootProject.version
}

tasks.register("integrationTest") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Runs the pinned Paper and Folia integration fixtures."
    dependsOn(
        ":integration-test-fixture:paperIntegrationTest",
        ":integration-test-fixture:foliaIntegrationTest",
    )
}

tasks.register("checkKotlinAbi") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Checks the committed Kotlin ABI baselines for all published Kotlin artifacts."
    dependsOn(
        ":ashlar-kernel:checkKotlinAbi",
        ":ashlar-di:checkKotlinAbi",
        ":ashlar-di-ksp:checkKotlinAbi",
        ":ashlar-commands:checkKotlinAbi",
        ":ashlar-commands-ksp:checkKotlinAbi",
        ":ashlar-events:checkKotlinAbi",
        ":ashlar-events-ksp:checkKotlinAbi",
        ":ashlar-input:checkKotlinAbi",
        ":ashlar-items:checkKotlinAbi",
        ":ashlar-menus:checkKotlinAbi",
        ":ashlar-menus-test:checkKotlinAbi",
        ":ashlar-config:checkKotlinAbi",
        ":ashlar-config-ksp:checkKotlinAbi",
        ":ashlar-config-test:checkKotlinAbi",
        ":ashlar-benchmarks:checkKotlinAbi",
        ":ashlar-testkit:checkKotlinAbi",
        ":ashlar-incubator:checkKotlinAbi",
        ":ashlar-gradle-plugin:checkKotlinAbi",
    )
}

tasks.register("updateKotlinAbi") {
    group = "build setup"
    description = "Updates the Kotlin ABI baselines for all published Kotlin artifacts."
    dependsOn(
        ":ashlar-kernel:updateKotlinAbi",
        ":ashlar-di:updateKotlinAbi",
        ":ashlar-di-ksp:updateKotlinAbi",
        ":ashlar-commands:updateKotlinAbi",
        ":ashlar-commands-ksp:updateKotlinAbi",
        ":ashlar-events:updateKotlinAbi",
        ":ashlar-events-ksp:updateKotlinAbi",
        ":ashlar-input:updateKotlinAbi",
        ":ashlar-items:updateKotlinAbi",
        ":ashlar-menus:updateKotlinAbi",
        ":ashlar-menus-test:updateKotlinAbi",
        ":ashlar-config:updateKotlinAbi",
        ":ashlar-config-ksp:updateKotlinAbi",
        ":ashlar-config-test:updateKotlinAbi",
        ":ashlar-benchmarks:updateKotlinAbi",
        ":ashlar-testkit:updateKotlinAbi",
        ":ashlar-incubator:updateKotlinAbi",
        ":ashlar-gradle-plugin:updateKotlinAbi",
    )
}

val benchmarkModuleNames = listOf(
    "ashlar-kernel",
    "ashlar-di",
    "ashlar-commands",
    "ashlar-events",
    "ashlar-input",
    "ashlar-items",
    "ashlar-menus",
    "sample-plugin",
)
benchmarkModuleNames.forEach { evaluationDependsOn(":$it") }

val benchmarkModules = benchmarkModuleNames.map { project(":$it") }
val benchmarkCatalogue = tasks.register<JavaExec>("benchmarkCatalogue") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Checks that every framework capability has a complete performance contract."
    mainClass.set("pink.alex.ashlar.benchmarks.BenchmarkCli")
    javaLauncher.set(
        project.extensions.getByType(JavaToolchainService::class.java).launcherFor {
            languageVersion.set(JavaLanguageVersion.of(25))
        },
    )
    val benchmarkSourceSets = benchmarkModules.map { module ->
        module.extensions.getByType(SourceSetContainer::class.java).getByName("benchmark")
    }
    dependsOn(benchmarkModules.zip(benchmarkSourceSets).map { (module, sourceSet) ->
        module.tasks.named(sourceSet.classesTaskName)
    })
    classpath = files(benchmarkSourceSets.map { it.runtimeClasspath })
    val classDirectories = files(benchmarkSourceSets.map { it.output.classesDirs })
    val report = layout.buildDirectory.file("reports/benchmarks/catalogue.json")
    val externalContracts = listOf(
        "kernel.scheduler-handoff",
        "native.hosts",
        "build.toolchain",
        "load.multiplayer",
        "soak.lifecycle",
    )
    args("catalogue", "--output", report.get().asFile.absolutePath)
    externalContracts.forEach { contract -> args("--external", contract) }
    if (providers.gradleProperty("benchmarkReleaseReady").map(String::toBoolean).getOrElse(false)) {
        args("--release-ready")
    }
    systemProperty("ashlar.benchmark.classDirs", classDirectories.asPath)
    inputs.files(classDirectories)
    outputs.file(report)
}

tasks.named("check") {
    dependsOn(benchmarkCatalogue)
}

evaluationDependsOn(":ashlar-benchmarks")
val benchmarkEngine = project(":ashlar-benchmarks")
val benchmarkEngineMain = benchmarkEngine.extensions.getByType(SourceSetContainer::class.java).getByName("main")

tasks.register<JavaExec>("benchmarkBuild") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Measures Gradle, KSP, generated output, and artifact size."
    dependsOn(benchmarkEngine.tasks.named(benchmarkEngineMain.classesTaskName))
    classpath = benchmarkEngineMain.runtimeClasspath
    mainClass.set("pink.alex.ashlar.benchmarks.BenchmarkCli")
    args(
        "build",
        "--project-dir", layout.projectDirectory.asFile.absolutePath,
        "--output", layout.buildDirectory.file("reports/benchmarks/build.json").get().asFile.absolutePath,
        "--ashlar-version", version.toString(),
        "--revision", providers.gradleProperty("benchmarkRevision").getOrElse("working-tree"),
        "--iterations", providers.gradleProperty("benchmarkBuildIterations").getOrElse("1"),
    )
    outputs.file(layout.buildDirectory.file("reports/benchmarks/build.json"))
    outputs.upToDateWhen { false }
}

tasks.register("benchmarkAll") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Runs every local JVM contract and the build benchmark."
    dependsOn(benchmarkModules.map { module -> module.tasks.named("benchmark") })
    dependsOn("benchmarkBuild", "benchmarkCatalogue", "benchmarkMerge")
}

tasks.register<JavaExec>("benchmarkMerge") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Merges compatible local module results for paired comparison."
    val benchmarkTasks = benchmarkModules.map { module -> module.tasks.named("benchmark") }
    dependsOn(benchmarkTasks)
    dependsOn(benchmarkEngine.tasks.named(benchmarkEngineMain.classesTaskName))
    classpath = benchmarkEngineMain.runtimeClasspath
    mainClass.set("pink.alex.ashlar.benchmarks.BenchmarkCli")
    val results = benchmarkModules.map { module -> module.layout.buildDirectory.file("reports/benchmarks/run.json") }
    args("merge", "--output", layout.buildDirectory.file("reports/benchmarks/local.json").get().asFile.absolutePath)
    results.forEach { result -> args("--input", result.get().asFile.absolutePath) }
    inputs.files(results)
    outputs.file(layout.buildDirectory.file("reports/benchmarks/local.json"))
    outputs.upToDateWhen { false }
}

tasks.register<JavaExec>("benchmarkCompare") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Compares two compatible aggregate benchmark results."
    dependsOn(benchmarkEngine.tasks.named(benchmarkEngineMain.classesTaskName))
    classpath = benchmarkEngineMain.runtimeClasspath
    mainClass.set("pink.alex.ashlar.benchmarks.BenchmarkCli")
    val baseline = providers.gradleProperty("benchmarkBaseline")
    val candidate = providers.gradleProperty("benchmarkCandidate")
    val comparison = layout.buildDirectory.file("reports/benchmarks/comparison.json")
    val markdown = layout.buildDirectory.file("reports/benchmarks/comparison.md")
    args(
        "compare",
        "--baseline", baseline.getOrElse("missing-baseline.json"),
        "--candidate", candidate.getOrElse("missing-candidate.json"),
        "--json", comparison.get().asFile.absolutePath,
        "--markdown", markdown.get().asFile.absolutePath,
    )
    inputs.files(baseline, candidate)
    outputs.files(comparison, markdown)
    outputs.upToDateWhen { false }
}

tasks.register("benchmarkPlatforms") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Runs command, scheduler, load, and soak workloads on pinned Paper and Folia."
    dependsOn(
        ":integration-test-fixture:paperIntegrationTest",
        ":integration-test-fixture:foliaIntegrationTest",
    )
}

tasks.register<Exec>("benchmarkClient") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Measures player-visible native host behavior through the connected Minecraft 26.2 client."
    commandLine(
        "node",
        layout.projectDirectory.file("scripts/benchmark-client.mjs").asFile.absolutePath,
        "--port", providers.gradleProperty("benchmarkClientPort").getOrElse("9877"),
        "--server-port", providers.gradleProperty("benchmarkClientServerPort").getOrElse("25565"),
        "--config", providers.gradleProperty("benchmarkClientConfig")
            .getOrElse("/opt/minecraft-test/secondary/game/config/debugbridge.json"),
        "--output", layout.buildDirectory.file("reports/benchmarks/client.json").get().asFile.absolutePath,
        "--profile", providers.gradleProperty("benchmarkClientProfile").getOrElse("small"),
        "--ashlar-version", version.toString(),
        "--revision", providers.gradleProperty("benchmarkRevision").getOrElse("working-tree"),
    )
    outputs.file(layout.buildDirectory.file("reports/benchmarks/client.json"))
    outputs.upToDateWhen { false }
}
