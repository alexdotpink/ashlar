import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("framework.published-library")
    id("framework.benchmark-contracts")
    alias(libs.plugins.ksp)
}

description = "Typed dependency graph runtime for framework plug-ins"

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

kotlin {
    compilerOptions.jvmTarget.set(JvmTarget.JVM_21)
}

dependencies {
    add("kspBenchmark", project(":framework-di-ksp"))
}
