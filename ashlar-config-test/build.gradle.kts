plugins {
    id("ashlar.published-library")
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

description = "Deterministic server-free tests for Ashlar configuration"

val processorFixture = sourceSets.create("processorFixture")

dependencies {
    api(project(":ashlar-config"))
    api(libs.coroutines.test)
    add("processorFixtureImplementation", project(":ashlar-config"))
    add("kspProcessorFixture", project(":ashlar-config-ksp"))
    add("kspProcessorFixture", project(":ashlar-di-ksp"))
}

tasks.check {
    dependsOn(tasks.named("compileProcessorFixtureKotlin"))
}
