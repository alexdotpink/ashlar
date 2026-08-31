import xyz.jpenilla.runpaper.task.RunServer

plugins {
    id("framework.kotlin-library")
    id("framework.benchmark-contracts")
    alias(libs.plugins.ksp)
    alias(libs.plugins.shadow)
    alias(libs.plugins.run.paper)
}

description = "Runnable example plug-in built with the framework kernel"

dependencies {
    implementation(project(":kernel"))
    implementation(project(":framework-commands"))
    implementation(project(":framework-events"))
    implementation(project(":framework-input"))
    implementation(project(":framework-items"))
    implementation(project(":framework-menus"))
    implementation(libs.coroutines.core)
    compileOnly(libs.paper.api)
    testImplementation(libs.paper.api)
    ksp(project(":framework-commands-ksp"))
    ksp(project(":framework-events-ksp"))
    ksp(project(":framework-di-ksp"))
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
    group = "framework"
    description = "Builds the sample and starts pinned Paper with it installed."
    dependsOn(tasks.named<RunServer>("runServer"))
}

tasks.register("runSampleFolia") {
    group = "framework"
    description = "Builds the sample and starts pinned Folia with it installed."
    dependsOn(tasks.named<RunServer>("runFolia"))
}
