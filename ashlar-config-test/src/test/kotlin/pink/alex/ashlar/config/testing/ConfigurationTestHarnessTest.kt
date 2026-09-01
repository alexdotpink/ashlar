package pink.alex.ashlar.config.testing

import kotlinx.serialization.Serializable
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import pink.alex.ashlar.config.ConfigLimits
import pink.alex.ashlar.config.ConfigReload
import pink.alex.ashlar.config.ConfigReloadMode
import pink.alex.ashlar.config.ConfigProblemCategory
import pink.alex.ashlar.config.ConfigEvent
import pink.alex.ashlar.config.ConfigEventOrigin
import pink.alex.ashlar.config.ConfigRestore
import pink.alex.ashlar.config.ConfigWrite
import pink.alex.ashlar.config.ConfigOperationStatus
import pink.alex.ashlar.config.codegen.ConfigDefinition
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ConfigurationTestHarnessTest {
    @Test
    fun `missing document starts from defaults and can be edited through the real handle`() = configTest(
        definition<TestSettings>("settings.json"),
    ) {
        val handle = handle<TestSettings>()

        assertEquals(TestSettings(), handle.current)
        assertEquals(
            """
            {
              "_ashlar-schema": 1,
              "limit": 5,
              "message": "hello"
            }
            """.trimIndent() + "\n",
            readSource("settings.json"),
        )

        writeSource(
            "settings.json",
            """
            {
              "_ashlar-schema": 1,
              "limit": 8,
              "message": "changed"
            }
            """.trimIndent(),
        )

        val reload = assertIs<ConfigReload.Accepted<TestSettings>>(handle.reload())
        assertEquals(TestSettings(limit = 8, message = "changed"), reload.value)
    }

    @Test
    fun `manual reload rejects invalid source and retains the accepted value`() = configTest(
        definition<TestSettings>("settings.json"),
    ) {
        val handle = handle<TestSettings>()
        writeSource("settings.json", "{ definitely-not-json }")

        val reload = assertIs<ConfigReload.Rejected<TestSettings>>(handle.reload())

        assertEquals(TestSettings(), reload.current)
        assertEquals(TestSettings(), handle.current)
        assertEquals(ConfigProblemCategory.SYNTAX, reload.problems.single().category)
    }

    @Test
    fun `updates persist atomically and reject a stale accepted revision`() = configTest(
        definition<TestSettings>("settings.json"),
    ) {
        val handle = handle<TestSettings>()

        val written = assertIs<ConfigWrite.Accepted<TestSettings>>(
            handle.update { settings -> settings.copy(limit = 6) },
        )
        assertEquals(6, written.value.limit)
        assertEquals(6, handle.current.limit)
        assertEquals(true, readSource("settings.json").contains("\"limit\": 6"))

        editSource("settings.json", atomicReplace = true) { source -> source.replace("\"limit\": 6", "\"limit\": 7") }

        assertIs<ConfigWrite.SourceChanged<TestSettings>>(
            handle.update { settings -> settings.copy(message = "must not win") },
        )
        assertEquals(TestSettings(limit = 6), handle.current)
        assertEquals(true, readSource("settings.json").contains("\"limit\": 7"))
    }

    @Test
    fun `valid predecessors can be listed and restored through production backups`() = configTest(
        definition<TestSettings>("settings.json", backups = 2),
    ) {
        val handle = handle<TestSettings>()
        assertIs<ConfigWrite.Accepted<TestSettings>>(handle.update { it.copy(limit = 6) })

        val backup = handle.backups().single()
        val restored = assertIs<ConfigRestore.Accepted<TestSettings>>(handle.restore(backup.id))

        assertEquals(TestSettings(), restored.value)
        assertEquals(TestSettings(), handle.current)
        assertEquals(true, readSource("settings.json").contains("\"limit\": 5"))
    }

    @Test
    fun `watched rejection retains current and a comment-only recovery is observable`() = configTest(
        definition<TestSettings>("settings.jsonc", reload = ConfigReloadMode.WATCH),
    ) {
        val handle = handle<TestSettings>()
        replaceSource("settings.jsonc", "{ broken }")

        val rejected = awaitEvent(handle) { event ->
            event is ConfigEvent.Rejected && event.origin == ConfigEventOrigin.WATCHED_RELOAD
        }
        assertIs<ConfigEvent.Rejected<TestSettings>>(rejected)
        assertEquals(TestSettings(), handle.current)

        replaceSource(
            "settings.jsonc",
            """
            {
              // an operator-only note
              "_ashlar-schema": 1,
              "limit": 5,
              "message": "hello"
            }
            """.trimIndent(),
        )

        val recovered = assertIs<ConfigEvent.Accepted<TestSettings>>(awaitEvent(handle) { event ->
            event is ConfigEvent.Accepted && event.origin == ConfigEventOrigin.WATCHED_RELOAD
        })
        assertEquals(false, recovered.changed)
        assertEquals(TestSettings(), recovered.value)
    }

    @Test
    fun `editor bursts settle on the final complete document`() = configTest(
        definition<TestSettings>("settings.json", reload = ConfigReloadMode.WATCH),
    ) {
        val handle = handle<TestSettings>()
        editorBurst(
            "settings.json",
            source(limit = 6, message = "first"),
            source(limit = 7, message = "middle"),
            source(limit = 8, message = "last"),
        )

        awaitCurrent(handle, TestSettings(limit = 8, message = "last"))
        assertEquals(TestSettings(limit = 8, message = "last"), handle.current)
    }

    @Test
    fun `bulk reload reports each qualified document independently`() = configTest(
        definition<TestSettings>("one.json", qualifier = First::class),
        definition<TestSettings>("two.json", qualifier = Second::class),
    ) {
        writeSource("one.json", source(limit = 6, message = "accepted"))
        writeSource("two.json", "not json")

        val report = configurations.reloadAll()

        assertEquals(1, report.accepted)
        assertEquals(1, report.retained)
        assertEquals(ConfigOperationStatus.ACCEPTED, report.documents.single { it.path == "one.json" }.status)
        assertEquals(ConfigOperationStatus.REJECTED, report.documents.single { it.path == "two.json" }.status)
        assertEquals(6, handle<TestSettings>(First::class).current.limit)
        assertEquals(5, handle<TestSettings>(Second::class).current.limit)
    }

    @Test
    fun `inspection exposes operations without configuration values`() = configTest(
        definition<TestSettings>("settings.json"),
    ) {
        writeSource("settings.json", source(limit = 9, message = "swordfish"))
        assertIs<ConfigReload.Accepted<TestSettings>>(handle<TestSettings>().reload())

        val inspection = awaitInspection("settings.json") { candidate ->
            candidate.status == ConfigOperationStatus.ACCEPTED
        }

        assertEquals("settings.json", inspection.path)
        assertEquals(ConfigOperationStatus.ACCEPTED, inspection.status)
        assertFalse(inspection.toString().contains("swordfish"))
        assertFalse(inspection.toString().contains("limit=9"))
    }

    @Test
    fun `startup rejects traversal and symbolic-link escapes`() = runTest {
        withTemporaryDirectories(2) { (root, outside) ->
            assertFailsWith<IllegalArgumentException> {
                ConfigTestScope.startAt(root, listOf(definition<TestSettings>("../escape.json")))
            }

            Files.createSymbolicLink(root.resolve("linked"), outside)
            assertFailsWith<IllegalArgumentException> {
                ConfigTestScope.startAt(root, listOf(definition<TestSettings>("linked/escape.json")))
            }
            assertFalse(Files.exists(outside.resolve("escape.json")))
        }
    }

    @Test
    fun `caller-owned directory exercises and retains real filesystem state`() = runTest {
        withTemporaryDirectories(1) { (root) ->
            ConfigTestScope.startAt(root, listOf(definition<TestSettings>("nested/settings.json"))).use { config ->
                val replacement = source(limit = 11, message = "utf8-✓")
                config.replaceSource("nested/settings.json", replacement)
                assertEquals(replacement, Files.readString(root.resolve("nested/settings.json")))
                assertIs<ConfigReload.Accepted<TestSettings>>(config.handle<TestSettings>().reload())
                assertEquals(TestSettings(11, "utf8-✓"), config.handle<TestSettings>().current)
                Files.list(root.resolve("nested")).use { files ->
                    assertFalse(files.anyMatch { it.fileName.toString().endsWith(".tmp") })
                }
            }

            assertTrue(Files.isRegularFile(root.resolve("nested/settings.json")))
        }
    }
}

private annotation class First
private annotation class Second

@Serializable
private data class TestSettings(
    val limit: Int = 5,
    val message: String = "hello",
)

private inline fun <reified T : Any> definition(
    path: String,
    reload: ConfigReloadMode = ConfigReloadMode.EXPLICIT,
    backups: Int = 5,
    qualifier: kotlin.reflect.KClass<out Annotation>? = null,
): ConfigDefinition<T> = ConfigDefinition(
    rootType = T::class,
    handleKey = configHandleKey<T>(qualifier),
    path = path,
    schemaVersion = 1,
    unversionedSchema = 0,
    reloadMode = reload,
    backups = backups,
    limits = ConfigLimits(),
    serializer = kotlinx.serialization.serializer(),
)

private fun source(limit: Int, message: String): String =
    """
    {
      "_ashlar-schema": 1,
      "limit": $limit,
      "message": "$message"
    }
    """.trimIndent()

private suspend fun withTemporaryDirectories(count: Int, block: suspend (List<Path>) -> Unit) {
    val directories = List(count) { Files.createTempDirectory("ashlar-config-test-fixture-") }
    try {
        block(directories)
    } finally {
        directories.asReversed().forEach(::deleteTree)
    }
}

private fun deleteTree(root: Path) {
    if (!Files.exists(root)) return
    Files.walk(root).use { paths ->
        paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
    }
}
