package pink.alex.ashlar.config.codegen

import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer
import org.junit.jupiter.api.Test
import pink.alex.ashlar.config.ConfigHandle
import pink.alex.ashlar.config.ConfigLimits
import pink.alex.ashlar.config.ConfigReloadMode
import pink.alex.ashlar.di.DependencyKey
import pink.alex.ashlar.di.DependencyType
import kotlin.test.assertEquals

class ConfigDefinitionTest {
    @Test
    fun `definition retains the exact structural handle key`() {
        val key = DependencyKey<ConfigHandle<Settings>>(
            DependencyType(
                rawType = ConfigHandle::class,
                arguments = listOf(DependencyType<Settings>(Settings::class)),
            ),
        )
        val definition = ConfigDefinition(
            rootType = Settings::class,
            handleKey = key,
            path = "settings.yml",
            schemaVersion = 1,
            unversionedSchema = 0,
            reloadMode = ConfigReloadMode.EXPLICIT,
            backups = 5,
            limits = ConfigLimits(),
            serializer = serializer<Settings>(),
        )

        assertEquals(key, definition.handleKey)
        assertEquals(DependencyType<Settings>(Settings::class), key.dependencyType.arguments.single())
    }

    @Test
    fun `typed migration performs one direct transformation`() {
        val migration = configMigration(
            fromSchema = 1,
            sourceSerializer = serializer<SettingsV1>(),
            targetSerializer = serializer<Settings>(),
            migrate = { previous -> Settings(previous.limit) },
        )

        assertEquals(Settings(7), migration.migrateValue(SettingsV1(7)))
    }
}

@Serializable
private data class Settings(val limit: Int = 5)

@Serializable
private data class SettingsV1(val limit: Int)
