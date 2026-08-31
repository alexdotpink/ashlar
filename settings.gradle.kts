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

rootProject.name = "framework"

includeBuild("build-logic")

include(
    "kernel",
    "framework-di",
    "framework-di-ksp",
    "framework-commands",
    "framework-commands-ksp",
    "framework-events",
    "framework-events-ksp",
    "framework-input",
    "framework-items",
    "framework-menus",
    "framework-menus-test",
    "framework-gradle-plugin",
    "framework-bom",
    "framework-testkit",
    "framework-incubator",
    "integration-test-fixture",
    "sample-plugin",
)

project(":sample-plugin").projectDir = file("samples/sample-plugin")
