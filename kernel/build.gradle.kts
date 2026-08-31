plugins {
    id("framework.published-library")
}

description = "Lifecycle and Folia-safe coroutine kernel for framework plug-ins"

dependencies {
    api(project(":framework-di"))
    api(libs.coroutines.core)
    compileOnlyApi(libs.paper.api)

    testImplementation(libs.coroutines.test)
    testImplementation(libs.paper.api)
}
