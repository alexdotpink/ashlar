package pink.alex.ashlar.config.testing

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import pink.alex.ashlar.config.ConfigBackup
import pink.alex.ashlar.config.ConfigBackupId
import pink.alex.ashlar.config.Config
import pink.alex.ashlar.config.ConfigDocument
import pink.alex.ashlar.config.ConfigDocumentReload
import pink.alex.ashlar.config.ConfigEvent
import pink.alex.ashlar.config.ConfigFormat
import pink.alex.ashlar.config.ConfigHandle
import pink.alex.ashlar.config.ConfigKeyPath
import pink.alex.ashlar.config.ConfigLimits
import pink.alex.ashlar.config.ConfigOperationStatus
import pink.alex.ashlar.config.ConfigParse
import pink.alex.ashlar.config.ConfigReload
import pink.alex.ashlar.config.ConfigReloadReport
import pink.alex.ashlar.config.ConfigRestore
import pink.alex.ashlar.config.ConfigSource
import pink.alex.ashlar.config.ConfigValue
import pink.alex.ashlar.config.ConfigWrite
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ConfigPublicModelTest {
    @Test
    fun `a consumer can implement and use the public handle contract`() = runTest {
        val handle: ConfigHandle<Settings> = FakeHandle(Settings(limit = 5))

        assertEquals(5, handle.current.limit)
        assertIs<ConfigReload.Accepted<Settings>>(handle.reload())
        assertIs<ConfigWrite.Unchanged<Settings>>(handle.update { it.copy() })
        assertIs<ConfigRestore.NotFound<Settings>>(handle.restore(ConfigBackupId("missing")))
    }

    @Test
    fun `a custom format can preserve its document while applying semantic changes`() {
        val format: ConfigFormat = ExampleFormat
        val parsed = assertIs<ConfigParse.Accepted>(
            format.parse(ConfigSource("settings.example", "limit=5 # operator note")),
        )
        val changed = parsed.document.patch(
            ConfigValue.ObjectValue(mapOf("limit" to ConfigValue.IntegerValue(8))),
            mapOf(ConfigKeyPath("limit") to "Maximum number of entries."),
        )

        assertEquals("example", changed.formatId)
        assertEquals(ConfigValue.IntegerValue(8), changed.value.entries["limit"])
        assertEquals("limit=8 # operator note", format.write(changed))
    }

    @Test
    fun `bulk reload reports accepted and retained documents without values`() {
        val report = ConfigReloadReport(
            listOf(
                ConfigDocumentReload("one.yml", ConfigOperationStatus.ACCEPTED, changed = true),
                ConfigDocumentReload("two.yml", ConfigOperationStatus.REJECTED),
                ConfigDocumentReload("three.yml", ConfigOperationStatus.UNAVAILABLE),
            ),
        )

        assertEquals(1, report.accepted)
        assertEquals(2, report.retained)
    }
}

private data class Settings(val limit: Int)

@Config(path = "default.yml")
private data class DefaultDeclaration(val enabled: Boolean = true)

private annotation class Nether

@Config(
    path = "worlds/nether.yml",
    schemaVersion = 3,
    unversionedSchema = 1,
    qualifier = Nether::class,
)
private data class QualifiedDeclaration(val explosions: Boolean = true)

private class FakeHandle(initial: Settings) : ConfigHandle<Settings> {
    private val state = MutableStateFlow(initial)
    override val current: Settings get() = state.value
    override val values: MutableStateFlow<Settings> get() = state
    override val events: Flow<ConfigEvent<Settings>> = emptyFlow()

    override suspend fun reload(): ConfigReload<Settings> = ConfigReload.Accepted(current, changed = false)

    override suspend fun update(transform: (Settings) -> Settings): ConfigWrite<Settings> {
        val transformed = transform(current)
        if (transformed == current) return ConfigWrite.Unchanged(current)
        state.value = transformed
        return ConfigWrite.Accepted(transformed)
    }

    override suspend fun backups(): List<ConfigBackup> = emptyList()

    override suspend fun restore(id: ConfigBackupId): ConfigRestore<Settings> =
        ConfigRestore.NotFound(current, id)
}

private object ExampleFormat : ConfigFormat {
    override val id: String = "example"
    override val extensions: Set<String> = setOf("example")
    override val preservesComments: Boolean = true

    override fun parse(source: ConfigSource, limits: ConfigLimits): ConfigParse =
        ConfigParse.Accepted(ExampleDocument(5, " # operator note"))

    override fun create(
        value: ConfigValue.ObjectValue,
        comments: Map<ConfigKeyPath, String>,
    ): ConfigDocument = ExampleDocument(
        limit = (value.entries.getValue("limit") as ConfigValue.IntegerValue).value,
        trailingComment = "",
    )

    override fun write(document: ConfigDocument): String {
        val example = document as ExampleDocument
        return "limit=${example.limit}${example.trailingComment}"
    }
}

private data class ExampleDocument(
    val limit: Long,
    val trailingComment: String,
) : ConfigDocument {
    override val formatId: String = "example"
    override val value: ConfigValue.ObjectValue =
        ConfigValue.ObjectValue(mapOf("limit" to ConfigValue.IntegerValue(limit)))

    override fun patch(
        value: ConfigValue.ObjectValue,
        newComments: Map<ConfigKeyPath, String>,
    ): ConfigDocument = copy(limit = (value.entries.getValue("limit") as ConfigValue.IntegerValue).value)
}
