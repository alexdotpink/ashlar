plugins {
    id("ashlar.published-library")
}

description = "Explicitly unstable experiments for the framework"

dependencies {
    api(project(":ashlar-kernel"))
    compileOnlyApi(libs.paper.api)

    testImplementation(libs.paper.api)
}
