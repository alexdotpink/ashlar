package dev.placeholder.framework.gradle

import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.artifacts.ExternalModuleDependency
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.bundling.Jar
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

/** Applies the managed Kotlin, Paper, dependency alignment, and packaging defaults. */
public class FrameworkPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.pluginManager.apply("org.jetbrains.kotlin.jvm")
        project.pluginManager.apply("com.gradleup.shadow")

        project.repositories.maven { repository ->
            repository.name = "PaperMC"
            repository.setUrl("https://repo.papermc.io/repository/maven-public/")
            repository.content { content ->
                content.includeGroup("io.papermc.paper")
                content.includeGroup("dev.folia")
                content.includeGroup("com.mojang")
                content.includeGroup("net.md-5")
            }
        }

        val extension = project.extensions.create(
            "frameworkPlugin",
            FrameworkPluginExtension::class.java,
            project,
        )

        project.extensions.getByType(JavaPluginExtension::class.java).apply {
            toolchain.languageVersion.set(JavaLanguageVersion.of(25))
        }
        project.extensions.getByType(KotlinJvmProjectExtension::class.java).compilerOptions.apply {
            jvmTarget.set(JvmTarget.JVM_25)
            progressiveMode.set(true)
            javaParameters.set(true)
        }

        configureDescriptor(project, extension)
        configurePackaging(project)

        project.afterEvaluate {
            configureDependencies(project, extension)
        }
    }

    private fun configureDescriptor(project: Project, extension: FrameworkPluginExtension) {
        val generate = project.tasks.register("generateFrameworkPluginYaml", GeneratePluginYaml::class.java) {
            it.group = "framework"
            it.description = "Generates the Paper plugin.yml descriptor."
            it.pluginName.set(extension.pluginName)
            it.pluginVersion.set(project.provider { project.version.toString() })
            it.mainClass.set(extension.mainClass)
            it.apiVersion.set(extension.paperApiVersion)
            it.foliaSupported.set(extension.foliaSupport)
            it.pluginDescription.set(extension.description)
            it.authors.set(extension.authors)
            it.website.set(extension.website)
            val existingDescriptor = project.layout.projectDirectory.file("src/main/resources/plugin.yml")
            it.existingDescriptor.set(existingDescriptor)
            it.existingDescriptorPresent.set(project.provider { existingDescriptor.asFile.exists() })
            it.outputFile.set(project.layout.buildDirectory.file("generated/framework/plugin.yml"))
        }

        project.tasks.named("processResources").configure { processResources ->
            processResources.dependsOn(generate)
            val sourceSets = project.extensions.getByType(SourceSetContainer::class.java)
            sourceSets.named("main").configure {
                it.resources.srcDir(generate.map { task -> task.outputFile.get().asFile.parentFile })
            }
        }
    }

    private fun configurePackaging(project: Project) {
        project.tasks.named("shadowJar", ShadowJar::class.java).configure {
            it.archiveClassifier.set("")
            it.duplicatesStrategy = DuplicatesStrategy.INCLUDE
            it.mergeServiceFiles()
        }
        project.tasks.named("jar", Jar::class.java).configure {
            it.archiveClassifier.set("plain")
        }
        project.tasks.named("assemble").configure {
            it.dependsOn("shadowJar")
        }
    }

    private fun configureDependencies(project: Project, extension: FrameworkPluginExtension) {
        val dependencies = project.dependencies
        val frameworkVersion = extension.frameworkVersion.get()
        val paperVersion = extension.paperApiVersion.get()
        val bom = dependencies.platform(
            "dev.placeholder.framework:framework-bom:$frameworkVersion",
        ) as ExternalModuleDependency
        bom.endorseStrictVersions()

        dependencies.add("implementation", bom)
        dependencies.add("implementation", "dev.placeholder.framework:kernel")
        dependencies.add("compileOnly", "io.papermc.paper:paper-api:$paperVersion")
        if (extension.commandsEnabled || extension.eventsEnabled) {
            dependencies.add("ksp", "dev.placeholder.framework:framework-di-ksp")
        }
        if (extension.commandsEnabled) {
            dependencies.add("implementation", "dev.placeholder.framework:framework-commands")
            dependencies.add("ksp", "dev.placeholder.framework:framework-commands-ksp")
        }
        if (extension.eventsEnabled) {
            dependencies.add("implementation", "dev.placeholder.framework:framework-events")
            dependencies.add("ksp", "dev.placeholder.framework:framework-events-ksp")
        }
        if (extension.inputEnabled) {
            dependencies.add("implementation", "dev.placeholder.framework:framework-input")
        }
        if (extension.itemsEnabled) {
            dependencies.add("implementation", "dev.placeholder.framework:framework-items")
        }
        if (extension.menusEnabled) {
            dependencies.add("implementation", "dev.placeholder.framework:framework-menus")
        }
    }
}
