package pink.alex.ashlar.config

import kotlin.reflect.KClass

/** Declares an immutable serializable type as one static configuration document. */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
@Repeatable
public annotation class Config(
    /** Path beneath the plug-in data directory. */
    val path: String,
    /** Current schema produced by this declaration. */
    val schemaVersion: Int = 1,
    /** Schema assigned to an existing document without an `_ashlar-schema` marker. */
    val unversionedSchema: Int = 1,
    /** Whether Ashlar reloads the document only explicitly or also watches it. */
    val reload: ConfigReloadMode = ConfigReloadMode.EXPLICIT,
    /** Maximum number of valid predecessor documents retained after writes. */
    val backups: Int = 5,
    /** Maximum encoded document size accepted before parsing. */
    val maximumBytes: Long = 1_048_576,
    /** Optional Ashlar dependency qualifier for this document's handle. */
    val qualifier: KClass<*> = Unit::class,
)

/** Selects how a configuration document is reloaded after its initial load. */
public enum class ConfigReloadMode {
    /** Reload only through [ConfigHandle.reload] or [Configurations.reloadAll]. */
    EXPLICIT,

    /** Also coalesce stable external file changes into reload attempts. */
    WATCH,
}

/** Marks one pure validation extension for a configuration root. */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
public annotation class ConfigValidation

/** Marks one pure transformation from [from] to the immediately following schema. */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
public annotation class ConfigMigration(
    val root: KClass<*>,
    val from: Int,
)
