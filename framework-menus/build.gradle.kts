plugins {
    id("framework.published-library")
    id("framework.benchmark-contracts")
    alias(libs.plugins.ksp)
}

description = "Declarative, stateful Minecraft inventory menus"

dependencies {
    api(project(":kernel"))
    api(project(":framework-items"))
    api(libs.coroutines.core)
    compileOnlyApi(project(":framework-input"))
    compileOnlyApi(libs.paper.api)
    ksp(project(":framework-di-ksp"))

    testImplementation(libs.paper.api)
    testImplementation(libs.coroutines.test)
    add("benchmarkImplementation", project(":framework-menus-test"))
    add("benchmarkImplementation", project(":framework-input"))
}
