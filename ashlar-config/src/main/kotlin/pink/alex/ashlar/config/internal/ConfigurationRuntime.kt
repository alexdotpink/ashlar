package pink.alex.ashlar.config.internal

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import pink.alex.ashlar.config.ConfigDocumentReload
import pink.alex.ashlar.config.ConfigFormat
import pink.alex.ashlar.config.ConfigInspection
import pink.alex.ashlar.config.ConfigReloadReport
import pink.alex.ashlar.config.ConfigStartupException
import pink.alex.ashlar.config.Configurations
import pink.alex.ashlar.config.codegen.ConfigDefinition
import pink.alex.ashlar.di.DependencyGraph
import pink.alex.ashlar.di.DependencyKey
import java.nio.file.Path
import java.time.Clock

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
        scope.cancel()
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
            val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
            opened.forEach { handle -> handle.startWatching(scope) }
            return ConfigurationRuntime(opened, scope).also { runtime ->
                graph.bind(DependencyKey(pink.alex.ashlar.config.Configurations::class), runtime)
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
                    graph.bind(definition.handleKey, result.handle)
                    opened += result.handle
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

/** Redacted watched-reload status sink. */
internal fun interface ConfigRuntimeReporter {
    fun report(path: String, recovered: Boolean, problemCount: Int)

    companion object {
        val NONE: ConfigRuntimeReporter = ConfigRuntimeReporter { _, _, _ -> }
    }
}
