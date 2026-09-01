plugins {
    id("ashlar.published-library")
    id("ashlar.benchmark-contracts")
    alias(libs.plugins.ksp)
}

description = "Typed player input for framework plug-ins"

dependencies {
    api(project(":ashlar-kernel"))
    api(project(":ashlar-events"))
    compileOnlyApi(libs.paper.api)
    ksp(project(":ashlar-di-ksp"))

    testImplementation(libs.paper.api)
    testImplementation(libs.coroutines.test)
}
