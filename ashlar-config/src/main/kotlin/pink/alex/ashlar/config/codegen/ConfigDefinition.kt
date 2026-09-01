package pink.alex.ashlar.config.codegen

import kotlinx.serialization.KSerializer
import pink.alex.ashlar.config.ConfigHandle
import pink.alex.ashlar.config.ConfigKeyPath
import pink.alex.ashlar.config.ConfigLimits
import pink.alex.ashlar.config.ConfigReloadMode
import pink.alex.ashlar.config.ConfigValidationScope
import pink.alex.ashlar.di.DependencyKey
import kotlin.reflect.KClass

/**
 * Static linkage for one configuration declaration.
 *
 * Ashlar's processor emits instances of this class. Plug-in code should use
 * [ConfigHandle] instead of constructing definitions itself.
 */
public class ConfigDefinition<T : Any>(
    public val rootType: KClass<T>,
    public val handleKey: DependencyKey<ConfigHandle<T>>,
    public val path: String,
    public val schemaVersion: Int,
    public val unversionedSchema: Int,
    public val reloadMode: ConfigReloadMode,
    public val backups: Int,
    public val limits: ConfigLimits,
    public val serializer: KSerializer<T>,
    /** Descriptor path to exact external key segment, emitted to preserve @SerialName overrides. */
    public val keyNames: Map<ConfigKeyPath, String> = emptyMap(),
    public val comments: Map<ConfigKeyPath, String> = emptyMap(),
    public val validators: List<ConfigValidator<T>> = emptyList(),
    public val migrations: List<ConfigMigrationStep> = emptyList(),
)

/** One direct call to a pure generated validation function. */
public class ConfigValidator<T : Any> internal constructor(
    internal val validate: ConfigValidationScope<T>.() -> Unit,
)

/** Creates validator linkage without generating a validator implementation. */
public fun <T : Any> configValidator(
    validate: ConfigValidationScope<T>.() -> Unit,
): ConfigValidator<T> = ConfigValidator(validate)

/** Type-erased boundary around one compile-checked, typed migration step. */
public class ConfigMigrationStep internal constructor(
    public val fromSchema: Int,
    internal val sourceSerializer: KSerializer<Any>,
    internal val targetSerializer: KSerializer<Any>,
    internal val sourceKeyNames: Map<ConfigKeyPath, String>,
    internal val targetKeyNames: Map<ConfigKeyPath, String>,
    internal val migrateValue: (Any) -> Any,
)

/** Creates one sequential migration while keeping every cast inside handwritten linkage code. */
public fun <F : Any, T : Any> configMigration(
    fromSchema: Int,
    sourceSerializer: KSerializer<F>,
    targetSerializer: KSerializer<T>,
    sourceKeyNames: Map<ConfigKeyPath, String> = emptyMap(),
    targetKeyNames: Map<ConfigKeyPath, String> = emptyMap(),
    migrate: (F) -> T,
): ConfigMigrationStep {
    require(fromSchema >= 1) { "fromSchema must be at least 1" }
    @Suppress("UNCHECKED_CAST")
    return ConfigMigrationStep(
        fromSchema = fromSchema,
        sourceSerializer = sourceSerializer as KSerializer<Any>,
        targetSerializer = targetSerializer as KSerializer<Any>,
        sourceKeyNames = sourceKeyNames,
        targetKeyNames = targetKeyNames,
        migrateValue = { value -> migrate(value as F) },
    )
}
