package pink.alex.ashlar.config.format

import pink.alex.ashlar.config.ConfigFormat
import java.util.Locale

/** The built-in bounded formats selected by configuration source extension. */
public object BuiltInConfigFormats {
    /** Stable built-in order used when registering the configuration runtime. */
    public val all: List<ConfigFormat> = listOf(
        YamlConfigFormat,
        TomlConfigFormat,
        JsonConfigFormat,
        JsoncConfigFormat,
    )

    private val byExtension: Map<String, ConfigFormat> = buildMap {
        all.forEach { format ->
            format.extensions.forEach { extension ->
                check(put(extension.lowercase(Locale.ROOT), format) == null) {
                    "duplicate built-in configuration extension '$extension'"
                }
            }
        }
    }

    /** Finds a built-in format from the final lowercase-insensitive extension in [path]. */
    public fun forPath(path: String): ConfigFormat? {
        val name = path.substringAfterLast('/').substringAfterLast('\\')
        val extension = name.substringAfterLast('.', missingDelimiterValue = "")
        return extension.takeIf(String::isNotEmpty)?.lowercase(Locale.ROOT)?.let(byExtension::get)
    }
}
