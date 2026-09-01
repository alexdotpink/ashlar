package pink.alex.ashlar.config.internal

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import pink.alex.ashlar.config.ConfigDocumentReload
import pink.alex.ashlar.config.ConfigFormat
import pink.alex.ashlar.config.ConfigInspection
import pink.alex.ashlar.config.ConfigReloadReport
import pink.alex.ashlar.config.ConfigStartupException
import pink.alex.ashlar.config.Configurations
import pink.alex.ashlar.config.codegen.ConfigDefinition
import pink.alex.ashlar.di.DependencyGraph
import java.nio.file.Path
import java.time.Clock
import java.util.concurrent.CopyOnWriteArrayList

internal class ConfigurationRuntime private constructor(
    private val handles: List<InternalConfigHandle>,
    private val scope: CoroutineScope,
) : Configurations, AutoCloseable {
    override suspend fun reloadAll(): ConfigReloadReport {
        val results = ArrayList<ConfigDocumentReload>(handles.size)
        handles.forEach { handle -> results += handle.reloadAny() }
        return ConfigReloadReport(results)
    }

    override fun inspect(): List<ConfigInspection> = handles.map(InternalConfigHandle::inspect)

    override fun close() {
        handles.asReversed().forEach(InternalConfigHandle::close)
        val job = scope.coroutineContext[Job]
        scope.cancel()
        runBlocking { job?.join() }
    }

    companion object {
        suspend fun install(
            graph: DependencyGraph,
            dataDirectory: Path,
            definitions: List<ConfigDefinition<*>>,
            formats: List<ConfigFormat>,
            ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
            clock: Clock = Clock.systemUTC(),
            reporter: ConfigRuntimeReporter = ConfigRuntimeReporter.NONE,
        ): ConfigurationRuntime {
            val duplicatePath = definitions.groupBy { definition ->
                definition.path.replace('\\', '/').lowercase()
            }.entries.firstOrNull { (_, declarations) -> declarations.size > 1 }
            require(duplicatePath == null) {
                "Configuration path '${duplicatePath!!.key}' is declared more than once"
            }
            val extensionOwners = linkedMapOf<String, ConfigFormat>()
            formats.forEach { format ->
                require(format.id.isNotBlank()) { "Config format id cannot be blank" }
                require(format.extensions.isNotEmpty()) { "Config format '${format.id}' has no extensions" }
                format.extensions.forEach { rawExtension ->
                    val extension = rawExtension.lowercase()
                    require(extension.isNotBlank() && '.' !in extension) {
                        "Invalid extension '$rawExtension' from format '${format.id}'"
                    }
                    val previous = extensionOwners.putIfAbsent(extension, format)
                    require(previous == null || previous === format) {
                        "Configuration extension '$extension' is owned by both '${previous!!.id}' and '${format.id}'"
                    }
                }
            }
            val files = ConfigFiles(dataDirectory, clock)
            val opened = mutableListOf<InternalConfigHandle>()
            try {
                definitions.sortedBy(ConfigDefinition<*>::path).forEach { definition ->
                    val extension = definition.path.substringAfterLast('.', missingDelimiterValue = "").lowercase()
                    val format = extensionOwners[extension] ?: throw ConfigStartupException(
                        definition.path,
                        problems = listOf(pink.alex.ashlar.config.ConfigProblem(
                            path = definition.path,
                            category = pink.alex.ashlar.config.ConfigProblemCategory.UNSUPPORTED_FEATURE,
                            message = "No configuration format owns extension '$extension'",
                        )),
                    )
                    installErased(graph, definition, format, files, ioDispatcher, reporter, opened)
                }
            } catch (failure: Throwable) {
                opened.asReversed().forEach { handle -> runCatching(handle::close) }
                throw failure
            }
            val runtime = ConfigurationRuntime(opened, CoroutineScope(SupervisorJob() + ioDispatcher))
            try {
                opened.forEach { handle -> handle.startWatching(runtime.scope) }
                return runtime
            } catch (failure: Throwable) {
                runtime.close()
                throw failure
            }
        }

        private suspend fun <T : Any> installTyped(
            graph: DependencyGraph,
            definition: ConfigDefinition<T>,
            format: ConfigFormat,
            files: ConfigFiles,
            ioDispatcher: CoroutineDispatcher,
            reporter: ConfigRuntimeReporter,
            opened: MutableList<InternalConfigHandle>,
        ) {
            when (val result = FileConfigHandle.open(definition, format, files, ioDispatcher, reporter)) {
                is OpenResult.Accepted -> {
                    opened += result.handle
                    graph.bind(definition.handleKey, result.handle)
                }
                is OpenResult.Rejected -> throw ConfigStartupException(definition.path, result.problems)
                is OpenResult.Unavailable -> throw ConfigStartupException(
                    definition.path,
                    operationProblem = result.problem,
                )
            }
        }

        @Suppress("UNCHECKED_CAST")
        private suspend fun installErased(
            graph: DependencyGraph,
            definition: ConfigDefinition<*>,
            format: ConfigFormat,
            files: ConfigFiles,
            ioDispatcher: CoroutineDispatcher,
            reporter: ConfigRuntimeReporter,
            opened: MutableList<InternalConfigHandle>,
        ) = installTyped(
            graph,
            definition as ConfigDefinition<Any>,
            format,
            files,
            ioDispatcher,
            reporter,
            opened,
        )
    }
}

internal class CompositeConfigurations : Configurations {
    private val runtimes = CopyOnWriteArrayList<ConfigurationRuntime>()

    fun attach(runtime: ConfigurationRuntime): AutoCloseable {
        val existingPaths = runtimes.flatMap { installed -> installed.inspect().map(ConfigInspection::path) }.toSet()
        val addedPaths = runtime.inspect().map(ConfigInspection::path)
        require(addedPaths.none(existingPaths::contains)) {
            "Configuration path '${addedPaths.first(existingPaths::contains)}' is declared by more than one module"
        }
        runtimes += runtime
        return AutoCloseable {
            if (runtimes.remove(runtime)) runtime.close()
        }
    }

    override suspend fun reloadAll(): ConfigReloadReport = ConfigReloadReport(
        runtimes.flatMap { runtime -> runtime.reloadAll().documents },
    )

    override fun inspect(): List<ConfigInspection> =
        runtimes.flatMap(ConfigurationRuntime::inspect).sortedBy(ConfigInspection::path)
}

/** Redacted watched-reload status sink. */
internal fun interface ConfigRuntimeReporter {
    fun report(path: String, recovered: Boolean, problemCount: Int)

    companion object {
        val NONE: ConfigRuntimeReporter = ConfigRuntimeReporter { _, _, _ -> }
    }
}
