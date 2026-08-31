plugins {
    id("framework.published-library")
    id("framework.benchmark-contracts")
    alias(libs.plugins.kotlin.serialization)
}

description = "Immutable items, snapshots, and typed custom items for framework plug-ins"

dependencies {
    api(libs.kotlinx.serialization.json)
    compileOnlyApi(libs.paper.api)

    testImplementation(libs.paper.api)
}
