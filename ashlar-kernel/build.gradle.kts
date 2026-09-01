plugins {
    id("ashlar.published-library")
    id("ashlar.benchmark-contracts")
}

description = "Lifecycle and Folia-safe coroutine kernel for framework plug-ins"

dependencies {
    api(project(":ashlar-di"))
    api(libs.coroutines.core)
    compileOnlyApi(libs.paper.api)

    testImplementation(libs.coroutines.test)
    testImplementation(libs.paper.api)
    add("benchmarkImplementation", project(":ashlar-testkit"))
}
