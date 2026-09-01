package pink.alex.ashlar.config.ksp

import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.MemberName
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.TypeSpec
import java.security.MessageDigest

internal class ConfigCodeWriter {
    fun file(module: ConfigModuleModel): Pair<String, FileSpec> {
        val className = generatedClassName(module)
        val initializer = TypeSpec.classBuilder(className)
            .addModifiers(KModifier.INTERNAL)
            .addAnnotation(AnnotationSpec.builder(CONTRIBUTES).build())
            .addAnnotation(AnnotationSpec.builder(INJECT).build())
            .addSuperinterface(DEPENDENCY_GRAPH_INITIALIZER)
            .addFunction(initialize(module))
            .build()
        return className to FileSpec.builder(GENERATED_PACKAGE, className)
            .addType(initializer)
            .build()
    }

    private fun initialize(module: ConfigModuleModel): FunSpec = FunSpec.builder("initialize")
        .addModifiers(KModifier.OVERRIDE)
        .addParameter("graph", DEPENDENCY_GRAPH)
        .returns(AUTO_CLOSEABLE)
        .addCode(
            CodeBlock.builder()
                .add("return %T.install(\n", CONFIGURATION_BOOTSTRAP)
                .indent()
                .add("graph = graph,\n")
                .add("definitions = %L,\n", definitions(module))
                .unindent()
                .add(")\n")
                .build(),
        )
        .build()

    private fun definitions(module: ConfigModuleModel): CodeBlock = list(
        module.roots.flatMap { root -> root.declarations.map { declaration -> root to declaration } },
    ) { (root, declaration) -> definition(root, declaration) }

    private fun definition(
        root: ConfigRootModel,
        declaration: ConfigDeclarationModel,
    ): CodeBlock {
        val rootClass = root.type.className()
        return CodeBlock.builder()
            .add("%T(\n", CONFIG_DEFINITION)
            .indent()
            .add("rootType = %T::class,\n", rootClass)
            .add("handleKey = %L,\n", handleKey(root, declaration))
            .add("path = %S,\n", declaration.path)
            .add("schemaVersion = %L,\n", declaration.schemaVersion)
            .add("unversionedSchema = %L,\n", declaration.unversionedSchema)
            .add("reloadMode = %T.%L,\n", CONFIG_RELOAD_MODE, declaration.reloadMode)
            .add("backups = %L,\n", declaration.backups)
            .add("limits = %T(maximumBytes = %LL),\n", CONFIG_LIMITS, declaration.maximumBytes)
            .add("serializer = %T.serializer(),\n", rootClass)
            .add("keyNames = %L,\n", keyNames(root.keyNames))
            .add("validationKeyNames = %L,\n", stringMap(root.validationKeyNames))
            .add("comments = %L,\n", comments(root.comments))
            .add("validators = %L,\n", validators(root.validators))
            .add("migrations = %L,\n", migrations(root.migrations))
            .unindent()
            .add(")")
            .build()
    }

    private fun keyNames(names: List<ConfigKeyNameModel>): CodeBlock = map(names) { name ->
        CodeBlock.of(
            "%T(%L) to %S",
            CONFIG_KEY_PATH,
            name.descriptorPath.joinToCode { segment -> CodeBlock.of("%S", segment) },
            name.externalName,
        )
    }

    private fun stringMap(values: Map<String, String>): CodeBlock = map(values.entries.toList()) { entry ->
        CodeBlock.of("%S to %S", entry.key, entry.value)
    }

    private fun handleKey(
        root: ConfigRootModel,
        declaration: ConfigDeclarationModel,
    ): CodeBlock {
        val rootClass = root.type.className()
        val handleType = CONFIG_HANDLE.parameterizedBy(rootClass)
        return CodeBlock.builder()
            .add("%T<%T>(\n", DEPENDENCY_KEY, handleType)
            .indent()
            .add("dependencyType = %T<%T>(\n", DEPENDENCY_TYPE, handleType)
            .indent()
            .add("rawType = %T::class,\n", CONFIG_HANDLE)
            .add(
                "arguments = listOf(%T<%T>(%T::class)),\n",
                DEPENDENCY_TYPE,
                rootClass,
                rootClass,
            )
            .unindent()
            .add("),\n")
            .apply {
                declaration.qualifier?.let { qualifier ->
                    add("qualifier = %T::class,\n", qualifier.className())
                }
            }
            .unindent()
            .add(")")
            .build()
    }

    private fun comments(comments: List<ConfigCommentModel>): CodeBlock = map(comments) { comment ->
        CodeBlock.of(
            "%T(%L) to %S",
            CONFIG_KEY_PATH,
            comment.path.joinToCode { segment -> CodeBlock.of("%S", segment) },
            comment.text,
        )
    }

    private fun validators(validators: List<ConfigValidatorModel>): CodeBlock = list(validators) { validator ->
        CodeBlock.of(
            "%M { %M() }",
            CONFIG_VALIDATOR,
            MemberName(validator.callable.packageName, validator.callable.name),
        )
    }

    private fun migrations(migrations: List<ConfigMigrationModel>): CodeBlock =
        list(migrations.sortedBy(ConfigMigrationModel::fromSchema)) { migration ->
            val source = requireNotNull(migration.sourceType).className()
            val target = requireNotNull(migration.targetType).className()
            CodeBlock.builder()
                .add("%M(\n", CONFIG_MIGRATION)
                .indent()
                .add("fromSchema = %L,\n", migration.fromSchema)
                .add("sourceSerializer = %T.serializer(),\n", source)
                .add("targetSerializer = %T.serializer(),\n", target)
                .add("sourceKeyNames = %L,\n", keyNames(migration.sourceKeyNames))
                .add("targetKeyNames = %L,\n", keyNames(migration.targetKeyNames))
                .add(
                    "migrate = { value -> value.%M() },\n",
                    MemberName(migration.callable.packageName, migration.callable.name),
                )
                .unindent()
                .add(")")
                .build()
        }

    private fun generatedClassName(module: ConfigModuleModel): String {
        val identity = module.roots.flatMap { root ->
            root.declarations.map { declaration ->
                "${root.type.qualifiedName}|${declaration.qualifier?.qualifiedName}|${declaration.path}"
            }
        }.sorted().joinToString("|")
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(identity.toByteArray())
            .take(6)
            .joinToString("") { byte -> "%02x".format(byte) }
        return "AshlarConfigurationInitializer_$digest"
    }

    private fun ConfigTypeModel.className(): ClassName = ClassName(packageName, typeNames)

    private fun <T> list(
        values: List<T>,
        render: (T) -> CodeBlock,
    ): CodeBlock {
        if (values.isEmpty()) return CodeBlock.of("emptyList()")
        return CodeBlock.builder().add("listOf(\n").indent().apply {
            values.forEach { value -> add("%L,\n", render(value)) }
        }.unindent().add(")").build()
    }

    private fun <T> map(
        values: List<T>,
        render: (T) -> CodeBlock,
    ): CodeBlock {
        if (values.isEmpty()) return CodeBlock.of("emptyMap()")
        return CodeBlock.builder().add("mapOf(\n").indent().apply {
            values.forEach { value -> add("%L,\n", render(value)) }
        }.unindent().add(")").build()
    }

    private fun <T> List<T>.joinToCode(render: (T) -> CodeBlock): CodeBlock {
        val result = CodeBlock.builder()
        forEachIndexed { index, value ->
            if (index > 0) result.add(", ")
            result.add("%L", render(value))
        }
        return result.build()
    }

    companion object {
        const val GENERATED_PACKAGE: String = "pink.alex.ashlar.generated"

        private val CONTRIBUTES = ClassName("pink.alex.ashlar.di", "Contributes")
        private val AUTO_CLOSEABLE = ClassName("java.lang", "AutoCloseable")
        private val INJECT = ClassName("pink.alex.ashlar.di", "Inject")
        private val DEPENDENCY_GRAPH_INITIALIZER = ClassName("pink.alex.ashlar.di", "DependencyGraphInitializer")
        private val DEPENDENCY_GRAPH = ClassName("pink.alex.ashlar.di", "DependencyGraph")
        private val DEPENDENCY_KEY = ClassName("pink.alex.ashlar.di", "DependencyKey")
        private val DEPENDENCY_TYPE = ClassName("pink.alex.ashlar.di", "DependencyType")
        private val CONFIG_HANDLE = ClassName("pink.alex.ashlar.config", "ConfigHandle")
        private val CONFIG_RELOAD_MODE = ClassName("pink.alex.ashlar.config", "ConfigReloadMode")
        private val CONFIG_LIMITS = ClassName("pink.alex.ashlar.config", "ConfigLimits")
        private val CONFIG_KEY_PATH = ClassName("pink.alex.ashlar.config", "ConfigKeyPath")
        private val CONFIG_DEFINITION = ClassName("pink.alex.ashlar.config.codegen", "ConfigDefinition")
        private val CONFIGURATION_BOOTSTRAP = ClassName("pink.alex.ashlar.config.codegen", "ConfigurationBootstrap")
        private val CONFIG_VALIDATOR = MemberName("pink.alex.ashlar.config.codegen", "configValidator")
        private val CONFIG_MIGRATION = MemberName("pink.alex.ashlar.config.codegen", "configMigration")
    }
}
