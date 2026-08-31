plugins {
    id("framework.published-library")
    alias(libs.plugins.ksp)
}

description = "Typed command runtime for framework plug-ins"

dependencies {
    api(project(":kernel"))
    compileOnlyApi(libs.paper.api)
    ksp(project(":framework-di-ksp"))

    testImplementation(libs.paper.api)
    testImplementation(libs.coroutines.test)
}
