package pink.alex.ashlar.config.format

import org.junit.jupiter.api.Test
import pink.alex.ashlar.config.ConfigKeyPath
import pink.alex.ashlar.config.ConfigParse
import pink.alex.ashlar.config.ConfigProblemCategory
import pink.alex.ashlar.config.ConfigSource
import pink.alex.ashlar.config.ConfigValue
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs

class JsoncConfigFormatTest {
    @Test
    fun `JSONC preserves leading inline block nested and standalone comments while patching`() {
        val source = """
            // operator heading
            {
              /* before key */
              "name": "spawn", // chosen by ops
              "nested": {
                "enabled": true /* keep nested */
              },
              // standalone tail
              "obsolete": 4
            }
        """.trimIndent() + "\n"
        val parsed = assertIs<ConfigParse.Accepted>(JsoncConfigFormat.parse(ConfigSource("settings.jsonc", source)))

        val patched = parsed.document.patch(
            ConfigValue.ObjectValue(
                linkedMapOf(
                    "name" to ConfigValue.StringValue("market"),
                    "nested" to ConfigValue.ObjectValue(mapOf("enabled" to ConfigValue.BooleanValue(false))),
                    "added" to ConfigValue.IntegerValue(7),
                ),
            ),
            mapOf(ConfigKeyPath("added") to "A generated default."),
        )
        val written = JsoncConfigFormat.write(patched)

        listOf(
            "// operator heading",
            "/* before key */",
            "// chosen by ops",
            "/* keep nested */",
            "// standalone tail",
            "// A generated default.",
        ).forEach { comment -> assertContains(written, comment) }
        assertContains(written, "\"name\": \"market\"")
        assertContains(written, "\"enabled\": false")
        assertContains(written, "\"added\": 7")

        val reparsed = assertIs<ConfigParse.Accepted>(JsoncConfigFormat.parse(ConfigSource("settings.jsonc", written)))
        assertEquals(patched.value, reparsed.document.value)
    }

    @Test
    fun `JSONC duplicate keys reject even when separated by comments`() {
        val parsed = assertIs<ConfigParse.Rejected>(
            JsoncConfigFormat.parse(ConfigSource("settings.jsonc", "{ \"mode\": 1, /* still duplicate */ \"mode\": 2 }")),
        )
        assertEquals(ConfigProblemCategory.DUPLICATE_KEY, parsed.problems.single().category)
    }

    @Test
    fun `an unchanged JSONC document writes byte for byte`() {
        val source = "{\r\n  // exact\r\n  \"value\" : 1\r\n}\r\n"
        val parsed = assertIs<ConfigParse.Accepted>(JsoncConfigFormat.parse(ConfigSource("settings.jsonc", source)))

        assertEquals(source, JsoncConfigFormat.write(parsed.document.patch(parsed.document.value)))
    }

    @Test
    fun `JSONC retains comments when an array is replaced and does not invent comments from strings`() {
        val parsed = assertIs<ConfigParse.Accepted>(
            JsoncConfigFormat.parse(
                ConfigSource(
                    "settings.jsonc",
                    """
                        {
                          "items": [
                            1, // keep array comment
                            2
                          ],
                          "obsolete": "https://example.test/path"
                        }
                    """.trimIndent() + "\n",
                ),
            ),
        )

        val patched = parsed.document.patch(
            ConfigValue.ObjectValue(
                mapOf("items" to ConfigValue.ArrayValue(listOf(ConfigValue.IntegerValue(3)))),
            ),
        )
        val written = JsoncConfigFormat.write(patched)

        assertContains(written, "// keep array comment")
        kotlin.test.assertFalse(written.contains("//example.test"))
        val reparsed = assertIs<ConfigParse.Accepted>(JsoncConfigFormat.parse(ConfigSource("settings.jsonc", written)))
        assertEquals(patched.value, reparsed.document.value)
    }
}
