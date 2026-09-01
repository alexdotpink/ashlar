package pink.alex.ashlar.config.format

import org.junit.jupiter.api.Test
import pink.alex.ashlar.config.ConfigLimits
import pink.alex.ashlar.config.ConfigParse
import pink.alex.ashlar.config.ConfigProblemCategory
import pink.alex.ashlar.config.ConfigSource
import pink.alex.ashlar.config.ConfigValue
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class JsonConfigFormatTest {
    @Test
    fun `strict JSON parses a bounded object and patches values in stable order`() {
        val parsed = assertIs<ConfigParse.Accepted>(
            JsonConfigFormat.parse(
                ConfigSource(
                    "settings.json",
                    """
                    {
                        "name": "spawn",
                        "enabled": true
                    }
                    """.trimIndent() + "\n",
                ),
            ),
        )

        val patched = parsed.document.patch(
            ConfigValue.ObjectValue(
                linkedMapOf(
                    "name" to ConfigValue.StringValue("market"),
                    "enabled" to ConfigValue.BooleanValue(true),
                    "limit" to ConfigValue.IntegerValue(8),
                ),
            ),
        )

        assertEquals(
            """
            {
                "name": "market",
                "enabled": true,
                "limit": 8
            }
            """.trimIndent() + "\n",
            JsonConfigFormat.write(patched),
        )
    }

    @Test
    fun `strict JSON rejects duplicate keys at their second location`() {
        val parsed = assertIs<ConfigParse.Rejected>(
            JsonConfigFormat.parse(ConfigSource("settings.json", "{\n  \"port\": 1,\n  \"port\": 2\n}")),
        )

        val problem = parsed.problems.single()
        assertEquals(ConfigProblemCategory.DUPLICATE_KEY, problem.category)
        assertEquals(3, problem.location?.line)
        assertEquals(3, problem.location?.column)
    }

    @Test
    fun `strict JSON enforces UTF-8 bytes depth and scalar limits`() {
        val byteProblem = assertIs<ConfigParse.Rejected>(
            JsonConfigFormat.parse(ConfigSource("settings.json", "{\"emoji\":\"💗\"}"), ConfigLimits(maximumBytes = 14)),
        ).problems.single()
        assertEquals(ConfigProblemCategory.RESOURCE_LIMIT, byteProblem.category)

        val depthProblem = assertIs<ConfigParse.Rejected>(
            JsonConfigFormat.parse(ConfigSource("settings.json", "{\"a\":{\"b\":1}}"), ConfigLimits(maximumDepth = 1)),
        ).problems.single()
        assertEquals(ConfigProblemCategory.RESOURCE_LIMIT, depthProblem.category)

        val scalarProblem = assertIs<ConfigParse.Rejected>(
            JsonConfigFormat.parse(
                ConfigSource("settings.json", "{\"message\":\"hello\"}"),
                ConfigLimits(maximumScalarCharacters = 4),
            ),
        ).problems.single()
        assertEquals(ConfigProblemCategory.RESOURCE_LIMIT, scalarProblem.category)
    }

    @Test
    fun `strict JSON rejects comments and a non-object root`() {
        val comments = assertIs<ConfigParse.Rejected>(
            JsonConfigFormat.parse(ConfigSource("settings.json", "{ // nope\n \"value\": 1\n}")),
        )
        assertEquals(ConfigProblemCategory.SYNTAX, comments.problems.single().category)

        val array = assertIs<ConfigParse.Rejected>(
            JsonConfigFormat.parse(ConfigSource("settings.json", "[1, 2]")),
        )
        assertEquals(ConfigProblemCategory.SYNTAX, array.problems.single().category)
        assertTrue(array.problems.single().message.contains("object"))
    }

    @Test
    fun `strict JSON rejects non RFC whitespace`() {
        val parsed = assertIs<ConfigParse.Rejected>(
            JsonConfigFormat.parse(ConfigSource("settings.json", "{\u00a0\"value\": 1}")),
        )
        assertEquals(ConfigProblemCategory.SYNTAX, parsed.problems.single().category)
    }
}
