package dev.placeholder.framework.gradle

import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.artifacts.ExternalModuleDependency
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.JavaExec
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
        configureBenchmarks(project)

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

    private fun configureBenchmarks(project: Project) {
        val sourceSets = project.extensions.getByType(SourceSetContainer::class.java)
        val main = sourceSets.named("main").get()
        val benchmark = sourceSets.maybeCreate("benchmark").apply {
            compileClasspath += main.output
            runtimeClasspath += output + compileClasspath
        }
        project.configurations.named(benchmark.implementationConfigurationName).configure {
            it.extendsFrom(project.configurations.getByName("implementation"))
        }
        project.configurations.named(benchmark.compileOnlyConfigurationName).configure {
            it.extendsFrom(project.configurations.getByName("compileOnly"))
        }
        project.configurations.named(benchmark.runtimeOnlyConfigurationName).configure {
            it.extendsFrom(project.configurations.getByName("runtimeOnly"))
        }

        val reportDirectory = project.layout.buildDirectory.dir("reports/benchmarks")
        val runResult = reportDirectory.map { it.file("run.json") }
        val comparisonResult = reportDirectory.map { it.file("comparison.json") }
        val markdownResult = reportDirectory.map { it.file("summary.md") }
        val benchmarkTask = project.tasks.register("benchmark", JavaExec::class.java) { task ->
            task.group = "verification"
            task.description = "Runs exploratory framework benchmark scenarios."
            task.dependsOn(benchmark.classesTaskName)
            task.classpath = benchmark.runtimeClasspath
            task.mainClass.set("dev.placeholder.framework.benchmarks.BenchmarkCli")
            task.outputs.file(runResult)
            task.outputs.upToDateWhen { false }
            task.doFirst {
                val arguments = mutableListOf(
                    "run",
                    "--output", runResult.get().asFile.absolutePath,
                    "--revision", project.providers.gradleProperty("benchmarkRevision").getOrElse("working-tree"),
                    "--framework-version", project.version.toString(),
                    "--warmups", project.providers.gradleProperty("benchmarkWarmups").getOrElse("5"),
                    "--iterations", project.providers.gradleProperty("benchmarkIterations").getOrElse("20"),
                    "--forks", project.providers.gradleProperty("benchmarkForks").getOrElse("3"),
                )
                project.providers.gradleProperty("benchmarkProfiles").orNull
                    ?.split(',')
                    ?.filter(String::isNotBlank)
                    ?.forEach { profile -> arguments += listOf("--profile", profile) }
                project.providers.gradleProperty("benchmarkScenarios").orNull
                    ?.split(',')
                    ?.filter(String::isNotBlank)
                    ?.forEach { scenario -> arguments += listOf("--scenario", scenario) }
                task.setArgs(arguments)
                task.systemProperty("framework.benchmark.classDirs", benchmark.output.classesDirs.asPath)
            }
        }
        project.tasks.register("benchmarkCompare", JavaExec::class.java) { task ->
            task.group = "verification"
            task.description = "Compares the current benchmark run with a compatible baseline."
            task.dependsOn(benchmarkTask)
            task.classpath = benchmark.runtimeClasspath
            task.mainClass.set("dev.placeholder.framework.benchmarks.BenchmarkCli")
            task.outputs.files(comparisonResult, markdownResult)
            task.outputs.upToDateWhen { false }
            task.doFirst {
                val baseline = project.providers.gradleProperty("benchmarkBaseline").orNull
                    ?: error("benchmarkCompare requires -PbenchmarkBaseline=/path/to/baseline.json")
                task.setArgs(
                    listOf(
                        "compare",
                        "--baseline", baseline,
                        "--candidate", runResult.get().asFile.absolutePath,
                        "--json", comparisonResult.get().asFile.absolutePath,
                        "--markdown", markdownResult.get().asFile.absolutePath,
                    ),
                )
            }
        }
        project.tasks.register("benchmarkReport", JavaExec::class.java) { task ->
            task.group = "reporting"
            task.description = "Renders an existing benchmark comparison."
            task.classpath = benchmark.runtimeClasspath
            task.mainClass.set("dev.placeholder.framework.benchmarks.BenchmarkCli")
            task.doFirst {
                val comparison = project.providers.gradleProperty("benchmarkComparison").orNull
                    ?: comparisonResult.get().asFile.absolutePath
                task.setArgs(listOf("report", "--comparison", comparison, "--format", "text"))
            }
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
