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
        api(project(":kernel"))
        api(project(":framework-di"))
        api(project(":framework-di-ksp"))
        api(project(":framework-commands"))
        api(project(":framework-commands-ksp"))
        api(project(":framework-events"))
        api(project(":framework-events-ksp"))
        api(project(":framework-testkit"))
        api(project(":framework-incubator"))
        api("dev.placeholder.framework:framework-gradle-plugin:${project.version}")
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
                name.set("Framework BOM")
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
