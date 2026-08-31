import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("framework.published-library")
}

description = "Typed dependency graph runtime for framework plug-ins"

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

kotlin {
    compilerOptions.jvmTarget.set(JvmTarget.JVM_21)
}
