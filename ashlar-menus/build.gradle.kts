plugins {
    id("ashlar.published-library")
    id("ashlar.benchmark-contracts")
    alias(libs.plugins.ksp)
}

description = "Declarative, stateful Minecraft inventory menus"

dependencies {
    api(project(":ashlar-kernel"))
    api(project(":ashlar-items"))
    api(libs.coroutines.core)
    compileOnlyApi(project(":ashlar-input"))
    compileOnlyApi(libs.paper.api)
    ksp(project(":ashlar-di-ksp"))

    testImplementation(libs.paper.api)
    testImplementation(libs.coroutines.test)
    add("benchmarkImplementation", project(":ashlar-menus-test"))
    add("benchmarkImplementation", project(":ashlar-input"))
}
