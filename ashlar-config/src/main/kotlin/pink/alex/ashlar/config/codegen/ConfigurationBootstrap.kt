package pink.alex.ashlar.config.codegen

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.bukkit.plugin.Plugin
import pink.alex.ashlar.config.ConfigFormat
import pink.alex.ashlar.config.format.BuiltInConfigFormats
import pink.alex.ashlar.config.internal.ConfigurationRuntime
import pink.alex.ashlar.di.DependencyGraph
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
        return install(
            graph = graph,
            dataDirectory = plugin.dataFolder.toPath(),
            definitions = definitions,
            formats = customFormats + BuiltInConfigFormats.all,
        )
    }

    /** Server-free installation seam used by `ashlar-config-test`. */
    public fun install(
        graph: DependencyGraph,
        dataDirectory: Path,
        definitions: List<ConfigDefinition<*>>,
        formats: List<ConfigFormat> = BuiltInConfigFormats.all,
    ): AutoCloseable = runBlocking(Dispatchers.IO) {
        ConfigurationRuntime.install(graph, dataDirectory, definitions, formats)
    }
}
