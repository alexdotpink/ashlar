package pink.alex.ashlar.config.ksp

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConfigModelValidatorTest {
    private val validator = ConfigModelValidator()

    @Test
    fun `accepts a complete sequential configuration declaration`() {
        assertTrue(validator.validate(module()).isEmpty())
    }

    @Test
    fun `rejects roots that cannot be created from defaults`() {
        val invalid = root().copy(
            dataClass = false,
            final = false,
            serializable = false,
            constructorParameters = listOf(
                ConstructorParameterModel("host", hasDefault = false),
                ConstructorParameterModel("port", hasDefault = true),
            ),
        )

        assertEquals(
            listOf(
                "Configuration root 'example.Settings' must be a data class",
                "Configuration root 'example.Settings' must be final",
                "Configuration root 'example.Settings' must be annotated with @Serializable",
                "Configuration root 'example.Settings' constructor parameter 'host' must have a default value",
            ),
            validator.validate(ConfigModuleModel(listOf(invalid))),
        )
    }

    @Test
    fun `rejects unsafe paths invalid bounds and duplicate exact handle keys`() {
        val duplicate = root().copy(
            declarations = listOf(
                declaration(path = "../settings.yml", schemaVersion = 0, unversionedSchema = 2, backups = -1, maximumBytes = 0),
                declaration(path = "/absolute.yml"),
            ),
        )

        assertEquals(
            listOf(
                "Configuration path '../settings.yml' for 'example.Settings' must be a safe relative path beneath the plug-in data directory",
                "Configuration path '../settings.yml' for 'example.Settings' must declare schemaVersion at least 1",
                "Configuration path '../settings.yml' for 'example.Settings' has unversionedSchema 2 outside 1..schemaVersion",
                "Configuration path '../settings.yml' for 'example.Settings' cannot retain a negative number of backups",
                "Configuration path '../settings.yml' for 'example.Settings' must declare maximumBytes greater than zero",
                "Configuration path '/absolute.yml' for 'example.Settings' must be a safe relative path beneath the plug-in data directory",
                "Configuration handle 'example.Settings' is declared more than once without a qualifier",
            ),
            validator.validate(ConfigModuleModel(listOf(duplicate))),
        )
    }

    @Test
    fun `rejects validator and migration shapes that cannot be called directly`() {
        val invalid = root().copy(
            validators = listOf(
                validator().copy(
                    topLevel = false,
                    receiverIsValidationScope = false,
                    suspending = true,
                    returnType = type("kotlin", "String"),
                    parameterCount = 1,
                    visible = false,
                    generic = true,
                ),
            ),
            migrations = listOf(
                migration(from = 1).copy(
                    topLevel = false,
                    suspending = true,
                    parameterCount = 1,
                    visible = false,
                    generic = true,
                ),
            ),
        )

        assertEquals(
            listOf(
                "Configuration validation 'example.validateSettings' must be top-level",
                "Configuration validation 'example.validateSettings' must extend ConfigValidationScope<example.Settings>",
                "Configuration validation 'example.validateSettings' cannot suspend",
                "Configuration validation 'example.validateSettings' cannot declare value parameters",
                "Configuration validation 'example.validateSettings' must return Unit",
                "Configuration validation 'example.validateSettings' must be public or internal",
                "Configuration validation 'example.validateSettings' cannot declare type parameters",
                "Configuration migration 'example.toSettingsV2' must be top-level",
                "Configuration migration 'example.toSettingsV2' cannot suspend",
                "Configuration migration 'example.toSettingsV2' cannot declare value parameters",
                "Configuration migration 'example.toSettingsV2' must be public or internal",
                "Configuration migration 'example.toSettingsV2' cannot declare type parameters",
            ),
            validator.validate(ConfigModuleModel(listOf(invalid))),
        )
    }

    @Test
    fun `rejects a broken or ambiguous migration chain`() {
        val invalid = root().copy(
            declarations = listOf(declaration(schemaVersion = 4)),
            migrations = listOf(
                migration(from = 1).copy(targetType = type("example", "SettingsV2")),
                migration(from = 1).copy(targetType = type("example", "SettingsV2")),
                migration(from = 3, source = type("example", "WrongV3")),
            ),
        )

        assertEquals(
            listOf(
                "Configuration root 'example.Settings' declares 2 migrations from schema 1; exactly one is required",
                "Configuration root 'example.Settings' is missing a migration from schema 2 to 3",
                "Configuration migration 'example.toSettingsV4' source example.WrongV3 does not match the previous target example.SettingsV2",
            ),
            validator.validate(ConfigModuleModel(listOf(invalid))),
        )
    }

    @Test
    fun `rejects reserved and duplicate external root keys`() {
        val invalid = root().copy(
            keyNames = listOf(
                ConfigKeyNameModel(listOf("schema"), "_ashlar-schema"),
                ConfigKeyNameModel(listOf("first"), "same-key"),
                ConfigKeyNameModel(listOf("second"), "same-key"),
            ),
        )

        assertEquals(
            listOf(
                "Configuration root 'example.Settings' cannot declare reserved top-level key '_ashlar-schema'",
                "Configuration root 'example.Settings' maps multiple properties to external key 'same-key'",
            ),
            validator.validate(ConfigModuleModel(listOf(invalid))),
        )
    }

    private fun module(): ConfigModuleModel = ConfigModuleModel(listOf(root()))

    private fun root(): ConfigRootModel = ConfigRootModel(
        type = type("example", "Settings"),
        dataClass = true,
        final = true,
        serializable = true,
        constructorParameters = listOf(ConstructorParameterModel("message", hasDefault = true)),
        declarations = listOf(declaration()),
        comments = listOf(ConfigCommentModel(listOf("message"), "Message shown to players.")),
        validators = listOf(validator()),
        migrations = listOf(migration(from = 1)),
    )

    private fun declaration(
        path: String = "settings.yml",
        schemaVersion: Int = 2,
        unversionedSchema: Int = 1,
        backups: Int = 5,
        maximumBytes: Long = 1_048_576,
    ): ConfigDeclarationModel = ConfigDeclarationModel(
        path = path,
        schemaVersion = schemaVersion,
        unversionedSchema = unversionedSchema,
        reloadMode = "WATCH",
        backups = backups,
        maximumBytes = maximumBytes,
        qualifier = null,
    )

    private fun validator(): ConfigValidatorModel = ConfigValidatorModel(
        callable = CallableModel("example", "validateSettings"),
        rootType = type("example", "Settings"),
        receiverIsValidationScope = true,
        topLevel = true,
        suspending = false,
        returnType = type("kotlin", "Unit"),
        parameterCount = 0,
        visible = true,
        generic = false,
    )

    private fun migration(
        from: Int,
        source: ConfigTypeModel = type("example", "SettingsV1"),
    ): ConfigMigrationModel = ConfigMigrationModel(
        callable = CallableModel("example", if (from == 1) "toSettingsV2" else "toSettingsV4"),
        rootType = type("example", "Settings"),
        fromSchema = from,
        sourceType = source,
        targetType = type("example", "Settings"),
        sourceSerializable = true,
        targetSerializable = true,
        topLevel = true,
        suspending = false,
        parameterCount = 0,
        visible = true,
        generic = false,
    )

    private fun type(packageName: String, simpleName: String): ConfigTypeModel =
        ConfigTypeModel(packageName, listOf(simpleName))
}
