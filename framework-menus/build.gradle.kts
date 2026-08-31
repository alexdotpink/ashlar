plugins {
    id("framework.published-library")
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
}
