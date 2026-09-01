plugins {
    id("ashlar.published-library")
    id("ashlar.benchmark-contracts")
    alias(libs.plugins.ksp)
}

description = "Typed event runtime for framework plug-ins"

dependencies {
    api(project(":ashlar-kernel"))
    compileOnlyApi(libs.paper.api)
    ksp(project(":ashlar-di-ksp"))
    add("kspTest", project(":ashlar-di-ksp"))
    add("kspTest", project(":ashlar-events-ksp"))

    testImplementation(libs.paper.api)
    testImplementation(libs.coroutines.test)
    add("kspBenchmark", project(":ashlar-di-ksp"))
    add("kspBenchmark", project(":ashlar-events-ksp"))
}
