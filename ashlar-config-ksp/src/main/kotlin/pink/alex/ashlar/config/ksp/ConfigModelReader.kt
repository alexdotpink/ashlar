package pink.alex.ashlar.config.ksp

import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSTypeAlias
import com.google.devtools.ksp.symbol.Modifier

internal class ConfigModelReader {
    fun root(declaration: KSClassDeclaration): ConfigRootModel {
        val metadata = propertyMetadata(declaration)
        return ConfigRootModel(
            type = declaration.typeModel(),
            dataClass = Modifier.DATA in declaration.modifiers,
            final = declaration.modifiers.none { modifier ->
                modifier == Modifier.OPEN || modifier == Modifier.ABSTRACT || modifier == Modifier.SEALED
            },
            serializable = declaration.annotationOrNull(SERIALIZABLE) != null,
            visible = declaration.isVisibleToGeneratedCode(),
            generic = declaration.typeParameters.isNotEmpty(),
            constructorParameters = declaration.primaryConstructor?.parameters.orEmpty().map { parameter ->
                ConstructorParameterModel(
                    name = parameter.name?.asString().orEmpty(),
                    hasDefault = parameter.hasDefault,
                )
            },
            declarations = declaration.annotations
                .filter { annotation -> annotation.qualifiedName() == CONFIG }
                .map(::configDeclaration)
                .toList(),
            keyNames = metadata.keyNames,
            validationKeyNames = metadata.validationKeyNames,
            comments = buildList {
                declaration.documentation()?.let { documentation ->
                    add(ConfigCommentModel(emptyList(), documentation))
                }
                addAll(metadata.comments)
            },
            validators = emptyList(),
            migrations = emptyList(),
        )
    }

    fun validator(function: KSFunctionDeclaration): ConfigValidatorModel {
        val receiver = function.extensionReceiver?.resolve()
        val root = receiver?.arguments?.singleOrNull()?.type?.resolve()?.classDeclarationOrNull()
        return ConfigValidatorModel(
            callable = function.callable(),
            rootType = root?.typeModel() ?: UNKNOWN_TYPE,
            receiverIsValidationScope = receiver?.declaration?.qualifiedName?.asString() == VALIDATION_SCOPE && root != null,
            topLevel = function.parentDeclaration == null,
            suspending = Modifier.SUSPEND in function.modifiers,
            returnType = function.returnType?.resolve()?.classDeclarationOrNull()?.typeModel(),
            parameterCount = function.parameters.size,
            visible = function.isVisibleToGeneratedCode(),
            generic = function.typeParameters.isNotEmpty(),
        )
    }

    fun migration(function: KSFunctionDeclaration): ConfigMigrationModel {
        val annotation = function.annotation(CONFIG_MIGRATION)
        val values = annotation.values()
        val root = (values.getValue("root") as KSType).classDeclarationOrNull()
            ?: error("@ConfigMigration root must be a concrete class")
        val source = function.extensionReceiver?.resolve()?.classDeclarationOrNull()
        val target = function.returnType?.resolve()?.classDeclarationOrNull()
        return ConfigMigrationModel(
            callable = function.callable(),
            rootType = root.typeModel(),
            fromSchema = values.getValue("from") as Int,
            sourceType = source?.typeModel(),
            targetType = target?.typeModel(),
            sourceSerializable = source?.annotationOrNull(SERIALIZABLE) != null,
            targetSerializable = target?.annotationOrNull(SERIALIZABLE) != null,
            sourceVisible = source?.isVisibleToGeneratedCode() ?: false,
            targetVisible = target?.isVisibleToGeneratedCode() ?: false,
            sourceGeneric = source?.typeParameters?.isNotEmpty() ?: false,
            targetGeneric = target?.typeParameters?.isNotEmpty() ?: false,
            sourceKeyNames = source?.let(::propertyMetadata)?.keyNames.orEmpty(),
            targetKeyNames = target?.let(::propertyMetadata)?.keyNames.orEmpty(),
            topLevel = function.parentDeclaration == null,
            suspending = Modifier.SUSPEND in function.modifiers,
            parameterCount = function.parameters.size,
            visible = function.isVisibleToGeneratedCode(),
            generic = function.typeParameters.isNotEmpty(),
        )
    }

    private fun configDeclaration(annotation: KSAnnotation): ConfigDeclarationModel {
        val values = annotation.values()
        val qualifierDeclaration = (values.getValue("qualifier") as KSType).classDeclarationOrNull()
            ?: error("@Config qualifier must be an annotation class")
        val qualifier = qualifierDeclaration.takeUnless { declaration ->
            declaration.qualifiedName?.asString() == ANNOTATION
        }
        return ConfigDeclarationModel(
            path = values.getValue("path") as String,
            schemaVersion = values.getValue("schemaVersion") as Int,
            unversionedSchema = values.getValue("unversionedSchema") as Int,
            reloadMode = values.getValue("reload").enumName(),
            backups = values.getValue("backups") as Int,
            maximumBytes = values.getValue("maximumBytes") as Long,
            qualifier = qualifier?.typeModel(),
            qualifierIsDependencyQualifier = qualifier?.let { declaration ->
                declaration.classKind == ClassKind.ANNOTATION_CLASS &&
                    declaration.annotationOrNull(DEPENDENCY_QUALIFIER) != null
            } ?: true,
        )
    }

    private fun propertyMetadata(root: KSClassDeclaration): PropertyMetadata = PropertyMetadata().also { metadata ->
        collectPropertyMetadata(
            declaration = root,
            descriptorPrefix = emptyList(),
            externalPrefix = emptyList(),
            visited = mutableSetOf(root.qualifiedName?.asString().orEmpty()),
            metadata = metadata,
        )
    }

    private fun collectPropertyMetadata(
        declaration: KSClassDeclaration,
        descriptorPrefix: List<String>,
        externalPrefix: List<String>,
        visited: MutableSet<String>,
        metadata: PropertyMetadata,
    ) {
        val properties = declaration.declarations.filterIsInstance<KSPropertyDeclaration>()
            .associateBy { property -> property.simpleName.asString() }
        declaration.primaryConstructor?.parameters.orEmpty().forEach { parameter ->
            val name = parameter.name?.asString() ?: return@forEach
            val property = properties[name] ?: return@forEach
            val explicitName = property.annotationOrNull(SERIAL_NAME)?.values()?.get("value") as? String
            val descriptorSegment = explicitName ?: name
            val externalSegment = explicitName ?: name.toKebabCase()
            val descriptorPath = descriptorPrefix + descriptorSegment
            val externalPath = externalPrefix + externalSegment
            metadata.keyNames += ConfigKeyNameModel(descriptorPath, externalSegment)
            if (descriptorPrefix.isEmpty()) metadata.validationKeyNames[name] = externalSegment
            property.documentation()?.let { documentation ->
                metadata.comments += ConfigCommentModel(externalPath, documentation)
            }
            property.type.resolve().serializableClasses().forEach { nested ->
                val nestedName = nested.qualifiedName?.asString() ?: return@forEach
                if (!visited.add(nestedName)) return@forEach
                collectPropertyMetadata(
                    declaration = nested,
                    descriptorPrefix = descriptorPath,
                    externalPrefix = externalPath,
                    visited = visited,
                    metadata = metadata,
                )
                visited.remove(nestedName)
            }
        }
    }

    private fun KSDeclaration.documentation(): String? {
        val text = docString.orEmpty().lines()
            .map(String::trim)
            .takeWhile { line -> !line.startsWith('@') }
            .joinToString("\n")
            .trim()
        return text.ifBlank { null }
    }

    private fun KSFunctionDeclaration.callable(): CallableModel = CallableModel(
        packageName = packageName.asString(),
        name = simpleName.asString(),
    )

    private fun KSDeclaration.typeModel(): ConfigTypeModel = ConfigTypeModel(
        packageName = packageName.asString(),
        typeNames = typeNames(),
    )

    private fun KSDeclaration.typeNames(): List<String> = buildList {
        var current: KSDeclaration? = this@typeNames
        while (current is KSClassDeclaration) {
            add(current.simpleName.asString())
            current = current.parentDeclaration
        }
    }.asReversed()

    private fun KSFunctionDeclaration.isVisibleToGeneratedCode(): Boolean =
        Modifier.PRIVATE !in modifiers && Modifier.PROTECTED !in modifiers

    private fun KSClassDeclaration.isVisibleToGeneratedCode(): Boolean =
        Modifier.PRIVATE !in modifiers && Modifier.PROTECTED !in modifiers

    private fun KSAnnotated.annotation(name: String): KSAnnotation =
        requireNotNull(annotationOrNull(name)) { "Missing annotation $name" }

    private fun KSAnnotated.annotationOrNull(name: String): KSAnnotation? =
        annotations.singleOrNull { annotation -> annotation.qualifiedName() == name }

    private fun KSAnnotation.qualifiedName(): String? =
        annotationType.resolve().declaration.qualifiedName?.asString()

    private fun KSAnnotation.values(): Map<String, Any?> =
        arguments.associate { argument -> requireNotNull(argument.name?.asString()) to argument.value }

    private fun KSType.classDeclarationOrNull(): KSClassDeclaration? = when (val declaration = declaration) {
        is KSClassDeclaration -> declaration
        is KSTypeAlias -> declaration.type.resolve().classDeclarationOrNull()
        else -> null
    }

    private fun KSType.serializableClasses(): Sequence<KSClassDeclaration> = sequence {
        val concrete = classDeclarationOrNull()
        if (concrete?.annotationOrNull(SERIALIZABLE) != null) {
            yield(concrete)
            return@sequence
        }
        arguments.forEach { argument ->
            argument.type?.resolve()?.let { type -> yieldAll(type.serializableClasses()) }
        }
    }

    private fun Any?.enumName(): String = when (this) {
        is KSType -> declaration.simpleName.asString()
        is KSClassDeclaration -> simpleName.asString()
        else -> toString().substringAfterLast('.')
    }

    private fun String.toKebabCase(): String =
        replace(Regex("([A-Z]+)([A-Z][a-z])"), "$1-$2")
            .replace(Regex("([a-z0-9])([A-Z])"), "$1-$2")
            .lowercase()

    private companion object {
        const val CONFIG = "pink.alex.ashlar.config.Config"
        const val CONFIG_MIGRATION = "pink.alex.ashlar.config.ConfigMigration"
        const val VALIDATION_SCOPE = "pink.alex.ashlar.config.ConfigValidationScope"
        const val SERIALIZABLE = "kotlinx.serialization.Serializable"
        const val SERIAL_NAME = "kotlinx.serialization.SerialName"
        const val DEPENDENCY_QUALIFIER = "pink.alex.ashlar.di.DependencyQualifier"
        const val ANNOTATION = "kotlin.Annotation"
        val UNKNOWN_TYPE = ConfigTypeModel("", listOf("<unknown>"))
    }

    private class PropertyMetadata(
        val keyNames: MutableList<ConfigKeyNameModel> = mutableListOf(),
        val validationKeyNames: MutableMap<String, String> = linkedMapOf(),
        val comments: MutableList<ConfigCommentModel> = mutableListOf(),
    )
}
