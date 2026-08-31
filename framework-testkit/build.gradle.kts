import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("framework.published-library")
}

description = "Test utilities for framework plug-ins and components"

dependencies {
    api(project(":kernel"))
    api(libs.coroutines.test)
    compileOnlyApi(libs.paper.api)

    testImplementation(libs.paper.api)
}

tasks.named<KotlinCompile>("compileKotlin") {
    dependsOn(":kernel:compileKotlin")
    val kernelClasses = project(":kernel").layout.buildDirectory.dir("classes/kotlin/main")
    compilerOptions.freeCompilerArgs.add("-Xfriend-paths=${kernelClasses.get().asFile.absolutePath}")
}
