import org.gradle.api.publish.maven.tasks.PublishToMavenRepository

plugins {
    `java-platform`
    `maven-publish`
    signing
}

description = "Aligned dependency versions for framework modules"

javaPlatform {
    allowDependencies()
}

dependencies {
    constraints {
        api(project(":ashlar-kernel"))
        api(project(":ashlar-di"))
        api(project(":ashlar-di-ksp"))
        api(project(":ashlar-commands"))
        api(project(":ashlar-commands-ksp"))
        api(project(":ashlar-events"))
        api(project(":ashlar-events-ksp"))
        api(project(":ashlar-input"))
        api(project(":ashlar-items"))
        api(project(":ashlar-menus"))
        api(project(":ashlar-menus-test"))
        api(project(":ashlar-benchmarks"))
        api(project(":ashlar-testkit"))
        api(project(":ashlar-incubator"))
        api("pink.alex.ashlar:ashlar-gradle-plugin:${project.version}")
        api(libs.kotlin.stdlib)
        api(libs.kotlin.reflect)
        api(libs.coroutines.core)
        api(libs.coroutines.test)
        api(libs.paper.api)
    }
}

publishing {
    publications {
        create<MavenPublication>("mavenBom") {
            from(components["javaPlatform"])
            pom {
                name.set("Ashlar BOM")
                description.set(project.description)
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
                "https://github.com/alexdotpink/ashlar",
                "scm:git:https://github.com/alexdotpink/ashlar.git",
            )
            check(identity.none { it.contains("placeholder", ignoreCase = true) }) {
                "Refusing Maven Central publication with placeholder coordinates or POM identity."
            }
        }
    }
}
