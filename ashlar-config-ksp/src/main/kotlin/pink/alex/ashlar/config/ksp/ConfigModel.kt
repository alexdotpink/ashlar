package pink.alex.ashlar.config.ksp

internal data class ConfigModuleModel(
    val roots: List<ConfigRootModel>,
    val orphanValidators: List<ConfigValidatorModel> = emptyList(),
    val orphanMigrations: List<ConfigMigrationModel> = emptyList(),
)

internal data class ConfigRootModel(
    val type: ConfigTypeModel,
    val dataClass: Boolean,
    val final: Boolean,
    val serializable: Boolean,
    val constructorParameters: List<ConstructorParameterModel>,
    val declarations: List<ConfigDeclarationModel>,
    val keyNames: List<ConfigKeyNameModel> = emptyList(),
    val comments: List<ConfigCommentModel>,
    val validators: List<ConfigValidatorModel>,
    val migrations: List<ConfigMigrationModel>,
)

internal data class ConfigKeyNameModel(
    val descriptorPath: List<String>,
    val externalName: String,
)

internal data class ConfigTypeModel(
    val packageName: String,
    val typeNames: List<String>,
) {
    val qualifiedName: String
        get() = (listOf(packageName) + typeNames).filter(String::isNotBlank).joinToString(".")
}

internal data class ConstructorParameterModel(
    val name: String,
    val hasDefault: Boolean,
)

internal data class ConfigDeclarationModel(
    val path: String,
    val schemaVersion: Int,
    val unversionedSchema: Int,
    val reloadMode: String,
    val backups: Int,
    val maximumBytes: Long,
    val qualifier: ConfigTypeModel?,
    val qualifierIsDependencyQualifier: Boolean = true,
)

internal data class ConfigCommentModel(
    val path: List<String>,
    val text: String,
)

internal data class CallableModel(
    val packageName: String,
    val name: String,
) {
    val qualifiedName: String
        get() = if (packageName.isBlank()) name else "$packageName.$name"
}

internal data class ConfigValidatorModel(
    val callable: CallableModel,
    val rootType: ConfigTypeModel,
    val receiverIsValidationScope: Boolean,
    val topLevel: Boolean,
    val suspending: Boolean,
    val returnType: ConfigTypeModel?,
    val parameterCount: Int,
    val visible: Boolean,
    val generic: Boolean,
) {
    companion object {
        fun valid(
            callable: CallableModel,
            rootType: ConfigTypeModel,
        ): ConfigValidatorModel = ConfigValidatorModel(
            callable = callable,
            rootType = rootType,
            receiverIsValidationScope = true,
            topLevel = true,
            suspending = false,
            returnType = ConfigTypeModel("kotlin", listOf("Unit")),
            parameterCount = 0,
            visible = true,
            generic = false,
        )
    }
}

internal data class ConfigMigrationModel(
    val callable: CallableModel,
    val rootType: ConfigTypeModel,
    val fromSchema: Int,
    val sourceType: ConfigTypeModel?,
    val targetType: ConfigTypeModel?,
    val sourceSerializable: Boolean,
    val targetSerializable: Boolean,
    val sourceKeyNames: List<ConfigKeyNameModel> = emptyList(),
    val targetKeyNames: List<ConfigKeyNameModel> = emptyList(),
    val topLevel: Boolean,
    val suspending: Boolean,
    val parameterCount: Int,
    val visible: Boolean,
    val generic: Boolean,
) {
    companion object {
        fun valid(
            callable: CallableModel,
            rootType: ConfigTypeModel,
            fromSchema: Int,
            sourceType: ConfigTypeModel,
            targetType: ConfigTypeModel,
        ): ConfigMigrationModel = ConfigMigrationModel(
            callable = callable,
            rootType = rootType,
            fromSchema = fromSchema,
            sourceType = sourceType,
            targetType = targetType,
            sourceSerializable = true,
            targetSerializable = true,
            sourceKeyNames = emptyList(),
            targetKeyNames = emptyList(),
            topLevel = true,
            suspending = false,
            parameterCount = 0,
            visible = true,
            generic = false,
        )
    }
}
