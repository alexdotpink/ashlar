import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("framework.published-library")
}

description = "Minimal KSP constructor factories and dependency contribution indexes"

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

kotlin {
    compilerOptions.jvmTarget.set(JvmTarget.JVM_21)
}

dependencies {
    implementation(libs.ksp.api)
    implementation(libs.kotlinpoet)
}
