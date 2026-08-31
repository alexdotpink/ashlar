plugins {
    `kotlin-dsl`
}

group = "dev.placeholder.framework.buildlogic"

dependencies {
    implementation(libs.kotlin.gradle)
    implementation(libs.dokka.gradle)
}

kotlin {
    // Included-build plugins execute inside Gradle, whose supported host JVM is 17+.
    // Target libraries still compile with the Java 25 toolchain in the convention.
    jvmToolchain(21)
}
