plugins {
    id("ashlar.published-library")
    id("ashlar.benchmark-contracts")
    alias(libs.plugins.ksp)
}

description = "Typed command runtime for framework plug-ins"

dependencies {
    api(project(":ashlar-kernel"))
    compileOnlyApi(libs.paper.api)
    ksp(project(":ashlar-di-ksp"))

    testImplementation(libs.paper.api)
    testImplementation(libs.coroutines.test)
}
