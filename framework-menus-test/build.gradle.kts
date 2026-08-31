import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("framework.published-library")
}

description = "Deterministic server-free tests for framework menus"

dependencies {
    api(project(":framework-menus"))
    api(libs.coroutines.test)
    compileOnlyApi(project(":framework-input"))
    compileOnlyApi(libs.paper.api)

    testImplementation(project(":framework-input"))
    testImplementation(libs.paper.api)
}

tasks.named<KotlinCompile>("compileKotlin") {
    dependsOn(":framework-menus:compileKotlin")
    val menuClasses = project(":framework-menus").layout.buildDirectory.dir("classes/kotlin/main")
    compilerOptions.freeCompilerArgs.add("-Xfriend-paths=${menuClasses.get().asFile.absolutePath}")
}
