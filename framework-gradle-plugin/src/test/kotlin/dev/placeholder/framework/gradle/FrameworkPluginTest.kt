package dev.placeholder.framework.gradle

import org.gradle.api.GradleException
import org.gradle.api.internal.project.ProjectInternal
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.testfixtures.ProjectBuilder
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FrameworkPluginTest {
    @TempDir
    lateinit var projectDirectory: Path

    @Test
    fun `plugin applies managed dependencies and packaging`() {
        val project = ProjectBuilder.builder().withProjectDir(projectDirectory.toFile()).build()
        project.version = "1.2.3"
        project.pluginManager.apply(FrameworkPlugin::class.java)
        val extension = project.extensions.getByType(FrameworkPluginExtension::class.java)
        val java = project.extensions.getByType(JavaPluginExtension::class.java)

        extension.mainClass.set("example.ExamplePlugin")
        extension.foliaSupported()
        assertEquals(25, java.toolchain.languageVersion.get().asInt())
        java.toolchain.languageVersion.set(JavaLanguageVersion.of(21))
        (project as ProjectInternal).evaluate()

        assertEquals(extension, project.extensions.getByName("frameworkPlugin"))
        assertTrue(project.pluginManager.hasPlugin("org.jetbrains.kotlin.jvm"))
        assertTrue(project.pluginManager.hasPlugin("com.gradleup.shadow"))
        assertTrue(project.repositories.any { it.name == "PaperMC" })
        assertEquals(
            "",
            project.tasks.named("shadowJar", com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar::class.java)
                .get().archiveClassifier.get(),
        )
        assertTrue(
            project.configurations.getByName("compileOnly").dependencies.any {
                it.group == "io.papermc.paper" && it.name == "paper-api"
            },
        )
        assertTrue(
            project.configurations.getByName("implementation").dependencies.any {
                it.group == "dev.placeholder.framework" && it.name == "kernel"
            },
        )
    }

    @Test
    fun `plugin creates an isolated benchmark source set and standard tasks`() {
        val project = ProjectBuilder.builder().withProjectDir(projectDirectory.toFile()).build()
        project.version = "1.2.3"
        project.pluginManager.apply(FrameworkPlugin::class.java)
        val extension = project.extensions.getByType(FrameworkPluginExtension::class.java)
        extension.mainClass.set("example.ExamplePlugin")
        project.extensions.getByType(JavaPluginExtension::class.java)
            .toolchain.languageVersion.set(JavaLanguageVersion.of(21))
        (project as ProjectInternal).evaluate()

        val sourceSets = project.extensions.getByType(SourceSetContainer::class.java)
        val benchmark = sourceSets.getByName("benchmark")

        assertTrue(project.configurations.getByName(benchmark.implementationConfigurationName)
            .extendsFrom.contains(project.configurations.getByName("implementation")))
        assertTrue(project.configurations.getByName(benchmark.compileOnlyConfigurationName)
            .extendsFrom.contains(project.configurations.getByName("compileOnly")))
        assertTrue(
            project.tasks.names.containsAll(
                setOf("benchmark", "benchmarkJmh", "benchmarkDiagnose", "benchmarkCompare", "benchmarkReport"),
            ),
        )
        assertTrue(project.tasks.names.contains("compileBenchmarkKotlin"))
    }

    @Test
    fun `commands enables runtime and both focused processors`() {
        val project = ProjectBuilder.builder().withProjectDir(projectDirectory.toFile()).build()
        project.version = "1.2.3"
        project.pluginManager.apply(FrameworkPlugin::class.java)
        val extension = project.extensions.getByType(FrameworkPluginExtension::class.java)

        extension.mainClass.set("example.ExamplePlugin")
        extension.commands()
        project.extensions.getByType(JavaPluginExtension::class.java)
            .toolchain.languageVersion.set(JavaLanguageVersion.of(21))
        (project as ProjectInternal).evaluate()

        assertTrue(project.pluginManager.hasPlugin("com.google.devtools.ksp"))
        assertTrue(project.configurations.getByName("implementation").dependencies.any {
            it.name == "framework-commands"
        })
        assertEquals(
            setOf("framework-di-ksp", "framework-commands-ksp"),
            project.configurations.getByName("ksp").dependencies.mapTo(mutableSetOf()) { it.name },
        )
    }

    @Test
    fun `events enables runtime and both focused processors`() {
        val project = ProjectBuilder.builder().withProjectDir(projectDirectory.toFile()).build()
        project.version = "1.2.3"
        project.pluginManager.apply(FrameworkPlugin::class.java)
        val extension = project.extensions.getByType(FrameworkPluginExtension::class.java)

        extension.mainClass.set("example.ExamplePlugin")
        extension.events()
        project.extensions.getByType(JavaPluginExtension::class.java)
            .toolchain.languageVersion.set(JavaLanguageVersion.of(21))
        (project as ProjectInternal).evaluate()

        assertTrue(project.pluginManager.hasPlugin("com.google.devtools.ksp"))
        assertTrue(project.configurations.getByName("implementation").dependencies.any {
            it.name == "framework-events"
        })
        assertEquals(
            setOf("framework-di-ksp", "framework-events-ksp"),
            project.configurations.getByName("ksp").dependencies.mapTo(mutableSetOf()) { it.name },
        )
    }

    @Test
    fun `input enables its runtime and the event processors`() {
        val project = ProjectBuilder.builder().withProjectDir(projectDirectory.toFile()).build()
        project.version = "1.2.3"
        project.pluginManager.apply(FrameworkPlugin::class.java)
        val extension = project.extensions.getByType(FrameworkPluginExtension::class.java)

        extension.mainClass.set("example.ExamplePlugin")
        extension.input()
        project.extensions.getByType(JavaPluginExtension::class.java)
            .toolchain.languageVersion.set(JavaLanguageVersion.of(21))
        (project as ProjectInternal).evaluate()

        assertTrue(project.configurations.getByName("implementation").dependencies.any {
            it.name == "framework-input"
        })
        assertTrue(project.configurations.getByName("implementation").dependencies.any {
            it.name == "framework-events"
        })
        assertEquals(
            setOf("framework-di-ksp", "framework-events-ksp"),
            project.configurations.getByName("ksp").dependencies.mapTo(mutableSetOf()) { it.name },
        )
    }

    @Test
    fun `items enables only the item runtime`() {
        val project = ProjectBuilder.builder().withProjectDir(projectDirectory.toFile()).build()
        project.version = "1.2.3"
        project.pluginManager.apply(FrameworkPlugin::class.java)
        val extension = project.extensions.getByType(FrameworkPluginExtension::class.java)

        extension.mainClass.set("example.ExamplePlugin")
        extension.items()
        project.extensions.getByType(JavaPluginExtension::class.java)
            .toolchain.languageVersion.set(JavaLanguageVersion.of(21))
        (project as ProjectInternal).evaluate()

        assertTrue(project.configurations.getByName("implementation").dependencies.any {
            it.name == "framework-items"
        })
        assertFalse(project.pluginManager.hasPlugin("com.google.devtools.ksp"))
    }

    @Test
    fun `menus enables its runtime and item dependency without code generation`() {
        val project = ProjectBuilder.builder().withProjectDir(projectDirectory.toFile()).build()
        project.version = "1.2.3"
        project.pluginManager.apply(FrameworkPlugin::class.java)
        val extension = project.extensions.getByType(FrameworkPluginExtension::class.java)

        extension.mainClass.set("example.ExamplePlugin")
        extension.menus()
        project.extensions.getByType(JavaPluginExtension::class.java)
            .toolchain.languageVersion.set(JavaLanguageVersion.of(21))
        (project as ProjectInternal).evaluate()

        val implementation = project.configurations.getByName("implementation").dependencies
            .mapTo(mutableSetOf()) { it.name }
        assertTrue("framework-items" in implementation)
        assertTrue("framework-menus" in implementation)
        assertFalse(project.pluginManager.hasPlugin("com.google.devtools.ksp"))
    }

    @Test
    fun `managed version overrides require an explicit reason`() {
        val project = ProjectBuilder.builder().withProjectDir(projectDirectory.toFile()).build()
        val extension = project.objects.newInstance(FrameworkPluginExtension::class.java, project)

        assertFailsWith<IllegalStateException> {
            extension.paperApiVersion("26.3.build.1-stable")
        }

        extension.allowVersionOverrides("Testing the next server line")
        extension.paperApiVersion("26.3.build.1-stable")

        assertEquals("26.3.build.1-stable", extension.paperApiVersion.get())
    }

    @Test
    fun `descriptor generation omits Folia flag by default`() {
        val project = ProjectBuilder.builder().withProjectDir(projectDirectory.toFile()).build()
        val task = project.descriptorTask(folia = false)

        task.generate()

        val descriptor = task.outputFile.get().asFile.readText()
        assertFalse(descriptor.contains("folia-supported"))
        assertTrue(descriptor.contains("api-version: '26.2'"))
    }

    @Test
    fun `descriptor generation emits Folia flag after acknowledgement`() {
        val project = ProjectBuilder.builder().withProjectDir(projectDirectory.toFile()).build()
        val task = project.descriptorTask(folia = true)

        task.generate()

        assertTrue(task.outputFile.get().asFile.readText().contains("folia-supported: true"))
    }

    @Test
    fun `descriptor generation rejects a handwritten descriptor`() {
        val project = ProjectBuilder.builder().withProjectDir(projectDirectory.toFile()).build()
        val descriptor = projectDirectory.resolve("src/main/resources/plugin.yml").toFile().apply {
            parentFile.mkdirs()
            writeText("name: Handwritten")
        }
        val task = project.descriptorTask(folia = false).apply {
            existingDescriptor.set(descriptor)
            existingDescriptorPresent.set(true)
        }

        val failure = assertFailsWith<GradleException> { task.generate() }

        assertTrue(failure.message.orEmpty().contains("Remove src/main/resources/plugin.yml"))
    }

    @Test
    fun `version override rejects ranges even after acknowledgement`() {
        val project = ProjectBuilder.builder().withProjectDir(projectDirectory.toFile()).build()
        val extension = project.objects.newInstance(FrameworkPluginExtension::class.java, project)
        extension.allowVersionOverrides("Compatibility investigation")

        assertFailsWith<IllegalArgumentException> {
            extension.frameworkVersion("[0.1,0.2)")
        }
    }
}

private fun org.gradle.api.Project.descriptorTask(folia: Boolean): GeneratePluginYaml =
    tasks.register("generate", GeneratePluginYaml::class.java).get().apply {
        pluginName.set("Example")
        pluginVersion.set("1.0.0")
        mainClass.set("example.ExamplePlugin")
        apiVersion.set("26.2.build.121-stable")
        foliaSupported.set(folia)
        pluginDescription.set("")
        authors.set(emptyList())
        existingDescriptorPresent.set(false)
        outputFile.set(layout.buildDirectory.file("plugin.yml"))
    }
