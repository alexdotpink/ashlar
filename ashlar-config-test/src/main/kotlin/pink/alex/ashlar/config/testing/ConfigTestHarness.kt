package pink.alex.ashlar.config.testing

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import pink.alex.ashlar.config.ConfigEvent
import pink.alex.ashlar.config.ConfigFormat
import pink.alex.ashlar.config.ConfigHandle
import pink.alex.ashlar.config.ConfigInspection
import pink.alex.ashlar.config.Configurations
import pink.alex.ashlar.config.codegen.ConfigDefinition
import pink.alex.ashlar.config.codegen.ConfigurationBootstrap
import pink.alex.ashlar.config.format.BuiltInConfigFormats
import pink.alex.ashlar.di.DependencyGraph
import pink.alex.ashlar.di.DependencyKey
import pink.alex.ashlar.di.DependencyType
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.Comparator
import java.util.UUID
import kotlin.reflect.KClass
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Runs one server-free configuration scenario against production file handles and codecs. */
public fun configTest(
    vararg definitions: ConfigDefinition<*>,
    formats: List<ConfigFormat> = BuiltInConfigFormats.all,
    block: suspend ConfigTestScope.() -> Unit,
): TestResult = runTest {
    ConfigTestScope.start(definitions.toList(), formats).use { configuration ->
        configuration.block()
    }
}

/** Exact structural key used by generated configuration linkage. */
public inline fun <reified T : Any> configHandleKey(
    qualifier: KClass<out Annotation>? = null,
): DependencyKey<ConfigHandle<T>> = configHandleKey(T::class, qualifier)

/** Exact structural key used by generated configuration linkage. */
public fun <T : Any> configHandleKey(
    rootType: KClass<T>,
    qualifier: KClass<out Annotation>? = null,
): DependencyKey<ConfigHandle<T>> = DependencyKey(
    dependencyType = DependencyType(
        rawType = ConfigHandle::class,
        arguments = listOf(DependencyType<Any>(rootType)),
    ),
    qualifier = qualifier,
)

/**
 * A temporary data directory with production configuration handles installed in a real dependency graph.
 *
 * The harness owns its temporary directory. Use [startAt] when a test needs to inspect a caller-owned path
 * after the harness closes.
 */
public class ConfigTestScope private constructor(
    /** Root corresponding to a plug-in's production data directory. */
    public val dataDirectory: Path,
    private val graph: DependencyGraph,
    private val runtime: AutoCloseable,
    private val deleteOnClose: Boolean,
) : AutoCloseable {
    /** Production aggregate capability installed by the configuration runtime. */
    public val configurations: Configurations = graph.get(Configurations::class)

    /** Resolves one production handle by its exact structural type and optional qualifier. */
    public inline fun <reified T : Any> handle(
        qualifier: KClass<out Annotation>? = null,
    ): ConfigHandle<T> = handle(configHandleKey<T>(qualifier))

    /** Resolves one production handle using the same key generated plug-in code uses. */
    public fun <T : Any> handle(key: DependencyKey<ConfigHandle<T>>): ConfigHandle<T> = graph.get(key)

    /** Reads the complete UTF-8 source visible to the production handle. */
    public fun readSource(path: String): String = Files.readString(resolve(path), StandardCharsets.UTF_8)

    /** Simulates an editor writing a document in place. */
    public fun writeSource(path: String, source: String) {
        val target = resolve(path)
        Files.createDirectories(target.parent)
        Files.writeString(
            target,
            source,
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE,
        )
    }

    /** Transforms the current source and saves it either in place or through an atomic replacement. */
    public fun editSource(
        path: String,
        atomicReplace: Boolean = false,
        transform: (String) -> String,
    ) {
        val source = transform(readSource(path))
        if (atomicReplace) replaceSource(path, source) else writeSource(path, source)
    }

    /** Simulates editors that save to a temporary file and atomically replace the original. */
    public fun replaceSource(path: String, source: String) {
        val target = resolve(path)
        Files.createDirectories(target.parent)
        val temporary = target.resolveSibling(".${target.fileName}.test-${UUID.randomUUID()}.tmp")
        Files.writeString(
            temporary,
            source,
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE,
        )
        try {
            Files.move(
                temporary,
                target,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    /** Performs a burst of complete editor saves; the final source is the last entry. */
    public fun editorBurst(
        path: String,
        vararg sources: String,
        atomicReplace: Boolean = true,
    ) {
        require(sources.isNotEmpty()) { "An editor burst needs at least one source" }
        sources.forEach { source ->
            if (atomicReplace) replaceSource(path, source) else writeSource(path, source)
        }
    }

    /** Waits until the real filesystem watcher publishes an event accepted by [predicate]. */
    public suspend fun <T : Any> awaitEvent(
        handle: ConfigHandle<T>,
        timeout: Duration = 5.seconds,
        predicate: (ConfigEvent<T>) -> Boolean,
    ): ConfigEvent<T> = withContext(Dispatchers.IO) {
        withTimeout(timeout) { handle.events.first(predicate) }
    }

    /** Waits until asynchronous watcher work makes [expected] current. */
    public suspend fun <T : Any> awaitCurrent(
        handle: ConfigHandle<T>,
        expected: T,
        timeout: Duration = 5.seconds,
    ) {
        await(timeout) { handle.current == expected }
    }

    /** Waits until redacted aggregate inspection satisfies [predicate]. */
    public suspend fun awaitInspection(
        path: String,
        timeout: Duration = 5.seconds,
        predicate: (ConfigInspection) -> Boolean,
    ): ConfigInspection {
        var found: ConfigInspection? = null
        await(timeout) {
            configurations.inspect().firstOrNull { inspection -> inspection.path == path }
                ?.also { inspection -> found = inspection }
                ?.let(predicate)
                ?: false
        }
        return checkNotNull(found)
    }

    override fun close() {
        try {
            runtime.close()
        } finally {
            try {
                graph.close()
            } finally {
                if (deleteOnClose) deleteTree(dataDirectory)
            }
        }
    }

    private fun resolve(relative: String): Path {
        require(relative.isNotBlank()) { "A configuration test path cannot be blank" }
        val supplied = Path.of(relative)
        require(!supplied.isAbsolute) { "A configuration test path must be relative: $relative" }
        val root = dataDirectory.toAbsolutePath().normalize()
        val resolved = root.resolve(supplied).normalize()
        require(resolved.startsWith(root) && resolved != root) {
            "A configuration test path escapes the data directory: $relative"
        }
        return resolved
    }

    private suspend fun await(timeout: Duration, ready: () -> Boolean) {
        withContext(Dispatchers.IO) {
            withTimeout(timeout) {
                while (!ready()) delay(POLL_INTERVAL)
            }
        }
    }

    public companion object {
        private val POLL_INTERVAL: Duration = 10.milliseconds

        /** Starts a harness in a fresh temporary data directory. */
        public fun start(
            definitions: List<ConfigDefinition<*>>,
            formats: List<ConfigFormat> = BuiltInConfigFormats.all,
        ): ConfigTestScope = startOwned(Files.createTempDirectory("ashlar-config-test-"), definitions, formats)

        /** Starts a harness in a caller-owned data directory, preserving its files after [close]. */
        public fun startAt(
            dataDirectory: Path,
            definitions: List<ConfigDefinition<*>>,
            formats: List<ConfigFormat> = BuiltInConfigFormats.all,
        ): ConfigTestScope = install(dataDirectory, definitions, formats, deleteOnClose = false)

        private fun startOwned(
            dataDirectory: Path,
            definitions: List<ConfigDefinition<*>>,
            formats: List<ConfigFormat>,
        ): ConfigTestScope = try {
            install(dataDirectory, definitions, formats, deleteOnClose = true)
        } catch (failure: Throwable) {
            deleteTree(dataDirectory)
            throw failure
        }

        private fun install(
            dataDirectory: Path,
            definitions: List<ConfigDefinition<*>>,
            formats: List<ConfigFormat>,
            deleteOnClose: Boolean,
        ): ConfigTestScope {
            val graph = DependencyGraph(ConfigTestScope::class.java.classLoader)
            return try {
                val runtime = ConfigurationBootstrap.install(graph, dataDirectory, definitions, formats)
                ConfigTestScope(dataDirectory, graph, runtime, deleteOnClose)
            } catch (failure: Throwable) {
                graph.close()
                throw failure
            }
        }
    }
}

private fun deleteTree(root: Path) {
    if (!Files.exists(root)) return
    Files.walk(root).use { paths ->
        paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
    }
}
