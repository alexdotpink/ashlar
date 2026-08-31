import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.publish.maven.tasks.PublishToMavenRepository
import org.gradle.api.tasks.bundling.Jar
import org.gradle.plugins.signing.Sign
import org.jetbrains.dokka.gradle.tasks.DokkaGeneratePublicationTask
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation

plugins {
    `java-gradle-plugin`
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.dokka)
    `maven-publish`
    signing
}

description = "Managed Gradle conventions for framework plug-ins"

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
    withSourcesJar()
}

kotlin {
    explicitApi()

    @OptIn(ExperimentalAbiValidation::class)
    abiValidation()

    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
        progressiveMode.set(true)
        allWarningsAsErrors.set(true)
        javaParameters.set(true)
    }
}

dependencies {
    implementation(libs.kotlin.gradle)
    implementation(libs.shadow.gradle)
    implementation(libs.ksp.gradle)

    testImplementation(gradleTestKit())
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.test.junit)
    testRuntimeOnly(libs.junit.launcher)
}

gradlePlugin {
    website.set("https://github.com/placeholder/framework")
    vcsUrl.set("https://github.com/placeholder/framework")
    plugins {
        create("framework") {
            id = "dev.placeholder.framework"
            displayName = "Framework Paper/Folia plug-in conventions"
            description = project.description
            implementationClass = "dev.placeholder.framework.gradle.FrameworkPlugin"
            tags.set(listOf("minecraft", "paper", "folia", "kotlin"))
        }
    }
}

tasks.processResources {
    val versions = mapOf(
        "frameworkVersion" to project.version.toString(),
        "kotlinVersion" to libs.versions.kotlin.get(),
        "paperVersion" to libs.versions.paper.get(),
    )
    inputs.properties(versions)
    filesMatching("dev/placeholder/framework/gradle/framework-version.properties") {
        expand(versions)
    }
}

tasks.test {
    useJUnitPlatform()
}

val dokkaHtml = tasks.named<DokkaGeneratePublicationTask>("dokkaGeneratePublicationHtml")
val dokkaJavadocJar = tasks.register<Jar>("dokkaJavadocJar") {
    description = "Packages Dokka HTML as the published API documentation artifact."
    archiveClassifier.set("javadoc")
    from(dokkaHtml.flatMap { it.outputDirectory })
}

tasks.check {
    dependsOn(dokkaHtml)
}

publishing {
    publications.withType<MavenPublication>().configureEach {
        if (name == "pluginMaven") {
            artifact(dokkaJavadocJar)
        }
        pom {
            name.set("Framework Gradle Plugin")
            description.set(project.description)
            url.set("https://github.com/placeholder/framework")
            licenses {
                license {
                    name.set("The Apache License, Version 2.0")
                    url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                    distribution.set("repo")
                }
            }
            developers {
                developer {
                    id.set("framework-maintainers")
                    name.set("Framework maintainers")
                }
            }
            scm {
                connection.set("scm:git:https://github.com/placeholder/framework.git")
                developerConnection.set("scm:git:ssh://git@github.com/placeholder/framework.git")
                url.set("https://github.com/placeholder/framework")
            }
        }
    }
    repositories {
        maven {
            name = "buildRepository"
            url = rootProject.layout.buildDirectory.dir("repository").get().asFile.toURI()
        }
        providers.environmentVariable("MAVEN_CENTRAL_URL").orNull?.let { centralUrl ->
            maven {
                name = "mavenCentralRelease"
                url = uri(centralUrl)
                credentials {
                    username = providers.environmentVariable("MAVEN_CENTRAL_USERNAME").orNull
                    password = providers.environmentVariable("MAVEN_CENTRAL_PASSWORD").orNull
                }
            }
        }
    }
}

val signingKey = providers.gradleProperty("signingInMemoryKey")
    .orElse(providers.environmentVariable("MAVEN_SIGNING_KEY"))
val signingPassword = providers.gradleProperty("signingInMemoryKeyPassword")
    .orElse(providers.environmentVariable("MAVEN_SIGNING_PASSWORD"))

signing {
    if (signingKey.isPresent) {
        useInMemoryPgpKeys(signingKey.get(), signingPassword.orNull)
        sign(publishing.publications)
    }
}

tasks.withType<Sign>().configureEach {
    onlyIf { signingKey.isPresent }
}

tasks.withType<PublishToMavenRepository>().configureEach {
    doFirst {
        if (repository.name == "mavenCentralRelease") {
            val identity = listOf(
                project.group.toString(),
                "https://github.com/placeholder/framework",
                "scm:git:https://github.com/placeholder/framework.git",
            )
            check(identity.none { it.contains("placeholder", ignoreCase = true) }) {
                "Refusing Maven Central publication with placeholder coordinates or POM identity."
            }
        }
    }
}
