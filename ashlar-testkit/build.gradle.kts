import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("ashlar.published-library")
}

description = "Test utilities for framework plug-ins and components"

dependencies {
    api(project(":ashlar-kernel"))
    api(libs.coroutines.test)
    compileOnlyApi(libs.paper.api)

    testImplementation(libs.paper.api)
}

tasks.named<KotlinCompile>("compileKotlin") {
    dependsOn(":ashlar-kernel:compileKotlin")
    val kernelClasses = project(":ashlar-kernel").layout.buildDirectory.dir("classes/kotlin/main")
    compilerOptions.freeCompilerArgs.add("-Xfriend-paths=${kernelClasses.get().asFile.absolutePath}")
}
