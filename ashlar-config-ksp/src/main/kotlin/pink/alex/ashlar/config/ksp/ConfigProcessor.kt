package pink.alex.ashlar.config.ksp

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFile
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.validate

/** KSP entry point for Ashlar's static configuration declaration metadata. */
public class ConfigProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor = ConfigProcessor(
        codeGenerator = environment.codeGenerator,
        logger = environment.logger,
    )
}

internal class ConfigProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
    private val reader: ConfigModelReader = ConfigModelReader(),
    private val validator: ConfigModelValidator = ConfigModelValidator(),
    private val writer: ConfigCodeWriter = ConfigCodeWriter(),
) : SymbolProcessor {
    private val roots: MutableMap<String, ConfigRootModel> = linkedMapOf()
    private val validations: MutableMap<String, ConfigValidatorModel> = linkedMapOf()
    private val migrations: MutableMap<String, ConfigMigrationModel> = linkedMapOf()
    private val nodes: MutableMap<String, KSAnnotated> = linkedMapOf()
    private val sourceFiles: MutableSet<KSFile> = linkedSetOf()
    private var failed: Boolean = false
    private var generated: Boolean = false

    override fun process(resolver: Resolver): List<KSAnnotated> {
        val deferred = mutableListOf<KSAnnotated>()
        resolver.getSymbolsWithAnnotation(CONFIG).forEach { symbol ->
            val declaration = symbol as? KSClassDeclaration
            if (declaration == null) {
                logger.error("@Config may annotate only a class", symbol)
                failed = true
                return@forEach
            }
            process(
                declaration = declaration,
                identity = declaration.qualifiedName?.asString(),
                deferred = deferred,
                read = { annotated -> reader.root(annotated as KSClassDeclaration) },
                destination = roots,
            )
        }
        resolver.getSymbolsWithAnnotation(CONFIG_VALIDATION).forEach { symbol ->
            val function = symbol as? KSFunctionDeclaration
            if (function == null) {
                logger.error("@ConfigValidation may annotate only a function", symbol)
                failed = true
                return@forEach
            }
            process(
                declaration = function,
                identity = function.callableIdentity(),
                deferred = deferred,
                read = { annotated -> reader.validator(annotated as KSFunctionDeclaration) },
                destination = validations,
            )
        }
        resolver.getSymbolsWithAnnotation(CONFIG_MIGRATION).forEach { symbol ->
            val function = symbol as? KSFunctionDeclaration
            if (function == null) {
                logger.error("@ConfigMigration may annotate only a function", symbol)
                failed = true
                return@forEach
            }
            process(
                declaration = function,
                identity = function.callableIdentity(),
                deferred = deferred,
                read = { annotated -> reader.migration(annotated as KSFunctionDeclaration) },
                destination = migrations,
            )
        }
        resolver.getAllFiles().forEach(sourceFiles::add)
        if (deferred.isEmpty() && !generated) generate()
        return deferred
    }

    private fun generate() {
        val rootNames = roots.keys
        val associatedValidations = validations.values.groupBy { validator -> validator.rootType.qualifiedName }
        val associatedMigrations = migrations.values.groupBy { migration -> migration.rootType.qualifiedName }
        val module = ConfigModuleModel(
            roots = roots.values.map { root ->
                root.copy(
                    validators = associatedValidations[root.type.qualifiedName].orEmpty()
                        .sortedBy { validator -> validator.callable.qualifiedName },
                    migrations = associatedMigrations[root.type.qualifiedName].orEmpty()
                        .sortedWith(
                            compareBy<ConfigMigrationModel> { migration -> migration.fromSchema }
                                .thenBy { migration -> migration.callable.qualifiedName },
                        ),
                )
            }.sortedBy { root -> root.type.qualifiedName },
            orphanValidators = validations.values.filter { validator ->
                validator.rootType.qualifiedName !in rootNames
            },
            orphanMigrations = migrations.values.filter { migration ->
                migration.rootType.qualifiedName !in rootNames
            },
        )
        val problems = validator.validate(module)
        problems.forEach { problem -> logger.error(problem, nodeFor(problem)) }
        if (failed || problems.isNotEmpty() || roots.isEmpty()) {
            generated = true
            return
        }

        val (className, generated) = writer.file(module)
        codeGenerator.createNewFile(
            Dependencies(aggregating = true, *sourceFiles.toTypedArray()),
            ConfigCodeWriter.GENERATED_PACKAGE,
            className,
        ).bufferedWriter().use(generated::writeTo)
        this.generated = true
    }

    private fun nodeFor(problem: String): KSAnnotated? =
        nodes.entries.firstOrNull { (identity) -> problem.contains(identity.substringBefore('|')) }?.value
            ?: roots.keys.firstOrNull { root -> problem.contains(root) }?.let(nodes::get)

    private fun <T> process(
        declaration: KSAnnotated,
        identity: String?,
        deferred: MutableList<KSAnnotated>,
        read: (KSAnnotated) -> T,
        destination: MutableMap<String, T>,
    ) {
        val name = identity
        if (name == null) {
            logger.error("Ashlar configuration declarations must have a stable qualified name", declaration)
            failed = true
            return
        }
        if (name in destination) return
        if (!declaration.validate()) {
            deferred += declaration
            return
        }
        val model = runCatching { read(declaration) }.getOrElse { failure ->
            logger.error(failure.message ?: "Cannot read Ashlar configuration declaration", declaration)
            failed = true
            return
        }
        destination[name] = model
        nodes[name] = declaration
        (declaration as? com.google.devtools.ksp.symbol.KSDeclaration)?.containingFile?.let(sourceFiles::add)
    }

    private fun KSFunctionDeclaration.callableIdentity(): String = buildString {
        append(packageName.asString()).append('.').append(simpleName.asString())
        append('|').append(extensionReceiver?.resolve())
        parameters.forEach { parameter -> append('|').append(parameter.type.resolve()) }
    }

    private companion object {
        const val CONFIG = "pink.alex.ashlar.config.Config"
        const val CONFIG_VALIDATION = "pink.alex.ashlar.config.ConfigValidation"
        const val CONFIG_MIGRATION = "pink.alex.ashlar.config.ConfigMigration"
    }
}
