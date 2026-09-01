pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/") {
            name = "PaperMC"
            content {
                includeGroup("io.papermc.paper")
                includeGroup("dev.folia")
                includeGroup("com.mojang")
                includeGroup("net.md-5")
            }
        }
    }
}

rootProject.name = "ashlar"

includeBuild("build-logic")

include(
    "ashlar-kernel",
    "ashlar-di",
    "ashlar-di-ksp",
    "ashlar-commands",
    "ashlar-commands-ksp",
    "ashlar-events",
    "ashlar-events-ksp",
    "ashlar-input",
    "ashlar-items",
    "ashlar-menus",
    "ashlar-menus-test",
    "ashlar-benchmarks",
    "ashlar-gradle-plugin",
    "ashlar-bom",
    "ashlar-testkit",
    "ashlar-incubator",
    "integration-test-fixture",
    "sample-plugin",
)

project(":sample-plugin").projectDir = file("samples/sample-plugin")
