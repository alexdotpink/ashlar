package dev.placeholder.framework.events.ksp

import com.google.devtools.ksp.getAllSuperTypes
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.Modifier

internal class EventSetReader {
    fun read(declaration: KSClassDeclaration): EventSetModel = EventSetModel(
        packageName = declaration.packageName.asString(),
        typeNames = declaration.typeNames(),
        abstract = Modifier.ABSTRACT in declaration.modifiers,
        open = Modifier.OPEN in declaration.modifiers,
        handlers = declaration.declarations
            .filterIsInstance<KSFunctionDeclaration>()
            .mapNotNull(::serverHandler)
            .toList(),
    )

    private fun serverHandler(function: KSFunctionDeclaration): ServerHandlerModel? {
        val annotation = function.annotationOrNull(ON_ANNOTATION) ?: return null
        val receiver = function.extensionReceiver?.resolve()
        val receiverDeclaration = receiver?.declaration as? KSClassDeclaration
        val eventPackage = receiverDeclaration?.packageName?.asString().orEmpty()
        val eventTypes = receiverDeclaration?.typeNames().orEmpty()
        val eventQualifiedName = receiverDeclaration?.qualifiedName?.asString().orEmpty()
        val supertypes = receiverDeclaration?.getAllSuperTypes()
            ?.mapNotNull { type -> type.declaration.qualifiedName?.asString() }
            ?.toSet()
            .orEmpty()
        val values = annotation.values()
        return ServerHandlerModel(
            functionName = function.simpleName.asString(),
            eventPackageName = eventPackage,
            eventTypeNames = eventTypes,
            eventQualifiedName = eventQualifiedName,
            event = eventQualifiedName == EVENT_TYPE || EVENT_TYPE in supertypes,
            priority = values.getValue("priority").enumName(),
            ignoreCancelled = values.getValue("ignoreCancelled") as Boolean,
            cancellable = CANCELLABLE_TYPE in supertypes || eventQualifiedName == CANCELLABLE_TYPE,
            suspending = Modifier.SUSPEND in function.modifiers,
            returnType = function.returnType?.resolve()?.declaration?.qualifiedName?.asString().orEmpty(),
            parameterCount = function.parameters.size,
            private = Modifier.PRIVATE in function.modifiers,
            protected = Modifier.PROTECTED in function.modifiers,
            generic = function.typeParameters.isNotEmpty(),
        )
    }

    private fun KSDeclaration.typeNames(): List<String> = buildList {
        var current: KSDeclaration? = this@typeNames
        while (current is KSClassDeclaration) {
            add(current.simpleName.asString())
            current = current.parentDeclaration
        }
    }.asReversed()

    private fun KSAnnotated.annotationOrNull(name: String): KSAnnotation? =
        annotations.singleOrNull { annotation ->
            annotation.annotationType.resolve().declaration.qualifiedName?.asString() == name
        }

    private fun KSAnnotation.values(): Map<String, Any?> =
        arguments.associate { argument -> requireNotNull(argument.name?.asString()) to argument.value }

    private fun Any?.enumName(): String = when (this) {
        is KSType -> declaration.simpleName.asString()
        is KSClassDeclaration -> simpleName.asString()
        else -> toString().substringAfterLast('.')
    }

    private companion object {
        const val ON_ANNOTATION = "dev.placeholder.framework.events.On"
        const val CANCELLABLE_TYPE = "org.bukkit.event.Cancellable"
        const val EVENT_TYPE = "org.bukkit.event.Event"
    }
}
