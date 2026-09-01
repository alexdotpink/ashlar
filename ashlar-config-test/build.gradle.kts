plugins {
    id("ashlar.published-library")
}

description = "Deterministic server-free tests for Ashlar configuration"

dependencies {
    api(project(":ashlar-config"))
    api(libs.coroutines.test)
}
