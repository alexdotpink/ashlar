import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.publish.maven.tasks.PublishToMavenRepository
import org.gradle.plugins.signing.Sign
import org.gradle.plugins.signing.SigningExtension
import org.jetbrains.dokka.gradle.tasks.DokkaGeneratePublicationTask
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation

plugins {
    id("ashlar.kotlin-library")
    id("org.jetbrains.dokka")
    `maven-publish`
    signing
}

extensions.configure<KotlinJvmProjectExtension> {
    @OptIn(ExperimentalAbiValidation::class)
    abiValidation()
}

val dokkaHtml = tasks.named<DokkaGeneratePublicationTask>("dokkaGeneratePublicationHtml")
val dokkaJavadocJar = tasks.register<Jar>("dokkaJavadocJar") {
    description = "Packages Dokka HTML as the published API documentation artifact."
    archiveClassifier.set("javadoc")
    from(dokkaHtml.flatMap { it.outputDirectory })
}

tasks.named("check") {
    dependsOn(dokkaHtml)
}

extensions.configure<PublishingExtension> {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            artifact(dokkaJavadocJar)

            pom {
                name.set(project.name)
                description.set(project.description ?: "Kotlin framework for Paper and Folia plug-ins")
                url.set("https://github.com/alexdotpink/ashlar")
                licenses {
                    license {
                        name.set("The Apache License, Version 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                        distribution.set("repo")
                    }
                }
                developers {
                    developer {
                        id.set("ashlar-maintainers")
                        name.set("Ashlar maintainers")
                    }
                }
                scm {
                    connection.set("scm:git:https://github.com/alexdotpink/ashlar.git")
                    developerConnection.set("scm:git:ssh://git@github.com/alexdotpink/ashlar.git")
                    url.set("https://github.com/alexdotpink/ashlar")
                }
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
val publications = extensions.getByType<PublishingExtension>().publications

extensions.configure<SigningExtension> {
    if (signingKey.isPresent) {
        useInMemoryPgpKeys(signingKey.get(), signingPassword.orNull)
        sign(publications)
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
                "https://github.com/alexdotpink/ashlar",
                "scm:git:https://github.com/alexdotpink/ashlar.git",
            )
            check(identity.none { it.contains("placeholder", ignoreCase = true) }) {
                "Refusing Maven Central publication with placeholder coordinates or POM identity."
            }
        }
    }
}
