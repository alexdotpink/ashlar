package pink.alex.ashlar.commands.ksp

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.google.devtools.ksp.symbol.KSClassDeclaration

public class CommandsProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor =
        CommandsProcessor(
            environment.codeGenerator,
            environment.logger,
            environment.options["ashlar.commands.strictDocumentation"].toBoolean(),
        )
}

internal class CommandsProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
    private val strictDocumentation: Boolean = false,
    private val reader: CommandSetReader = CommandSetReader(),
    private val validator: CommandSetValidator = CommandSetValidator(),
    private val writer: CommandSetWriter = CommandSetWriter(),
) : SymbolProcessor {
    private val processed: MutableSet<String> = mutableSetOf()

    override fun process(resolver: Resolver): List<KSClassDeclaration> {
        val deferred = mutableListOf<KSClassDeclaration>()
        resolver.getSymbolsWithAnnotation(COMMANDS_ANNOTATION)
            .filterIsInstance<KSClassDeclaration>()
            .forEach { declaration ->
                processDeclaration(declaration, reader.read(declaration, resolver))
            }
        resolver.getSymbolsWithAnnotation(COMMAND_FRAGMENT_ANNOTATION)
            .filterIsInstance<KSClassDeclaration>()
            .forEach { declaration ->
                processDeclaration(declaration, reader.readFragment(declaration, resolver))
            }
        return deferred
    }

    private fun processDeclaration(
        declaration: KSClassDeclaration,
        model: CommandSetModel,
    ) {
        val qualifiedName = declaration.qualifiedName?.asString() ?: return
        if (qualifiedName in processed) return
        val problems = validator.validate(model)
        if (problems.isNotEmpty()) {
            problems.forEach { problem -> logger.error(problem, declaration) }
            processed += qualifiedName
            return
        }

        model.routes.filter { route -> route.documentation.summary.isBlank() }
            .forEach { route ->
                val message = "Command function '${route.functionName}' has no KDoc summary"
                if (strictDocumentation) logger.error(message, declaration)
                else logger.warn(message, declaration)
            }

        val sourceFile = checkNotNull(declaration.containingFile)
        val generatedFile = writer.file(model)
        codeGenerator.createNewFile(
            dependencies = Dependencies(aggregating = false, sourceFile),
            packageName = model.packageName,
            fileName = generatedFile.name,
        ).bufferedWriter().use(generatedFile::writeTo)
        processed += qualifiedName
    }

    private companion object {
        const val COMMANDS_ANNOTATION = "pink.alex.ashlar.commands.Commands"
        const val COMMAND_FRAGMENT_ANNOTATION = "pink.alex.ashlar.commands.CommandFragment"
    }
}
