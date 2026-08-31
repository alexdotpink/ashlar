package dev.placeholder.framework.gradle

import com.google.devtools.ksp.gradle.KspExtension
import org.gradle.api.Project
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import javax.inject.Inject

/** Managed descriptor and dependency settings for a framework plug-in. */
public abstract class FrameworkPluginExtension @Inject constructor(private val project: Project) {
    /** Name written to `plugin.yml`. */
    public val pluginName: Property<String> = project.objects.property(String::class.java)
        .convention(project.name.toPluginName())

    /** Fully qualified Paper `JavaPlugin` implementation name. */
    public val mainClass: Property<String> = project.objects.property(String::class.java)

    /** Human-readable plug-in description. */
    public val description: Property<String> = project.objects.property(String::class.java)
        .convention(project.provider { project.description ?: "" })

    /** Plug-in authors written to `plugin.yml`. */
    public val authors: ListProperty<String> = project.objects.listProperty(String::class.java)
        .convention(emptyList())

    /** Optional project URL written to `plugin.yml`. */
    public val website: Property<String> = project.objects.property(String::class.java)

    internal val foliaSupport: Property<Boolean> = project.objects.property(Boolean::class.java)
        .convention(false)

    internal val frameworkVersion: Property<String> = project.objects.property(String::class.java)
        .convention(FrameworkVersions.framework)

    internal val paperApiVersion: Property<String> = project.objects.property(String::class.java)
        .convention(FrameworkVersions.paper)

    private var versionOverridesAllowed: Boolean = false
    internal var commandsEnabled: Boolean = false
        private set
    internal var eventsEnabled: Boolean = false
        private set
    internal var inputEnabled: Boolean = false
        private set

    /** Enables the typed command runtime and its two small KSP processors. */
    public fun commands(strictDocumentation: Boolean = false) {
        if (commandsEnabled) return
        commandsEnabled = true
        project.pluginManager.apply("com.google.devtools.ksp")
        project.extensions.configure(KspExtension::class.java) { ksp ->
            ksp.arg("framework.commands.strictDocumentation", strictDocumentation.toString())
        }
    }

    /** Enables the typed event runtime and its small KSP processor. */
    public fun events() {
        if (eventsEnabled) return
        eventsEnabled = true
        project.pluginManager.apply("com.google.devtools.ksp")
    }

    /** Enables typed player input and its event dependency. */
    public fun input() {
        if (inputEnabled) return
        inputEnabled = true
        events()
    }

    /**
     * Acknowledges that the plug-in only uses Folia-safe ownership APIs and writes
     * `folia-supported: true` to its descriptor.
     */
    public fun foliaSupported() {
        foliaSupport.set(true)
    }

    /**
     * Unlocks exact managed-version overrides after recording why the aligned release
     * cannot be used. Overrides are intentionally exceptional and never accept ranges.
     */
    public fun allowVersionOverrides(reason: String) {
        require(reason.isNotBlank()) { "A non-blank compatibility reason is required." }
        versionOverridesAllowed = true
    }

    /** Overrides the aligned framework release with one exact version. */
    public fun frameworkVersion(version: String) {
        requireOverrideAllowed()
        val exactVersion = version.requireExactVersion("framework")
        project.logger.warn(
            "Using framework version {} outside the tested alignment {}.",
            exactVersion,
            FrameworkVersions.framework,
        )
        frameworkVersion.set(exactVersion)
    }

    /** Overrides the pinned Paper API with one exact version. */
    public fun paperApiVersion(version: String) {
        requireOverrideAllowed()
        val exactVersion = version.requireExactVersion("Paper API")
        project.logger.warn(
            "Using Paper API version {} outside the tested pin {}.",
            exactVersion,
            FrameworkVersions.paper,
        )
        paperApiVersion.set(exactVersion)
    }

    private fun requireOverrideAllowed() {
        check(versionOverridesAllowed) {
            "Call allowVersionOverrides(reason) before overriding managed versions."
        }
    }
}

private fun String.toPluginName(): String =
    split(Regex("[^A-Za-z0-9]+"))
        .filter(String::isNotBlank)
        .joinToString("") { word -> word.replaceFirstChar(Char::uppercaseChar) }
        .ifBlank { "FrameworkPlugin" }

private fun String.requireExactVersion(subject: String): String {
    require(isNotBlank()) { "$subject version must not be blank." }
    require(none { it in "+[]()," }) { "$subject version must be exact, not a range: $this" }
    return this
}
