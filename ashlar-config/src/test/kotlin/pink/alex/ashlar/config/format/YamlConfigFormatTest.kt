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

    @Test
    fun `safe YAML accepts only one 1_2 document`() {
        val oldDirective = assertIs<ConfigParse.Rejected>(
            YamlConfigFormat.parse(ConfigSource("settings.yml", "%YAML 1.1\n---\nenabled: true\n")),
        )
        assertEquals(ConfigProblemCategory.SYNTAX, oldDirective.problems.single().category)

        val multiple = assertIs<ConfigParse.Rejected>(
            YamlConfigFormat.parse(ConfigSource("settings.yml", "enabled: true\n---\nenabled: false\n")),
        )
        assertEquals(ConfigProblemCategory.SYNTAX, multiple.problems.single().category)

        val currentDirective = assertIs<ConfigParse.Accepted>(
            YamlConfigFormat.parse(ConfigSource("settings.yml", "%YAML 1.2\n---\nenabled: true\n")),
        )
        assertEquals(ConfigValue.BooleanValue(true), currentDirective.document.value.entries["enabled"])
    }

    @Test
    fun `safe YAML rejects recursive aliases before composing nodes`() {
        val recursive = assertIs<ConfigParse.Rejected>(
            YamlConfigFormat.parse(ConfigSource("settings.yml", "loop: &loop [*loop]\n")),
        )

        assertEquals(ConfigProblemCategory.UNSUPPORTED_FEATURE, recursive.problems.single().category)
    }

    @Test
    fun `YAML keeps descendant and removed-key comments orphaned at their parent`() {
        val parsed = assertIs<ConfigParse.Accepted>(
            YamlConfigFormat.parse(
                ConfigSource(
                    "settings.yml",
                    "nested:\n  # descendant\n  enabled: true\n# removed key\nobsolete: 4\n",
                ),
            ),
        )
        val replacement = ConfigValue.ObjectValue(
            linkedMapOf(
                "nested" to ConfigValue.StringValue("flat"),
                "added" to ConfigValue.IntegerValue(7),
            ),
        )

        val written = YamlConfigFormat.write(parsed.document.patch(replacement))

        assertContains(written, "# descendant")
        assertContains(written, "# removed key")
        kotlin.test.assertTrue(written.indexOf("# removed key") > written.indexOf("added:"))
        assertEquals(
            replacement,
            assertIs<ConfigParse.Accepted>(
                YamlConfigFormat.parse(ConfigSource("settings.yml", written)),
            ).document.value,
        )
    }
}
