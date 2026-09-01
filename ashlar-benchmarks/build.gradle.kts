import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("ashlar.published-library")
    alias(libs.plugins.kotlin.serialization)
}

description = "Test-only performance contracts and benchmark runners for framework plug-ins"

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

kotlin {
    compilerOptions.jvmTarget.set(JvmTarget.JVM_21)
}

dependencies {
    api(libs.coroutines.core)
    api(libs.kotlinx.serialization.json)
    implementation(libs.jmh.core)
    annotationProcessor(libs.jmh.generator)

    testImplementation(libs.coroutines.test)
}
