plugins {
    id("framework.published-library")
    alias(libs.plugins.kotlin.serialization)
}

description = "Test-only performance contracts and benchmark runners for framework plug-ins"

dependencies {
    api(libs.coroutines.core)
    api(libs.kotlinx.serialization.json)
    implementation(libs.jmh.core)
    annotationProcessor(libs.jmh.generator)

    testImplementation(libs.coroutines.test)
}
