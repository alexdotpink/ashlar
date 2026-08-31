package dev.placeholder.framework.events.ksp

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.validate

public class EventsProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor =
        EventsProcessor(environment.codeGenerator, environment.logger)
}

internal class EventsProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
    private val reader: EventSetReader = EventSetReader(),
    private val validator: EventSetValidator = EventSetValidator(),
    private val writer: EventSetWriter = EventSetWriter(),
) : SymbolProcessor {
    private val processed: MutableSet<String> = mutableSetOf()

    override fun process(resolver: Resolver): List<KSAnnotated> {
        val deferred = mutableListOf<KSAnnotated>()
        resolver.getSymbolsWithAnnotation(EVENTS_ANNOTATION)
            .filterIsInstance<KSClassDeclaration>()
            .forEach { declaration ->
                val qualifiedName = declaration.qualifiedName?.asString() ?: return@forEach
                if (qualifiedName in processed) return@forEach
                if (!declaration.validate()) {
                    deferred += declaration
                    return@forEach
                }
                val model = reader.read(declaration)
                val problems = validator.validate(model)
                problems.forEach { problem -> logger.error(problem, declaration) }
                if (problems.isEmpty() && !model.abstract) {
                    val sourceFile = checkNotNull(declaration.containingFile)
                    val generated = writer.file(model)
                    codeGenerator.createNewFile(
                        Dependencies(aggregating = false, sourceFile),
                        model.packageName,
                        generated.name,
                    ).bufferedWriter().use(generated::writeTo)
                }
                processed += qualifiedName
            }
        return deferred
    }

    private companion object {
        const val EVENTS_ANNOTATION = "dev.placeholder.framework.events.Events"
    }
}
