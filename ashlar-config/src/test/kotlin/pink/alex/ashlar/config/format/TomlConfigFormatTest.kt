package pink.alex.ashlar.config.format

import org.junit.jupiter.api.Test
import pink.alex.ashlar.config.ConfigKeyPath
import pink.alex.ashlar.config.ConfigLimits
import pink.alex.ashlar.config.ConfigParse
import pink.alex.ashlar.config.ConfigProblemCategory
import pink.alex.ashlar.config.ConfigSource
import pink.alex.ashlar.config.ConfigValue
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs

class TomlConfigFormatTest {
    @Test
    fun `TOML preserves heading inline array and standalone comments through a patch`() {
        val source = """
            # operator heading
            name = "spawn" # chosen by ops
            colors = [
              "red", # keep array note
              "blue",
            ]

            [nested]
            # nested block
            enabled = true

            # standalone tail
            obsolete = 4
        """.trimIndent() + "\n"
        val parsed = assertIs<ConfigParse.Accepted>(TomlConfigFormat.parse(ConfigSource("settings.toml", source)))
        val patched = parsed.document.patch(
            ConfigValue.ObjectValue(
                linkedMapOf(
                    "name" to ConfigValue.StringValue("market"),
                    "colors" to ConfigValue.ArrayValue(listOf(ConfigValue.StringValue("green"))),
                    "nested" to ConfigValue.ObjectValue(
                        linkedMapOf(
                            "enabled" to ConfigValue.BooleanValue(false),
                            "limit" to ConfigValue.IntegerValue(7),
                        ),
                    ),
                ),
            ),
            mapOf(ConfigKeyPath("nested", "limit") to "A generated default."),
        )
        val written = TomlConfigFormat.write(patched)

        listOf(
            "# operator heading",
            "# chosen by ops",
            "# keep array note",
            "# nested block",
            "# standalone tail",
            "# A generated default.",
        ).forEach { assertContains(written, it) }
        val reparsed = assertIs<ConfigParse.Accepted>(TomlConfigFormat.parse(ConfigSource("settings.toml", written)))
        assertEquals(patched.value, reparsed.document.value)
    }

    @Test
    fun `TOML rejects duplicate definitions with a source location`() {
        val parsed = assertIs<ConfigParse.Rejected>(
            TomlConfigFormat.parse(ConfigSource("settings.toml", "port = 1\nport = 2\n")),
        )
        assertEquals(ConfigProblemCategory.DUPLICATE_KEY, parsed.problems.single().category)
        assertEquals(2, parsed.problems.single().location?.line)
    }

    @Test
    fun `TOML enforces bytes depth and scalar limits`() {
        val bytes = assertIs<ConfigParse.Rejected>(
            TomlConfigFormat.parse(ConfigSource("settings.toml", "emoji = \"💗\"\n"), ConfigLimits(maximumBytes = 13)),
        )
        assertEquals(ConfigProblemCategory.RESOURCE_LIMIT, bytes.problems.single().category)

        val depth = assertIs<ConfigParse.Rejected>(
            TomlConfigFormat.parse(ConfigSource("settings.toml", "[a.b]\nvalue = 1\n"), ConfigLimits(maximumDepth = 2)),
        )
        assertEquals(ConfigProblemCategory.RESOURCE_LIMIT, depth.problems.single().category)

        val scalar = assertIs<ConfigParse.Rejected>(
            TomlConfigFormat.parse(ConfigSource("settings.toml", "message = \"hello\"\n"), ConfigLimits(maximumScalarCharacters = 4)),
        )
        assertEquals(ConfigProblemCategory.RESOURCE_LIMIT, scalar.problems.single().category)
    }

    @Test
    fun `unchanged TOML writes byte for byte`() {
        val source = "# exact\r\nvalue=1 # spacing\r\n"
        val parsed = assertIs<ConfigParse.Accepted>(TomlConfigFormat.parse(ConfigSource("settings.toml", source)))
        assertEquals(source, TomlConfigFormat.write(parsed.document.patch(parsed.document.value)))
    }

    @Test
    fun `TOML does not reinterpret hash characters inside multiline strings as comments`() {
        val parsed = assertIs<ConfigParse.Accepted>(
            TomlConfigFormat.parse(
                ConfigSource("settings.toml", "message = \"\"\"\n# value text, not a comment\nold\n\"\"\"\n"),
            ),
        )
        val patched = parsed.document.patch(
            ConfigValue.ObjectValue(mapOf("message" to ConfigValue.StringValue("new"))),
        )
        val written = TomlConfigFormat.write(patched)

        kotlin.test.assertFalse(written.contains("# value text"))
        val reparsed = assertIs<ConfigParse.Accepted>(TomlConfigFormat.parse(ConfigSource("settings.toml", written)))
        assertEquals(patched.value, reparsed.document.value)
    }
}
