plugins {
    id("ashlar.published-library")
    alias(libs.plugins.kotlin.serialization)
}

description = "Typed, validated, lossless plug-in configuration"

dependencies {
    api(project(":ashlar-di"))
    api(libs.coroutines.core)
    api(libs.kotlinx.serialization.core)
    compileOnlyApi(libs.paper.api)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.snakeyaml.engine)
    implementation(libs.tomlj)
    implementation(libs.checker.qual)

    testImplementation(libs.paper.api)
    testImplementation(libs.coroutines.test)
}
