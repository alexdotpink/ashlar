plugins {
    id("framework.published-library")
    id("framework.benchmark-contracts")
    alias(libs.plugins.ksp)
}

description = "Typed player input for framework plug-ins"

dependencies {
    api(project(":kernel"))
    api(project(":framework-events"))
    compileOnlyApi(libs.paper.api)
    ksp(project(":framework-di-ksp"))

    testImplementation(libs.paper.api)
    testImplementation(libs.coroutines.test)
}
