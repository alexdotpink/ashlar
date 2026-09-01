package pink.alex.ashlar.config.internal

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import pink.alex.ashlar.config.ConfigHandle
import pink.alex.ashlar.config.ConfigKeyPath
import pink.alex.ashlar.config.ConfigLimits
import pink.alex.ashlar.config.ConfigProblemCategory
import pink.alex.ashlar.config.ConfigReload
import pink.alex.ashlar.config.ConfigReloadMode
import pink.alex.ashlar.config.ConfigRestore
import pink.alex.ashlar.config.ConfigStartupException
import pink.alex.ashlar.config.ConfigWrite
import pink.alex.ashlar.config.codegen.ConfigDefinition
import pink.alex.ashlar.config.codegen.ConfigurationBootstrap
import pink.alex.ashlar.config.codegen.configValidator
import pink.alex.ashlar.config.codegen.configMigration
import pink.alex.ashlar.config.format.BuiltInConfigFormats
import pink.alex.ashlar.di.DependencyGraph
import pink.alex.ashlar.di.DependencyKey
import pink.alex.ashlar.di.DependencyType
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class ConfigurationRuntimeTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `missing document is created from complete defaults and injected by exact handle key`() {
        val graph = DependencyGraph(javaClass.classLoader)

        ConfigurationBootstrap.install(graph, directory, listOf(settingsDefinition("settings.json"))).use {
            val handle = graph.get(settingsKey())

            assertEquals(Settings(), handle.current)
            val source = Files.readString(directory.resolve("settings.json"))
            assertTrue("\"_ashlar-schema\"" in source)
            assertTrue("\"maximum-waypoints\"" in source)
            assertTrue("\"message\"" in source)
        }
    }

    @Test
    fun `invalid reload retains current and suggests the nearest known key`() = runTest {
        val graph = DependencyGraph(javaClass.classLoader)
        ConfigurationBootstrap.install(graph, directory, listOf(settingsDefinition("settings.json"))).use {
            val handle = graph.get(settingsKey())
            Files.writeString(
                directory.resolve("settings.json"),
                """{"_ashlar-schema":1,"maximum-wayponts":12,"message":"hello"}""",
            )

            val result = assertIs<ConfigReload.Rejected<Settings>>(handle.reload())

            assertEquals(Settings(), handle.current)
            val problem = result.problems.single { it.category == ConfigProblemCategory.UNKNOWN_KEY }
            assertEquals(ConfigKeyPath("maximum-waypoints"), problem.nearestKnownKey)
        }
    }

    @Test
    fun `update rejects an unseen external edit instead of overwriting it`() = runTest {
        val graph = DependencyGraph(javaClass.classLoader)
        ConfigurationBootstrap.install(graph, directory, listOf(settingsDefinition("settings.json"))).use {
            val handle = graph.get(settingsKey())
            assertIs<ConfigWrite.Accepted<Settings>>(
                handle.update { current -> current.copy(maximumWaypoints = 8) },
            )
            Files.writeString(
                directory.resolve("settings.json"),
                """{"_ashlar-schema":1,"maximum-waypoints":13,"message":"operator"}""",
            )

            val result = handle.update { current -> current.copy(maximumWaypoints = 9) }

            assertIs<ConfigWrite.SourceChanged<Settings>>(result)
            assertEquals(8, handle.current.maximumWaypoints)
            assertTrue("13" in Files.readString(directory.resolve("settings.json")))
        }
    }

    @Test
    fun `validation rejects writes while warnings remain accepted`() = runTest {
        val graph = DependencyGraph(javaClass.classLoader)
        val definition = settingsDefinition(
            "settings.json",
            validators = listOf(configValidator {
                requireValue(
                    current.maximumWaypoints in 1..100,
                    Settings::maximumWaypoints,
                ) { "must be from 1 through 100" }
                warnIf(
                    current.maximumWaypoints > 80,
                    Settings::maximumWaypoints,
                ) { "large value" }
            }),
        )
        ConfigurationBootstrap.install(graph, directory, listOf(definition)).use {
            val handle = graph.get(settingsKey())

            assertIs<ConfigWrite.Rejected<Settings>>(
                handle.update { it.copy(maximumWaypoints = 0) },
            )
            val accepted = assertIs<ConfigWrite.Accepted<Settings>>(
                handle.update { it.copy(maximumWaypoints = 90) },
            )
            assertEquals(1, accepted.warnings.size)
            assertEquals(90, handle.current.maximumWaypoints)
        }
    }

    @Test
    fun `valid predecessor can be restored after an explicit update`() = runTest {
        val graph = DependencyGraph(javaClass.classLoader)
        ConfigurationBootstrap.install(graph, directory, listOf(settingsDefinition("settings.json"))).use {
            val handle = graph.get(settingsKey())
            assertIs<ConfigWrite.Accepted<Settings>>(
                handle.update { it.copy(maximumWaypoints = 25) },
            )
            val backup = handle.backups().single()

            val restored = assertIs<ConfigRestore.Accepted<Settings>>(handle.restore(backup.id))

            assertEquals(Settings(), restored.value)
            assertEquals(Settings(), handle.current)
        }
    }

    @Test
    fun `unversioned historical document migrates sequentially before publication`() = runTest {
        Files.writeString(directory.resolve("settings.json"), """{"limit":7}""")
        val graph = DependencyGraph(javaClass.classLoader)
        val definition = ConfigDefinition(
            rootType = MigratedSettings::class,
            handleKey = migratedKey(),
            path = "settings.json",
            schemaVersion = 2,
            unversionedSchema = 1,
            reloadMode = ConfigReloadMode.EXPLICIT,
            backups = 5,
            limits = ConfigLimits(),
            serializer = serializer<MigratedSettings>(),
            migrations = listOf(configMigration(
                fromSchema = 1,
                sourceSerializer = serializer<SettingsV1>(),
                targetSerializer = serializer<MigratedSettings>(),
                migrate = { previous -> MigratedSettings(previous.limit, enabled = true) },
            )),
        )

        ConfigurationBootstrap.install(graph, directory, listOf(definition)).use {
            assertEquals(MigratedSettings(7, true), graph.get(migratedKey()).current)
            assertTrue("\"_ashlar-schema\": 2" in Files.readString(directory.resolve("settings.json")))
            assertEquals(1, graph.get(migratedKey()).backups().size)
        }
    }

    @Test
    fun `future schema prevents startup without touching the source`() {
        val source = """{"_ashlar-schema":99,"maximum-waypoints":5,"message":"hello"}"""
        Files.writeString(directory.resolve("settings.json"), source)
        val graph = DependencyGraph(javaClass.classLoader)

        val failure = assertFailsWith<ConfigStartupException> {
            ConfigurationBootstrap.install(graph, directory, listOf(settingsDefinition("settings.json")))
        }

        assertEquals(ConfigProblemCategory.UNSUPPORTED_SCHEMA, failure.problems.single().category)
        assertEquals(source, Files.readString(directory.resolve("settings.json")))
    }

    private fun settingsDefinition(
        path: String,
        validators: List<pink.alex.ashlar.config.codegen.ConfigValidator<Settings>> = emptyList(),
    ): ConfigDefinition<Settings> = ConfigDefinition(
        rootType = Settings::class,
        handleKey = settingsKey(),
        path = path,
        schemaVersion = 1,
        unversionedSchema = 0,
        reloadMode = ConfigReloadMode.EXPLICIT,
        backups = 5,
        limits = ConfigLimits(),
        serializer = serializer<Settings>(),
        comments = mapOf(
            ConfigKeyPath("maximum-waypoints") to "Maximum public waypoints.",
            ConfigKeyPath("message") to "Message shown to players.",
        ),
        validators = validators,
    )

    private fun settingsKey(): DependencyKey<ConfigHandle<Settings>> = DependencyKey(
        DependencyType(
            rawType = ConfigHandle::class,
            arguments = listOf(DependencyType<Settings>(Settings::class)),
        ),
    )

    private fun migratedKey(): DependencyKey<ConfigHandle<MigratedSettings>> = DependencyKey(
        DependencyType(
            rawType = ConfigHandle::class,
            arguments = listOf(DependencyType<MigratedSettings>(MigratedSettings::class)),
        ),
    )
}

@Serializable
private data class Settings(
    val maximumWaypoints: Int = 5,
    val message: String = "hello",
)

@Serializable
private data class SettingsV1(val limit: Int)

@Serializable
private data class MigratedSettings(
    val limit: Int = 5,
    val enabled: Boolean = false,
)
