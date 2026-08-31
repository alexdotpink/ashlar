plugins {
    id("framework.published-library")
    id("framework.benchmark-contracts")
    alias(libs.plugins.ksp)
}

description = "Typed event runtime for framework plug-ins"

dependencies {
    api(project(":kernel"))
    compileOnlyApi(libs.paper.api)
    ksp(project(":framework-di-ksp"))
    add("kspTest", project(":framework-di-ksp"))
    add("kspTest", project(":framework-events-ksp"))

    testImplementation(libs.paper.api)
    testImplementation(libs.coroutines.test)
    add("kspBenchmark", project(":framework-di-ksp"))
    add("kspBenchmark", project(":framework-events-ksp"))
}
