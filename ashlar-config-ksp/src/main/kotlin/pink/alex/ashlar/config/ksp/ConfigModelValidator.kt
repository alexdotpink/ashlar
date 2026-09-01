package pink.alex.ashlar.config.ksp

internal class ConfigModelValidator {
    fun validate(module: ConfigModuleModel): List<String> = buildList {
        module.roots.forEach { root -> validate(root, this) }
        module.orphanValidators.forEach { validator ->
            validate(validator, this)
            add(
                "Configuration validation '${validator.callable.qualifiedName}' targets " +
                    "${validator.rootType.qualifiedName}, which is not an @Config root",
            )
        }
        module.orphanMigrations.forEach { migration ->
            validate(migration, this)
            add(
                "Configuration migration '${migration.callable.qualifiedName}' targets " +
                    "${migration.rootType.qualifiedName}, which is not an @Config root",
            )
        }
        duplicatePaths(module).forEach { duplicate -> add(duplicate) }
        duplicateKeys(module).forEach { duplicate -> add(duplicate) }
    }

    private fun validate(
        root: ConfigRootModel,
        problems: MutableList<String>,
    ) {
        val name = root.type.qualifiedName
        if (!root.dataClass) problems += "Configuration root '$name' must be a data class"
        if (!root.final) problems += "Configuration root '$name' must be final"
        if (!root.serializable) problems += "Configuration root '$name' must be annotated with @Serializable"
        if (!root.visible) problems += "Configuration root '$name' must be public or internal"
        if (root.generic) problems += "Configuration root '$name' cannot declare type parameters"
        root.constructorParameters.filterNot(ConstructorParameterModel::hasDefault).forEach { parameter ->
            problems += "Configuration root '$name' constructor parameter '${parameter.name}' must have a default value"
        }
        root.keyNames.filter { key -> key.descriptorPath.size == 1 && key.externalName == SCHEMA_KEY }
            .forEach {
                problems += "Configuration root '$name' cannot declare reserved top-level key '$SCHEMA_KEY'"
            }
        root.keyNames.groupBy { key -> key.descriptorPath.dropLast(1) to key.externalName }
            .filterValues { keys -> keys.size > 1 }
            .values
            .forEach { keys ->
                problems += "Configuration root '$name' maps multiple properties to external key '${keys.first().externalName}'"
            }
        if (root.declarations.map(ConfigDeclarationModel::schemaVersion).distinct().size > 1) {
            problems += "Configuration root '$name' repeats @Config with different schemaVersion values"
        }
        if (root.declarations.map(ConfigDeclarationModel::unversionedSchema).distinct().size > 1) {
            problems += "Configuration root '$name' repeats @Config with different unversionedSchema values"
        }
        root.declarations.forEach { declaration -> validate(root, declaration, problems) }
        root.validators.forEach { validator -> validate(validator, problems) }
        root.migrations.forEach { migration -> validate(migration, problems) }
        validateMigrationChain(root, problems)
    }

    private fun validate(
        root: ConfigRootModel,
        declaration: ConfigDeclarationModel,
        problems: MutableList<String>,
    ) {
        val label = "Configuration path '${declaration.path}' for '${root.type.qualifiedName}'"
        if (!isSafeRelativePath(declaration.path)) {
            problems += "$label must be a safe relative path beneath the plug-in data directory"
        }
        if (declaration.schemaVersion < 1) problems += "$label must declare schemaVersion at least 1"
        if (declaration.unversionedSchema !in 0..declaration.schemaVersion) {
            problems += "$label has unversionedSchema ${declaration.unversionedSchema} outside 0..schemaVersion"
        }
        if (declaration.backups < 0) problems += "$label cannot retain a negative number of backups"
        if (declaration.backups > MAXIMUM_BACKUPS) {
            problems += "$label cannot retain more than $MAXIMUM_BACKUPS backups"
        }
        if (declaration.maximumBytes <= 0) problems += "$label must declare maximumBytes greater than zero"
        if (declaration.maximumBytes > MAXIMUM_BYTES) {
            problems += "$label cannot accept more than $MAXIMUM_BYTES bytes"
        }
        if (declaration.qualifier != null && !declaration.qualifierIsDependencyQualifier) {
            problems += "$label qualifier ${declaration.qualifier.qualifiedName} must be annotated with @DependencyQualifier"
        }
    }

    private fun validate(
        validator: ConfigValidatorModel,
        problems: MutableList<String>,
    ) {
        val label = "Configuration validation '${validator.callable.qualifiedName}'"
        if (!validator.topLevel) problems += "$label must be top-level"
        if (!validator.receiverIsValidationScope) {
            problems += "$label must extend ConfigValidationScope<${validator.rootType.qualifiedName}>"
        }
        if (validator.suspending) problems += "$label cannot suspend"
        if (validator.parameterCount != 0) problems += "$label cannot declare value parameters"
        if (validator.returnType?.qualifiedName != UNIT) problems += "$label must return Unit"
        if (!validator.visible) problems += "$label must be public or internal"
        if (validator.generic) problems += "$label cannot declare type parameters"
    }

    private fun validate(
        migration: ConfigMigrationModel,
        problems: MutableList<String>,
    ) {
        val label = "Configuration migration '${migration.callable.qualifiedName}'"
        if (!migration.topLevel) problems += "$label must be top-level"
        if (migration.suspending) problems += "$label cannot suspend"
        if (migration.parameterCount != 0) problems += "$label cannot declare value parameters"
        if (!migration.visible) problems += "$label must be public or internal"
        if (migration.generic) problems += "$label cannot declare type parameters"
        if (migration.sourceType == null) problems += "$label must declare an extension receiver"
        if (migration.targetType == null || migration.targetType.qualifiedName == UNIT) {
            problems += "$label must return the next serializable schema type"
        }
        if (migration.sourceType != null && !migration.sourceSerializable) {
            problems += "$label source ${migration.sourceType.qualifiedName} must be annotated with @Serializable"
        }
        if (!migration.sourceVisible) problems += "$label source schema type must be public or internal"
        if (!migration.targetVisible) problems += "$label target schema type must be public or internal"
        if (migration.sourceGeneric) problems += "$label source schema type cannot declare type parameters"
        if (migration.targetGeneric) problems += "$label target schema type cannot declare type parameters"
        if (migration.targetType != null && !migration.targetSerializable) {
            problems += "$label target ${migration.targetType.qualifiedName} must be annotated with @Serializable"
        }
    }

    private fun validateMigrationChain(
        root: ConfigRootModel,
        problems: MutableList<String>,
    ) {
        val currentSchema = root.declarations.maxOfOrNull(ConfigDeclarationModel::schemaVersion) ?: return
        val rootName = root.type.qualifiedName
        val bySchema = root.migrations.groupBy(ConfigMigrationModel::fromSchema)
        var previousTarget: ConfigTypeModel? = null
        for (schema in 1 until currentSchema) {
            val steps = bySchema[schema].orEmpty()
            when {
                steps.isEmpty() -> {
                    problems += "Configuration root '$rootName' is missing a migration from schema $schema to ${schema + 1}"
                    continue
                }
                steps.size > 1 -> {
                    problems += "Configuration root '$rootName' declares ${steps.size} migrations from schema $schema; exactly one is required"
                }
            }
            val step = steps.first()
            if (previousTarget != null && step.sourceType != previousTarget) {
                problems += "Configuration migration '${step.callable.qualifiedName}' source " +
                    "${step.sourceType?.qualifiedName ?: "<missing>"} does not match the previous target " +
                    previousTarget.qualifiedName
            }
            previousTarget = step.targetType
        }
        if (currentSchema > 1 && previousTarget != null && previousTarget != root.type) {
            val final = bySchema[currentSchema - 1]?.firstOrNull() ?: return
            problems += "Configuration migration '${final.callable.qualifiedName}' target " +
                "${previousTarget.qualifiedName} must be the current root $rootName"
        }
        bySchema.keys.filter { it < 1 || it >= currentSchema }.sorted().forEach { schema ->
            bySchema.getValue(schema).forEach { migration ->
                problems += "Configuration migration '${migration.callable.qualifiedName}' declares from=$schema outside 1..<schemaVersion"
            }
        }
    }

    private fun duplicateKeys(module: ConfigModuleModel): List<String> =
        module.roots.flatMap { root -> root.declarations.map { declaration -> root to declaration } }
            .groupBy { (root, declaration) -> root.type to declaration.qualifier }
            .filterValues { declarations -> declarations.size > 1 }
            .values
            .map { declarations ->
                val (root, declaration) = declarations.first()
                declaration.qualifier?.let { qualifier ->
                    "Configuration handle '${root.type.qualifiedName}' is declared more than once with qualifier ${qualifier.qualifiedName}"
                } ?: "Configuration handle '${root.type.qualifiedName}' is declared more than once without a qualifier"
            }

    private fun duplicatePaths(module: ConfigModuleModel): List<String> =
        module.roots.flatMap { root -> root.declarations.map { declaration -> root to declaration } }
            .groupBy { (_, declaration) -> normalizePath(declaration.path) }
            .filterValues { declarations -> declarations.size > 1 }
            .map { (path, declarations) ->
                val owners = declarations.map { (root) -> root.type.qualifiedName }.distinct()
                if (owners.size == 1) {
                    "Configuration path '$path' is declared more than once by ${owners.single()}"
                } else {
                    "Configuration path '$path' is declared by both ${owners.joinToString(" and ")}"
                }
            }

    private fun isSafeRelativePath(path: String): Boolean {
        if (path.isBlank() || '\u0000' in path) return false
        val normalized = path.replace('\\', '/')
        if (normalized.startsWith('/') || WINDOWS_DRIVE.matches(normalized)) return false
        val segments = normalized.split('/')
        return segments.none { it.isBlank() || it == "." || it == ".." }
    }

    private fun normalizePath(path: String): String = path.replace('\\', '/')

    private companion object {
        const val UNIT = "kotlin.Unit"
        const val SCHEMA_KEY = "_ashlar-schema"
        const val MAXIMUM_BACKUPS = 100
        const val MAXIMUM_BYTES = 67_108_864L
        val WINDOWS_DRIVE = Regex("^[A-Za-z]:.*")
    }
}
