package dev.placeholder.framework.events.ksp

import com.squareup.kotlinpoet.ANY
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.INT
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeSpec

internal class EventSetWriter {
    fun file(model: EventSetModel): FileSpec {
        val target = ClassName(model.packageName, model.typeNames)
        val bindingName = "${model.typeName}GeneratedEventBinding"
        val binding = TypeSpec.classBuilder(bindingName)
            .addModifiers(KModifier.INTERNAL)
            .addAnnotation(CONTRIBUTES)
            .addAnnotation(INJECT)
            .addSuperinterface(EVENT_SET_CONTRIBUTION)
            .addProperty(
                PropertySpec.builder("targetType", KCLASS.parameterizedBy(STAR))
                    .addModifiers(KModifier.OVERRIDE)
                    .initializer("%T::class", target)
                    .build(),
            )
            .addProperty(
                PropertySpec.builder("definition", EVENT_SET_DEFINITION)
                    .addModifiers(KModifier.OVERRIDE)
                    .initializer(definition(model.handlers))
                    .build(),
            )
            .addFunction(invoke(model, target))
            .build()
        return FileSpec.builder(model.packageName, "${model.typeName}_EventsGenerated")
            .addType(binding)
            .build()
    }

    private fun definition(handlers: List<ServerHandlerModel>): CodeBlock =
        CodeBlock.builder()
            .add("%T(handlers = %L)", EVENT_SET_DEFINITION, list(handlers) { handler ->
                CodeBlock.of(
                    "%T(name = %S, eventType = %T::class, priority = %T.%L, ignoreCancelled = %L)",
                    SERVER_HANDLER_DEFINITION,
                    handler.functionName,
                    handler.eventClassName(),
                    EVENT_PRIORITY,
                    handler.priority,
                    handler.ignoreCancelled,
                )
            })
            .build()

    private fun invoke(
        model: EventSetModel,
        target: ClassName,
    ): FunSpec {
        val code = CodeBlock.builder()
            .add("val typedTarget = target as %T\n", target)
            .beginControlFlow("when (handler)")
        model.handlers.forEachIndexed { index, handler ->
            code.add(
                "%L -> with(typedTarget) { (event as %T).%N() }\n",
                index,
                handler.eventClassName(),
                handler.functionName,
            )
        }
        code.add("else -> %M(handler)\n", INVALID_EVENT_HANDLER)
            .endControlFlow()
        return FunSpec.builder("invoke")
            .addModifiers(KModifier.OVERRIDE)
            .addParameter("target", ANY)
            .addParameter("handler", INT)
            .addParameter("event", EVENT)
            .addCode(code.build())
            .build()
    }

    private fun ServerHandlerModel.eventClassName(): ClassName =
        ClassName(eventPackageName, eventTypeNames)

    private fun <T> list(
        values: List<T>,
        render: (T) -> CodeBlock,
    ): CodeBlock {
        if (values.isEmpty()) return CodeBlock.of("emptyList()")
        val code = CodeBlock.builder().add("listOf(\n").indent()
        values.forEach { value -> code.add("%L,\n", render(value)) }
        return code.unindent().add(")").build()
    }

    private companion object {
        val CONTRIBUTES = ClassName("dev.placeholder.framework.di", "Contributes")
        val INJECT = ClassName("dev.placeholder.framework.di", "Inject")
        val EVENT_SET_CONTRIBUTION =
            ClassName("dev.placeholder.framework.events.codegen", "EventSetContribution")
        val EVENT_SET_DEFINITION =
            ClassName("dev.placeholder.framework.events.codegen", "EventSetDefinition")
        val SERVER_HANDLER_DEFINITION =
            ClassName("dev.placeholder.framework.events.codegen", "ServerEventHandlerDefinition")
        val EVENT_PRIORITY = ClassName("org.bukkit.event", "EventPriority")
        val EVENT = ClassName("org.bukkit.event", "Event")
        val KCLASS = ClassName("kotlin.reflect", "KClass")
        val STAR = com.squareup.kotlinpoet.STAR
        val INVALID_EVENT_HANDLER = com.squareup.kotlinpoet.MemberName(
            "dev.placeholder.framework.events.codegen",
            "invalidEventHandler",
        )
    }
}
