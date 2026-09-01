package pink.alex.ashlar.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

/** Generates the standard Paper `plugin.yml` descriptor. */
@CacheableTask
public abstract class GeneratePluginYaml : DefaultTask() {
    /** Plug-in name. */
    @get:Input
    public abstract val pluginName: Property<String>

    /** Plug-in release version. */
    @get:Input
    public abstract val pluginVersion: Property<String>

    /** Fully qualified JavaPlugin implementation. */
    @get:Input
    public abstract val mainClass: Property<String>

    /** Paper API line. */
    @get:Input
    public abstract val apiVersion: Property<String>

    /** Whether [AshlarPluginExtension.foliaSupported] was called. */
    @get:Input
    public abstract val foliaSupported: Property<Boolean>

    /** Optional plug-in description. */
    @get:Input
    public abstract val pluginDescription: Property<String>

    /** Plug-in authors. */
    @get:Input
    public abstract val authors: ListProperty<String>

    /** Optional project URL. */
    @get:Input
    @get:Optional
    public abstract val website: Property<String>

    /** Hand-written descriptor whose presence would make generation ambiguous. */
    @get:Internal
    public abstract val existingDescriptor: RegularFileProperty

    /** Whether a hand-written descriptor currently occupies the standard source path. */
    @get:Input
    public abstract val existingDescriptorPresent: Property<Boolean>

    /** Generated descriptor destination. */
    @get:OutputFile
    public abstract val outputFile: RegularFileProperty

    /** Writes a validated descriptor. */
    @TaskAction
    public fun generate() {
        if (existingDescriptorPresent.get()) {
            throw GradleException(
                "Remove src/main/resources/plugin.yml; the framework Gradle plugin generates it.",
            )
        }
        val main = mainClass.orNull?.trim().orEmpty()
        if (main.isEmpty()) {
            throw GradleException("Set framework.mainClass to the JavaPlugin implementation class.")
        }

        val version = pluginVersion.get().trim()
        if (version.isEmpty() || version == "unspecified") {
            throw GradleException("Set project.version before generating plugin.yml.")
        }

        val lines = buildList {
            add("name: ${pluginName.get().yaml()}")
            add("version: ${version.yaml()}")
            add("main: ${main.yaml()}")
            add("api-version: ${apiVersion.get().substringBefore(".build.").yaml()}")
            if (foliaSupported.get()) {
                add("folia-supported: true")
            }
            pluginDescription.orNull?.takeIf(String::isNotBlank)?.let { add("description: ${it.yaml()}") }
            website.orNull?.takeIf(String::isNotBlank)?.let { add("website: ${it.yaml()}") }
            authors.get().takeIf(List<String>::isNotEmpty)?.let { values ->
                add("authors: [${values.joinToString(", ") { it.yaml() }}]")
            }
        }

        outputFile.get().asFile.apply {
            parentFile.mkdirs()
            writeText(lines.joinToString(System.lineSeparator(), postfix = System.lineSeparator()))
        }
    }
}

private fun String.yaml(): String = "'${replace("'", "''")}'"
