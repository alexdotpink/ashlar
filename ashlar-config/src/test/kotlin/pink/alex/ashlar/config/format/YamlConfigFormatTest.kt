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

class YamlConfigFormatTest {
    @Test
    fun `safe YAML 1_2 preserves every comment through nested semantic patches`() {
        val source = """
            # operator heading
            name: spawn # chosen by ops
            nested:
              # nested block
              enabled: true
            # standalone tail
            obsolete: 4
        """.trimIndent() + "\n"
        val parsed = assertIs<ConfigParse.Accepted>(YamlConfigFormat.parse(ConfigSource("settings.yml", source)))

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
        val written = YamlConfigFormat.write(patched)

        listOf(
            "# operator heading",
            "# chosen by ops",
            "# nested block",
            "# standalone tail",
            "# A generated default.",
        ).forEach { assertContains(written, it) }
        val reparsed = assertIs<ConfigParse.Accepted>(YamlConfigFormat.parse(ConfigSource("settings.yml", written)))
        assertEquals(patched.value, reparsed.document.value)
    }

    @Test
    fun `safe YAML rejects duplicate keys custom tags and a non-mapping root`() {
        val duplicate = assertIs<ConfigParse.Rejected>(
            YamlConfigFormat.parse(ConfigSource("settings.yml", "port: 1\nport: 2\n")),
        )
        assertEquals(ConfigProblemCategory.DUPLICATE_KEY, duplicate.problems.single().category)
        assertEquals(2, duplicate.problems.single().location?.line)

        val customTag = assertIs<ConfigParse.Rejected>(
            YamlConfigFormat.parse(ConfigSource("settings.yml", "value: !plugin thing\n")),
        )
        assertEquals(ConfigProblemCategory.UNSUPPORTED_FEATURE, customTag.problems.single().category)

        val sequence = assertIs<ConfigParse.Rejected>(
            YamlConfigFormat.parse(ConfigSource("settings.yml", "- one\n- two\n")),
        )
        assertEquals(ConfigProblemCategory.SYNTAX, sequence.problems.single().category)
    }

    @Test
    fun `safe YAML bounds UTF-8 bytes depth scalars and aliases`() {
        val byteLimit = assertIs<ConfigParse.Rejected>(
            YamlConfigFormat.parse(ConfigSource("settings.yml", "emoji: 💗\n"), ConfigLimits(maximumBytes = 10)),
        )
        assertEquals(ConfigProblemCategory.RESOURCE_LIMIT, byteLimit.problems.single().category)

        val depth = assertIs<ConfigParse.Rejected>(
            YamlConfigFormat.parse(ConfigSource("settings.yml", "a:\n  b: 1\n"), ConfigLimits(maximumDepth = 1)),
        )
        assertEquals(ConfigProblemCategory.RESOURCE_LIMIT, depth.problems.single().category)

        val scalar = assertIs<ConfigParse.Rejected>(
            YamlConfigFormat.parse(ConfigSource("settings.yml", "message: hello\n"), ConfigLimits(maximumScalarCharacters = 4)),
        )
        assertEquals(ConfigProblemCategory.RESOURCE_LIMIT, scalar.problems.single().category)

        val aliases = assertIs<ConfigParse.Rejected>(
            YamlConfigFormat.parse(
                ConfigSource("settings.yml", "base: &base [1]\na: *base\nb: *base\n"),
                ConfigLimits(maximumAliases = 1),
            ),
        )
        assertEquals(ConfigProblemCategory.RESOURCE_LIMIT, aliases.problems.single().category)
    }

    @Test
    fun `YAML boolean resolution follows the 1_2 core schema`() {
        val parsed = assertIs<ConfigParse.Accepted>(
            YamlConfigFormat.parse(ConfigSource("settings.yml", "modern: true\nlegacy: yes\n")),
        )
        assertEquals(ConfigValue.BooleanValue(true), parsed.document.value.entries["modern"])
        assertEquals(ConfigValue.StringValue("yes"), parsed.document.value.entries["legacy"])
    }
}
