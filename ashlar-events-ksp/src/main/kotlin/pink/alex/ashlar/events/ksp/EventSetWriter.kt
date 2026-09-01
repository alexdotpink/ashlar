package pink.alex.ashlar.events.ksp

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
                    .initializer(definition(model))
                    .build(),
            )
            .addFunction(invoke(model, target))
            .addFunction(observe(model, target))
            .addFunction(invokeApplication(model, target))
            .apply {
                if (model.lifecycleFunctions.isNotEmpty()) {
                    addFunction(configureLifecycle(model, target))
                }
            }
            .build()
        return FileSpec.builder(model.packageName, "${model.typeName}_EventsGenerated")
            .addType(binding)
            .build()
    }

    private fun definition(model: EventSetModel): CodeBlock =
        CodeBlock.builder()
            .add("%T(\n", EVENT_SET_DEFINITION)
            .indent()
            .add("handlers = %L,\n", list(model.handlers) { handler ->
                CodeBlock.of(
                    "%T(name = %S, eventType = %T::class, priority = %T.%L, ignoreCancelled = %L, kind = %T.%L)",
                    SERVER_HANDLER_DEFINITION,
                    handler.functionName,
                    handler.eventClassName(),
                    EVENT_PRIORITY,
                    handler.priority,
                    handler.ignoreCancelled,
                    SERVER_HANDLER_KIND,
                    if (handler.observer) "OBSERVER" else "SYNCHRONOUS",
                )
            })
            .add("applicationHandlers = %L,\n", list(model.applicationHandlers) { handler ->
                CodeBlock.of(
                    "%T(name = %S, eventType = %T::class)",
                    APPLICATION_HANDLER_DEFINITION,
                    handler.functionName,
                    handler.eventClassName(),
                )
            })
            .unindent()
            .add(")")
            .build()

    private fun invoke(
        model: EventSetModel,
        target: ClassName,
    ): FunSpec {
        val code = CodeBlock.builder()
            .add("val typedTarget = target as %T\n", target)
            .beginControlFlow("when (handler)")
        model.handlers.forEachIndexed { index, handler ->
            if (handler.observer) return@forEachIndexed
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

    private fun observe(
        model: EventSetModel,
        target: ClassName,
    ): FunSpec {
        val code = CodeBlock.builder()
            .add("val typedTarget = target as %T\n", target)
            .beginControlFlow("when (handler)")
        model.handlers.forEachIndexed { index, handler ->
            if (!handler.observer) return@forEachIndexed
            code.add(
                "%L -> with(typedTarget) { (event as %T).%N() }\n",
                index,
                handler.eventClassName(),
                handler.functionName,
            )
        }
        code.add("else -> %M(handler)\n", INVALID_EVENT_HANDLER)
            .endControlFlow()
        return FunSpec.builder("observe")
            .addModifiers(KModifier.OVERRIDE, KModifier.SUSPEND)
            .addParameter("target", ANY)
            .addParameter("handler", INT)
            .addParameter("event", EVENT)
            .addCode(code.build())
            .build()
    }

    private fun invokeApplication(
        model: EventSetModel,
        target: ClassName,
    ): FunSpec {
        val code = CodeBlock.builder()
            .add("val typedTarget = target as %T\n", target)
            .beginControlFlow("when (handler)")
        model.applicationHandlers.forEachIndexed { index, handler ->
            code.add(
                "%L -> with(typedTarget) { (event as %T).%N() }\n",
                index,
                handler.eventClassName(),
                handler.functionName,
            )
        }
        code.add("else -> %M(handler)\n", INVALID_EVENT_HANDLER)
            .endControlFlow()
        return FunSpec.builder("invokeApplication")
            .addModifiers(KModifier.OVERRIDE, KModifier.SUSPEND)
            .addParameter("target", ANY)
            .addParameter("handler", INT)
            .addParameter("event", APPLICATION_EVENT)
            .addCode(code.build())
            .build()
    }

    private fun configureLifecycle(
        model: EventSetModel,
        target: ClassName,
    ): FunSpec {
        val code = CodeBlock.builder().add("val typedTarget = target as %T\n", target)
        model.lifecycleFunctions.forEach { function ->
            code.add("with(typedTarget) { registry.%N() }\n", function.functionName)
        }
        return FunSpec.builder("configureLifecycle")
            .addModifiers(KModifier.OVERRIDE)
            .addParameter("target", ANY)
            .addParameter("registry", LIFECYCLE_REGISTRY)
            .addCode(code.build())
            .build()
    }

    private fun ServerHandlerModel.eventClassName(): ClassName =
        ClassName(eventPackageName, eventTypeNames)

    private fun ApplicationHandlerModel.eventClassName(): ClassName =
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
        val CONTRIBUTES = ClassName("pink.alex.ashlar.di", "Contributes")
        val INJECT = ClassName("pink.alex.ashlar.di", "Inject")
        val EVENT_SET_CONTRIBUTION =
            ClassName("pink.alex.ashlar.events.codegen", "EventSetContribution")
        val EVENT_SET_DEFINITION =
            ClassName("pink.alex.ashlar.events.codegen", "EventSetDefinition")
        val SERVER_HANDLER_DEFINITION =
            ClassName("pink.alex.ashlar.events.codegen", "ServerEventHandlerDefinition")
        val SERVER_HANDLER_KIND =
            ClassName("pink.alex.ashlar.events.codegen", "ServerEventHandlerKind")
        val APPLICATION_HANDLER_DEFINITION =
            ClassName("pink.alex.ashlar.events.codegen", "ApplicationEventHandlerDefinition")
        val APPLICATION_EVENT = ClassName("pink.alex.ashlar.events", "ApplicationEvent")
        val LIFECYCLE_REGISTRY = ClassName("pink.alex.ashlar.events", "LifecycleEventRegistry")
        val EVENT_PRIORITY = ClassName("org.bukkit.event", "EventPriority")
        val EVENT = ClassName("org.bukkit.event", "Event")
        val KCLASS = ClassName("kotlin.reflect", "KClass")
        val STAR = com.squareup.kotlinpoet.STAR
        val INVALID_EVENT_HANDLER = com.squareup.kotlinpoet.MemberName(
            "pink.alex.ashlar.events.codegen",
            "invalidEventHandler",
        )
    }
}
