import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("ashlar.published-library")
}

description = "Deterministic server-free tests for framework menus"

dependencies {
    api(project(":ashlar-menus"))
    api(libs.coroutines.test)
    compileOnlyApi(project(":ashlar-input"))
    compileOnlyApi(libs.paper.api)

    testImplementation(project(":ashlar-input"))
    testImplementation(libs.paper.api)
}

tasks.named<KotlinCompile>("compileKotlin") {
    dependsOn(":ashlar-menus:compileKotlin")
    val menuClasses = project(":ashlar-menus").layout.buildDirectory.dir("classes/kotlin/main")
    compilerOptions.freeCompilerArgs.add("-Xfriend-paths=${menuClasses.get().asFile.absolutePath}")
}
