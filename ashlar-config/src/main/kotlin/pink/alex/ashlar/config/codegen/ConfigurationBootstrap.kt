package pink.alex.ashlar.config.codegen

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.bukkit.plugin.Plugin
import pink.alex.ashlar.config.ConfigFormat
import pink.alex.ashlar.config.format.BuiltInConfigFormats
import pink.alex.ashlar.config.internal.ConfigurationRuntime
import pink.alex.ashlar.config.internal.CompositeConfigurations
import pink.alex.ashlar.di.DependencyGraph
import pink.alex.ashlar.di.DependencyKey
import java.nio.file.Path

/** Handwritten runtime entry point called once by generated configuration linkage. */
public object ConfigurationBootstrap {
    /** Installs every generated definition beneath the current plug-in data folder. */
    public fun install(
        graph: DependencyGraph,
        definitions: List<ConfigDefinition<*>>,
    ): AutoCloseable {
        val plugin = graph.get(Plugin::class)
        val customFormats = graph.contributions(ConfigFormat::class)
        return installRuntime(
            graph = graph,
            dataDirectory = plugin.dataFolder.toPath(),
            definitions = definitions,
            formats = customFormats + BuiltInConfigFormats.all,
            reporter = pink.alex.ashlar.config.internal.ConfigRuntimeReporter { path, recovered, count ->
                if (recovered) {
                    plugin.logger.info("Configuration '$path' recovered and is valid again")
                } else {
                    plugin.logger.warning("Configuration '$path' was rejected with $count problem(s); current value retained")
                }
            },
        )
    }

    /** Server-free installation seam used by `ashlar-config-test`. */
    public fun install(
        graph: DependencyGraph,
        dataDirectory: Path,
        definitions: List<ConfigDefinition<*>>,
        formats: List<ConfigFormat> = BuiltInConfigFormats.all,
    ): AutoCloseable = installRuntime(
        graph,
        dataDirectory,
        definitions,
        formats,
        pink.alex.ashlar.config.internal.ConfigRuntimeReporter.NONE,
    )

    private fun installRuntime(
        graph: DependencyGraph,
        dataDirectory: Path,
        definitions: List<ConfigDefinition<*>>,
        formats: List<ConfigFormat>,
        reporter: pink.alex.ashlar.config.internal.ConfigRuntimeReporter,
    ): AutoCloseable = runBlocking(Dispatchers.IO) {
        val aggregateKey = DependencyKey(pink.alex.ashlar.config.Configurations::class)
        val candidate = CompositeConfigurations()
        val aggregate = if (graph.bindDefault(aggregateKey, candidate)) {
            candidate
        } else {
            graph.get(aggregateKey) as? CompositeConfigurations
                ?: error("Configurations is already bound by a non-Ashlar implementation")
        }
        val runtime = ConfigurationRuntime.install(
            graph,
            dataDirectory,
            definitions,
            formats,
            reporter = reporter,
        )
        try {
            aggregate.attach(runtime)
        } catch (failure: Throwable) {
            runtime.close()
            throw failure
        }
    }
}
