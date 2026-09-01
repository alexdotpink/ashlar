package pink.alex.ashlar.config.testing

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import pink.alex.ashlar.config.Config
import pink.alex.ashlar.config.ConfigMigration
import pink.alex.ashlar.config.ConfigReloadMode
import pink.alex.ashlar.config.ConfigValidation
import pink.alex.ashlar.config.ConfigValidationScope
import pink.alex.ashlar.di.DependencyQualifier

@DependencyQualifier
annotation class ProcessorFixture

@DependencyQualifier
annotation class SecondaryProcessorFixture

@Config(
    path = "processor-fixture.jsonc",
    schemaVersion = 2,
    unversionedSchema = 1,
    reload = ConfigReloadMode.WATCH,
    qualifier = ProcessorFixture::class,
)
@Config(
    path = "processor-fixture-secondary.json",
    schemaVersion = 2,
    unversionedSchema = 1,
    qualifier = SecondaryProcessorFixture::class,
)
@Serializable
data class ProcessorFixtureSettings(
    /** A default property whose external key is kebab case. */
    val maximumPlayers: Int = 20,
    /** An explicit serialization name that must remain verbatim. */
    @SerialName("literalName") val displayName: String = "Ashlar",
)

@Serializable
data class ProcessorFixtureSettingsV1(
    val maximumPlayers: Int,
)

@ConfigMigration(ProcessorFixtureSettings::class, from = 1)
fun ProcessorFixtureSettingsV1.toProcessorFixtureSettings(): ProcessorFixtureSettings =
    ProcessorFixtureSettings(maximumPlayers = maximumPlayers)

@ConfigValidation
fun ConfigValidationScope<ProcessorFixtureSettings>.validateProcessorFixture() {
    requireValue(current.maximumPlayers > 0, ProcessorFixtureSettings::maximumPlayers) {
        "must be positive"
    }
}
