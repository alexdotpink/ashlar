plugins {
    id("ashlar.published-library")
}

description = "Typed, validated, lossless plug-in configuration"

dependencies {
    api(libs.coroutines.core)
    api(libs.kotlinx.serialization.core)
    implementation(libs.snakeyaml.engine)
    implementation(libs.tomlj)
    implementation(libs.checker.qual)
}
