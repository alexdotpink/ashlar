package dev.placeholder.framework.di.ksp

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
import com.google.devtools.ksp.validate

public class DiProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor =
        DiProcessor(environment.codeGenerator, environment.logger)
}

internal class DiProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
    private val reader: DiModelReader = DiModelReader(),
    private val validator: DiModelValidator = DiModelValidator(),
    private val writer: DiCodeWriter = DiCodeWriter(),
) : SymbolProcessor {
    private val processedFactories: MutableSet<String> = mutableSetOf()
    private val roots: MutableMap<String, RootComponentModel> = linkedMapOf()
    private val contributions: MutableMap<String, ContributionModel> = linkedMapOf()
    private val sourceFiles: MutableSet<KSFile> = linkedSetOf()

    override fun process(resolver: Resolver): List<KSAnnotated> {
        val deferred = mutableListOf<KSAnnotated>()
        resolver.getSymbolsWithAnnotation(INJECT).forEach { symbol ->
            val declaration = reader.injectableClass(symbol) ?: return@forEach
            processFactory(declaration, deferred)
        }
        resolver.getSymbolsWithAnnotation(FRAMEWORK_COMPONENT)
            .filterIsInstance<KSClassDeclaration>()
            .forEach { declaration ->
                if (!declaration.validate()) {
                    deferred += declaration
                    return@forEach
                }
                processFactory(declaration, deferred)
                val model = reader.rootComponent(declaration)
                report(validator.validate(model), declaration)
                roots[model.qualifiedName] = model
                declaration.containingFile?.let(sourceFiles::add)
            }
        resolver.getSymbolsWithAnnotation(CONTRIBUTES)
            .filterIsInstance<KSClassDeclaration>()
            .forEach { declaration ->
                if (!declaration.validate()) {
                    deferred += declaration
                    return@forEach
                }
                processFactory(declaration, deferred)
                val model = reader.contribution(declaration)
                contributions[model.qualifiedName] = model
                declaration.containingFile?.let(sourceFiles::add)
            }
        resolver.getSymbolsWithAnnotation(COMMANDS)
            .filterIsInstance<KSClassDeclaration>()
            .forEach { declaration -> processFactory(declaration, deferred) }
        resolver.getSymbolsWithAnnotation(COMMAND_FRAGMENT)
            .filterIsInstance<KSClassDeclaration>()
            .forEach { declaration -> processFactory(declaration, deferred) }
        resolver.getSymbolsWithAnnotation(EVENTS)
            .filterIsInstance<KSClassDeclaration>()
            .forEach { declaration -> processFactory(declaration, deferred) }
        return deferred
    }

    override fun finish() {
        if (roots.isEmpty() && contributions.isEmpty()) return
        val orderedRoots = roots.values.sortedBy(RootComponentModel::qualifiedName)
        val orderedContributions = contributions.values.sortedBy(ContributionModel::qualifiedName)
        val (className, file) = writer.contributionModule(orderedRoots, orderedContributions)
        val dependencies = Dependencies(aggregating = true, *sourceFiles.toTypedArray())
        codeGenerator.createNewFile(
            dependencies,
            DiCodeWriter.GENERATED_PACKAGE,
            file.name,
        ).bufferedWriter().use(file::writeTo)
        codeGenerator.createNewFileByPath(
            dependencies,
            "META-INF/services/${DiCodeWriter.CONTRIBUTION_MODULE}",
            "",
        ).bufferedWriter().use { service ->
            service.append(DiCodeWriter.GENERATED_PACKAGE).append('.').appendLine(className)
        }
    }

    private fun processFactory(
        declaration: KSClassDeclaration,
        deferred: MutableList<KSAnnotated>,
    ) {
        val qualifiedName = declaration.qualifiedName?.asString() ?: return
        if (qualifiedName in processedFactories) return
        if (!declaration.validate()) {
            deferred += declaration
            return
        }
        val model = runCatching { reader.factory(declaration) }
            .getOrElse { failure ->
                logger.error(failure.message ?: "Cannot read injectable constructor", declaration)
                processedFactories += qualifiedName
                return
            }
        val problems = validator.validate(model)
        report(problems, declaration)
        if (problems.isEmpty()) {
            val sourceFile = checkNotNull(declaration.containingFile)
            val file = writer.factory(model)
            codeGenerator.createNewFile(
                Dependencies(aggregating = false, sourceFile),
                model.packageName,
                file.name,
            ).bufferedWriter().use(file::writeTo)
            sourceFiles += sourceFile
        }
        processedFactories += qualifiedName
    }

    private fun report(
        problems: List<String>,
        declaration: KSClassDeclaration,
    ) {
        problems.forEach { problem -> logger.error(problem, declaration) }
    }

    private companion object {
        const val INJECT = "dev.placeholder.framework.di.Inject"
        const val FRAMEWORK_COMPONENT = "dev.placeholder.framework.FrameworkComponent"
        const val CONTRIBUTES = "dev.placeholder.framework.di.Contributes"
        const val COMMANDS = "dev.placeholder.framework.commands.Commands"
        const val COMMAND_FRAGMENT = "dev.placeholder.framework.commands.CommandFragment"
        const val EVENTS = "dev.placeholder.framework.events.Events"
    }
}
