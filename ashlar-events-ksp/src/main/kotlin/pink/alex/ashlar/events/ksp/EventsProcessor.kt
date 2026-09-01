package pink.alex.ashlar.events.ksp

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.getAllSuperTypes
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
        resolver.getAllFiles()
            .flatMap { file -> classes(file.declarations) }
            .filter { declaration -> declaration.belongsToEventSetFamily() }
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
                        // A concrete event set may inherit handlers from any source file in the
                        // compilation, so changes to a base class must invalidate its descendants.
                        Dependencies(aggregating = true, sourceFile),
                        model.packageName,
                        generated.name,
                    ).bufferedWriter().use(generated::writeTo)
                }
                processed += qualifiedName
            }
        return deferred
    }

    private fun classes(declarations: Sequence<KSDeclaration>): Sequence<KSClassDeclaration> = sequence {
        declarations.forEach { declaration ->
            if (declaration !is KSClassDeclaration) return@forEach
            yield(declaration)
            yieldAll(classes(declaration.declarations))
        }
    }

    private fun KSClassDeclaration.belongsToEventSetFamily(): Boolean {
        val hierarchy = sequenceOf(this) + getAllSuperTypes().mapNotNull { type ->
            type.declaration as? KSClassDeclaration
        }
        val declarations = hierarchy.toList()
        if (declarations.any { declaration -> declaration.hasAnnotation(DISABLE_EVENTS_ANNOTATION) }) return false
        return declarations.any { declaration -> declaration.hasAnnotation(EVENTS_ANNOTATION) }
    }

    private fun KSClassDeclaration.hasAnnotation(name: String): Boolean =
        annotations.any { annotation ->
            annotation.annotationType.resolve().declaration.qualifiedName?.asString() == name
        }

    private companion object {
        const val EVENTS_ANNOTATION = "pink.alex.ashlar.events.Events"
        const val DISABLE_EVENTS_ANNOTATION = "pink.alex.ashlar.events.DisableEvents"
    }
}
