package pink.alex.ashlar.config.ksp

import org.junit.jupiter.api.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

class ConfigCodeWriterTest {
    @Test
    fun `initializer contains only static metadata and direct calls`() {
        val generated = ConfigCodeWriter().file(
            ConfigModuleModel(
                roots = listOf(
                    ConfigRootModel(
                        type = ConfigTypeModel("example", listOf("Settings")),
                        dataClass = true,
                        final = true,
                        serializable = true,
                        constructorParameters = listOf(ConstructorParameterModel("message", true)),
                        declarations = listOf(
                            ConfigDeclarationModel(
                                path = "settings.yml",
                                schemaVersion = 2,
                                unversionedSchema = 1,
                                reloadMode = "WATCH",
                                backups = 5,
                                maximumBytes = 1_048_576,
                                qualifier = ConfigTypeModel("example", listOf("Production")),
                            ),
                        ),
                        comments = listOf(
                            ConfigCommentModel(emptyList(), "Server settings."),
                            ConfigCommentModel(listOf("welcome-message"), "Shown when a player joins."),
                        ),
                        keyNames = listOf(
                            ConfigKeyNameModel(listOf("welcomeMessage"), "welcome-message"),
                            ConfigKeyNameModel(listOf("literalName"), "literalName"),
                        ),
                        validators = listOf(
                            ConfigValidatorModel.valid(
                                callable = CallableModel("example", "validateSettings"),
                                rootType = ConfigTypeModel("example", listOf("Settings")),
                            ),
                        ),
                        migrations = listOf(
                            ConfigMigrationModel.valid(
                                callable = CallableModel("example", "toSettings"),
                                rootType = ConfigTypeModel("example", listOf("Settings")),
                                fromSchema = 1,
                                sourceType = ConfigTypeModel("example", listOf("SettingsV1")),
                                targetType = ConfigTypeModel("example", listOf("Settings")),
                            ).copy(
                                sourceKeyNames = listOf(ConfigKeyNameModel(listOf("oldValue"), "old-value")),
                                targetKeyNames = listOf(ConfigKeyNameModel(listOf("literalName"), "literalName")),
                            ),
                        ),
                    ),
                ),
            ),
        ).second.toString()

        assertContains(generated, "@Contributes")
        assertContains(generated, "@Inject")
        assertContains(generated, "DependencyGraphInitializer")
        assertContains(generated, "ConfigurationBootstrap.install(")
        assertContains(generated, "rootType = Settings::class")
        assertContains(generated, "rawType = ConfigHandle::class")
        assertContains(generated, "DependencyType<Settings>(Settings::class)")
        assertContains(generated, "qualifier = Production::class")
        assertContains(generated, "serializer = Settings.serializer()")
        assertContains(generated, "ConfigKeyPath(\"welcomeMessage\") to \"welcome-message\"")
        assertContains(generated, "ConfigKeyPath(\"literalName\") to \"literalName\"")
        assertContains(generated, "ConfigKeyPath(\"welcome-message\") to \"Shown when a player joins.\"")
        assertContains(generated, "configValidator { validateSettings() }")
        assertContains(generated, "sourceSerializer = SettingsV1.serializer()")
        assertContains(generated, "targetSerializer = Settings.serializer()")
        assertContains(generated, "sourceKeyNames = mapOf(")
        assertContains(generated, "ConfigKeyPath(\"oldValue\") to \"old-value\"")
        assertContains(generated, "targetKeyNames = mapOf(")
        assertContains(generated, "migrate = { value -> value.toSettings() }")
        assertFalse(generated.contains("ServiceLoader"))
        assertFalse(generated.contains("Files."))
        assertFalse(generated.contains("WatchService"))
    }
}
