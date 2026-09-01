import xyz.jpenilla.runpaper.task.RunServer

plugins {
    id("ashlar.kotlin-library")
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.shadow)
    alias(libs.plugins.run.paper)
}

description = "Executable Paper and Folia integration fixture"

dependencies {
    implementation(project(":ashlar-kernel"))
    implementation(project(":ashlar-commands"))
    implementation(project(":ashlar-events"))
    implementation(project(":ashlar-input"))
    implementation(project(":ashlar-menus"))
    implementation(project(":ashlar-items"))
    implementation(project(":ashlar-config"))
    implementation(project(":ashlar-benchmarks"))
    implementation(libs.coroutines.core)
    compileOnly(libs.paper.api)
    ksp(project(":ashlar-commands-ksp"))
    ksp(project(":ashlar-events-ksp"))
    ksp(project(":ashlar-config-ksp"))
    ksp(project(":ashlar-di-ksp"))
}

tasks.shadowJar {
    archiveClassifier.set("")
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    mergeServiceFiles()
}

tasks.jar {
    archiveClassifier.set("plain")
}

tasks.assemble {
    dependsOn(tasks.shadowJar)
}

tasks.processResources {
    val properties = mapOf("version" to project.version.toString())
    inputs.properties(properties)
    filesMatching("plugin.yml") {
        expand(properties)
    }
}

tasks.runServer {
    minecraftVersion("26.2")
    build(libs.versions.paperBuildNumber.get().toInt())
    args("--port", "25566")
    systemProperty("com.mojang.eula.agree", "true")
    systemProperty("ashlar.benchmark.soakSeconds", providers.gradleProperty("benchmarkSoakSeconds").getOrElse("1"))
    systemProperty("ashlar.benchmark.revision", providers.gradleProperty("benchmarkRevision").getOrElse("working-tree"))
    runDirectory.set(layout.buildDirectory.dir("run/paper"))
    pluginJars.from(tasks.shadowJar.flatMap { it.archiveFile })
    verifyFixtureReceipt()
}

runPaper.folia.registerTask {
    minecraftVersion("26.2")
    build(libs.versions.foliaBuildNumber.get().toInt())
    args("--port", "25567")
    systemProperty("com.mojang.eula.agree", "true")
    systemProperty("ashlar.benchmark.soakSeconds", providers.gradleProperty("benchmarkSoakSeconds").getOrElse("1"))
    systemProperty("ashlar.benchmark.revision", providers.gradleProperty("benchmarkRevision").getOrElse("working-tree"))
    runDirectory.set(layout.buildDirectory.dir("run/folia"))
    pluginJars.from(tasks.shadowJar.flatMap { it.archiveFile })
    verifyFixtureReceipt()
}

tasks.register("paperIntegrationTest") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Runs the integration fixture on pinned Paper 26.2."
    dependsOn(tasks.named<RunServer>("runServer"))
}

tasks.register("foliaIntegrationTest") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Runs the integration fixture on pinned Folia 26.2."
    dependsOn(tasks.named<RunServer>("runFolia"))
}

fun RunServer.verifyFixtureReceipt() {
    val receipt = runDirectory.file("fixture-result.txt")
    val dataDirectory = runDirectory.dir("plugins/AshlarIntegrationFixture")
    val latestLog = runDirectory.file("logs/latest.log")
    outputs.upToDateWhen { false }
    doFirst {
        receipt.get().asFile.delete()
        dataDirectory.get().asFile.deleteRecursively()
    }
    doLast {
        val resultFile = receipt.get().asFile
        check(resultFile.isFile && resultFile.useLines { it.firstOrNull() } == "PASS") {
            "Integration fixture did not write a PASS receipt: ${resultFile.absolutePath}"
        }
        val logFile = latestLog.get().asFile
        check(logFile.isFile) { "Integration server did not write its lifecycle log: ${logFile.absolutePath}" }
        val log = logFile.readText()
        check("A lifecycle action failed while closing dependency initializers" !in log) {
            "A configuration watcher or another dependency-initializer resource failed to close"
        }
    }
}
