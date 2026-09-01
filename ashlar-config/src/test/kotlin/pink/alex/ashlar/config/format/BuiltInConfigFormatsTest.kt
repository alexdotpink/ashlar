package pink.alex.ashlar.config.format

import org.junit.jupiter.api.Test
import pink.alex.ashlar.config.ConfigKeyPath
import pink.alex.ashlar.config.ConfigParse
import pink.alex.ashlar.config.ConfigSource
import pink.alex.ashlar.config.ConfigValue
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class BuiltInConfigFormatsTest {
    private val complete = ConfigValue.ObjectValue(
        linkedMapOf(
            "name" to ConfigValue.StringValue("spawn"),
            "enabled" to ConfigValue.BooleanValue(true),
            "limit" to ConfigValue.IntegerValue(5),
            "nested" to ConfigValue.ObjectValue(mapOf("message" to ConfigValue.StringValue("hello"))),
        ),
    )

    @Test
    fun `registry resolves every extension case insensitively`() {
        assertEquals("yaml", BuiltInConfigFormats.forPath("plugins/demo/settings.YML")?.id)
        assertEquals("yaml", BuiltInConfigFormats.forPath("settings.yaml")?.id)
        assertEquals("toml", BuiltInConfigFormats.forPath("settings.TOML")?.id)
        assertEquals("json", BuiltInConfigFormats.forPath("settings.json")?.id)
        assertEquals("jsonc", BuiltInConfigFormats.forPath("settings.JSONC")?.id)
        assertNull(BuiltInConfigFormats.forPath("settings.conf"))
        assertNull(BuiltInConfigFormats.forPath("settings"))
    }

    @Test
    fun `every built-in creates a complete parseable document in declaration order`() {
        BuiltInConfigFormats.all.forEach { format ->
            val created = format.create(
                complete,
                mapOf(
                    ConfigKeyPath(emptyList()) to "Configuration heading.",
                    ConfigKeyPath("limit") to "Maximum amount.",
                ),
            )
            val source = format.write(created)
            val parsed = assertIs<ConfigParse.Accepted>(format.parse(ConfigSource("settings.${format.extensions.first()}", source)))

            assertEquals(complete, parsed.document.value, format.id)
            assertEquals(listOf("name", "enabled", "limit", "nested"), parsed.document.value.entries.keys.toList(), format.id)
            if (format.preservesComments) {
                assertContains(source, "Configuration heading.", message = format.id)
                assertContains(source, "Maximum amount.", message = format.id)
            }
        }
    }

    @Test
    fun `built-ins retain source locations for nested keys`() {
        val fixtures = mapOf(
            JsonConfigFormat to "{\n  \"nested\": {\n    \"message\": \"hello\"\n  }\n}\n",
            JsoncConfigFormat to "{\n  \"nested\": {\n    \"message\": \"hello\"\n  }\n}\n",
            YamlConfigFormat to "nested:\n  message: hello\n",
            TomlConfigFormat to "[nested]\nmessage = \"hello\"\n",
        )
        fixtures.forEach { (format, source) ->
            val document = assertIs<ConfigParse.Accepted>(format.parse(ConfigSource("settings", source))).document
            assertEquals(3.takeIf { format.id.startsWith("json") } ?: 2, document.location(ConfigKeyPath("nested", "message"))?.line)
        }
    }
}
