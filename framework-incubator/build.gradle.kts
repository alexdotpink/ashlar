plugins {
    id("framework.published-library")
}

description = "Explicitly unstable experiments for the framework"

dependencies {
    api(project(":kernel"))
    compileOnlyApi(libs.paper.api)

    testImplementation(libs.paper.api)
}
