import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("ashlar.published-library")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

kotlin {
    compilerOptions.jvmTarget.set(JvmTarget.JVM_21)
}

description = "Minimal KSP bindings for framework event classes"

dependencies {
    implementation(libs.ksp.api)
    implementation(libs.kotlinpoet)
}
