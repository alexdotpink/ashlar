import xyz.jpenilla.runpaper.task.RunServer

plugins {
    id("ashlar.kotlin-library")
    id("ashlar.benchmark-contracts")
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.shadow)
    alias(libs.plugins.run.paper)
}

description = "Runnable example plug-in built with the framework kernel"

dependencies {
    implementation(project(":ashlar-kernel"))
    implementation(project(":ashlar-commands"))
    implementation(project(":ashlar-events"))
    implementation(project(":ashlar-input"))
    implementation(project(":ashlar-items"))
    implementation(project(":ashlar-menus"))
    implementation(project(":ashlar-config"))
    implementation(libs.coroutines.core)
    compileOnly(libs.paper.api)
    testImplementation(libs.paper.api)
    testImplementation(project(":ashlar-menus-test"))
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
    systemProperty("com.mojang.eula.agree", "true")
    runDirectory.set(layout.buildDirectory.dir("run/paper"))
    pluginJars.from(tasks.shadowJar.flatMap { it.archiveFile })
}

runPaper.folia.registerTask {
    minecraftVersion("26.2")
    build(libs.versions.foliaBuildNumber.get().toInt())
    systemProperty("com.mojang.eula.agree", "true")
    runDirectory.set(layout.buildDirectory.dir("run/folia"))
    pluginJars.from(tasks.shadowJar.flatMap { it.archiveFile })
}

tasks.register("runSamplePaper") {
    group = "ashlar"
    description = "Builds the sample and starts pinned Paper with it installed."
    dependsOn(tasks.named<RunServer>("runServer"))
}

tasks.register("runSampleFolia") {
    group = "ashlar"
    description = "Builds the sample and starts pinned Folia with it installed."
    dependsOn(tasks.named<RunServer>("runFolia"))
}
