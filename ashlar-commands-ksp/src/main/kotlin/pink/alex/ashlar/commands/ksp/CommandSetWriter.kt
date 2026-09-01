package pink.alex.ashlar.commands.ksp

import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.INT
import com.squareup.kotlinpoet.ANY
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.LIST
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeSpec

internal class CommandSetWriter {
    fun file(model: CommandSetModel): FileSpec {
        val targetType = ClassName(model.packageName, model.typeName)
        val bindingName = "${model.typeName}GeneratedBinding"
        val bindingType = TypeSpec.classBuilder(bindingName)
            .addModifiers(KModifier.INTERNAL)
            .addAnnotation(CONTRIBUTES)
            .addAnnotation(INJECT)
            .addSuperinterface(COMMAND_SET_BINDING.parameterizedBy(targetType))
            .addProperty(
                PropertySpec.builder("targetType", KCLASS.parameterizedBy(targetType))
                    .addModifiers(KModifier.OVERRIDE)
                    .initializer("%T::class", targetType)
                    .build(),
            )
            .addProperty(
                PropertySpec.builder("definition", COMMAND_SET_DEFINITION)
                    .addModifiers(KModifier.OVERRIDE)
                    .initializer(definition(model))
                    .build(),
            )
            .apply { addOptionsFactories(model) }
            .apply { if (model.graphFunctions.isNotEmpty()) addFunction(configureGraph(model, targetType)) }
            .addFunction(invoke(model, targetType))
            .build()
        val commandsFunction = FunSpec.builder("commands")
            .addModifiers(KModifier.INTERNAL)
            .addParameter("target", targetType)
            .returns(PLUGIN_COMPONENT)
            .addStatement("return %M(target, %T())", COMMAND_SET, ClassName(model.packageName, bindingName))
            .build()
        val routesType = routesType(model)

        return FileSpec.builder(model.packageName, "${model.typeName}_Generated")
            .addType(bindingType)
            .addType(routesType)
            .addFunction(commandsFunction)
            .build()
    }

    private fun configureGraph(
        model: CommandSetModel,
        targetType: ClassName,
    ): FunSpec {
        val code = CodeBlock.builder().add("val typedTarget = target as %T\n", targetType)
        model.graphFunctions.forEach { function ->
            code.add("typedTarget.%N(\n", function.name).indent()
            function.parameters.forEach { parameter ->
                val value = if (parameter.type == COMMAND_GRAPH.canonicalName) {
                    CodeBlock.of("graph")
                } else {
                    CodeBlock.of("dependencies.get(%T::class)", parameter.className())
                }
                code.add("%N = %L,\n", parameter.name, value)
            }
            code.unindent().add(")\n")
        }
        return FunSpec.builder("configureGraph")
            .addModifiers(KModifier.OVERRIDE)
            .addParameter("target", ANY)
            .addParameter("dependencies", DEPENDENCY_RESOLVER)
            .addParameter("graph", COMMAND_GRAPH)
            .addCode(code.build())
            .build()
    }

    private fun routesType(model: CommandSetModel): TypeSpec {
        val name = "${model.typeName}Routes"
        return TypeSpec.classBuilder(name)
            .addModifiers(KModifier.INTERNAL)
            .addAnnotation(INJECT)
            .primaryConstructor(
                FunSpec.constructorBuilder()
                    .addParameter("encoder", COMMAND_ROUTE_ENCODER)
                    .build(),
            )
            .addProperty(
                PropertySpec.builder("encoder", COMMAND_ROUTE_ENCODER, KModifier.PRIVATE)
                    .initializer("encoder")
                    .build(),
            )
            .addFunctions(model.routes.mapIndexed { index, route -> routeFunction(model, index, route) })
            .build()
    }

    private fun routeFunction(
        model: CommandSetModel,
        index: Int,
        route: RouteModel,
    ): FunSpec {
        val function = FunSpec.builder(route.functionName)
            .returns(COMMAND_ROUTE)
        route.parameters.forEach { parameter ->
            val declared = parameter.declaredTypeName()
            val routed = if (parameter.sensitive) SENSITIVE_ROUTE_VALUE.parameterizedBy(declared) else declared
            val type = routed.copy(nullable = parameter.optional || parameter.nullable)
            function.addParameter(
                com.squareup.kotlinpoet.ParameterSpec.builder(parameter.name, type)
                    .apply { if (parameter.optional) defaultValue("null") }
                    .build(),
            )
        }
        val code = CodeBlock.builder()
            .add("return %M(\n", COMMAND_ROUTE_FUNCTION)
            .indent()
            .add("route = %S,\n", "${model.packageName}.${model.typeName}#$index")
            .add("segments = buildList {\n")
            .indent()
            .add("add(%M(%S))\n", ROUTE_LITERAL, model.rootName)
        route.segments.forEach { segment ->
            when (segment) {
                is SegmentModel.Literal -> code.add("add(%M(%S))\n", ROUTE_LITERAL, segment.names.first())
                is SegmentModel.Argument -> {
                    val parameter = route.parameters[segment.parameterIndex]
                    if (parameter.optional) {
                        code.beginControlFlow("if (%N != null)", parameter.name)
                    }
                    if (parameter.collection || parameter.vararg) {
                        code.beginControlFlow("for (argumentValue in %N)", parameter.name)
                        if (parameter.sensitive) {
                            code.add(
                                "add(encoder.sensitiveArgument(%T::class, argumentValue, %L))\n",
                                parameter.className(),
                                qualifier(parameter.qualifier),
                            )
                        } else {
                            code.add(
                                "addAll(encoder.argument(%T::class, argumentValue, %L))\n",
                                parameter.className(),
                                qualifier(parameter.qualifier),
                            )
                        }
                        code.endControlFlow()
                    } else if (parameter.sensitive) {
                        code.add(
                            "add(encoder.sensitiveArgument(%T::class, %N, %L))\n",
                            parameter.className(),
                            parameter.name,
                            qualifier(parameter.qualifier),
                        )
                    } else {
                        code.add(
                            "addAll(encoder.argument(%T::class, %N, %L))\n",
                            parameter.className(),
                            parameter.name,
                            qualifier(parameter.qualifier),
                        )
                    }
                    if (parameter.optional) code.endControlFlow()
                }
                is SegmentModel.ScannedArguments -> route.parameters.drop(segment.firstParameterIndex)
                    .forEach { parameter -> addScannedRouteParameter(code, parameter) }
            }
        }
        code.unindent().add("},\n").unindent().add(")\n")
        return function.addCode(code.build()).build()
    }

    private fun addScannedRouteParameter(code: CodeBlock.Builder, parameter: ParameterModel) {
        if (parameter.options != null) {
            parameter.options.members.forEach { member ->
                val value = CodeBlock.of("%N.%N", parameter.name, member.propertyName)
                addRouteOption(code, member.option, value, member.collection)
            }
            return
        }
        if (parameter.option != null) {
            val option = parameter.option
            addRouteOption(code, option, CodeBlock.of("%N", parameter.name), parameter.collection)
            return
        }
        if (parameter.sensitive) {
            code.add(
                "add(encoder.sensitiveArgument(%T::class, %N, %L))\n",
                parameter.className(),
                parameter.name,
                qualifier(parameter.qualifier),
            )
        } else {
            code.add(
                "addAll(encoder.argument(%T::class, %N, %L))\n",
                parameter.className(),
                parameter.name,
                qualifier(parameter.qualifier),
            )
        }
    }

    private fun addRouteOption(
        code: CodeBlock.Builder,
        option: OptionModel,
        value: CodeBlock,
        collection: Boolean,
    ) {
        when {
            collection -> {
                code.beginControlFlow("for (optionValue in %L)", value)
                code.add(
                    "addAll(encoder.option(%S, %T::class, optionValue, %L))\n",
                    option.name,
                    option.className(),
                    qualifier(option.qualifier),
                )
                code.endControlFlow()
            }
            option.presenceAware -> {
                code.beginControlFlow("when (val supplied = %L)", value)
                code.add("%T.Absent -> Unit\n", OPTION_VALUE)
                code.add(
                    "is %T.Present -> addAll(encoder.option(%S, %T::class, supplied.value, %L))\n",
                    OPTION_VALUE,
                    option.name,
                    option.className(),
                    qualifier(option.qualifier),
                )
                code.endControlFlow()
            }
            option.nullable -> {
                code.beginControlFlow("if (%L != null)", value)
                code.add(
                    "addAll(encoder.option(%S, %T::class, %L, %L))\n",
                    option.name,
                    option.className(),
                    value,
                    qualifier(option.qualifier),
                )
                code.endControlFlow()
            }
            else -> code.add(
                "addAll(encoder.option(%S, %T::class, %L, %L))\n",
                option.name,
                option.className(),
                value,
                qualifier(option.qualifier),
            )
        }
    }

    private fun qualifier(type: String?): CodeBlock = type?.let { qualifier ->
        CodeBlock.of("%T::class", ClassName.bestGuess(qualifier))
    } ?: CodeBlock.of("null")

    private fun definition(model: CommandSetModel): CodeBlock =
        CodeBlock.builder()
            .add("%T(\n", COMMAND_SET_DEFINITION)
            .add("name = %S,\n", model.rootName)
            .add("aliases = %L,\n", stringList(model.aliases))
            .add("optionalAliases = %L,\n", stringList(model.optionalAliases))
            .add("permission = %L,\n", model.permission?.let { CodeBlock.of("%S", it) } ?: CodeBlock.of("null"))
            .add("routes = %L,\n", routeList(model.routes))
            .add("helpName = %L,\n", model.helpName?.let { CodeBlock.of("%S", it) } ?: CodeBlock.of("null"))
            .add("fragment = %L,\n", model.fragment)
            .add(")")
            .build()

    private fun routeList(routes: List<RouteModel>): CodeBlock = list(routes) { route ->
        CodeBlock.builder()
            .add("%T(\n", COMMAND_ROUTE_DEFINITION)
            .indent()
            .add("name = %S,\n", route.name)
            .add("parameters = %L,\n", parameterList(route.parameters))
            .add("aliases = %L,\n", stringList(route.aliases))
            .add("permissions = %L,\n", stringList(route.permissions))
            .add("policies = %L,\n", policyList(route.policies))
            .add("cancelOnExecutorRetire = %L,\n", route.cancelOnExecutorRetire)
            .add("segments = %L,\n", segmentList(route.segments))
            .add("documentation = %L,\n", documentation(route.documentation))
            .unindent()
            .add(")")
            .build()
    }

    private fun parameterList(parameters: List<ParameterModel>): CodeBlock = list(parameters) { parameter ->
        CodeBlock.of(
            "%T(name = %S, optional = %L, type = %T::class, greedy = %L, repeated = %L, nullable = %L, option = %L, options = %L, centerIntegers = %L, minimumTicks = %L, registry = %L, sensitive = %L, observed = %L, qualifier = %L)",
            COMMAND_PARAMETER_DEFINITION,
            parameter.name,
            parameter.optional,
            parameter.className(),
            parameter.greedy,
            parameter.repeated,
            parameter.nullable,
            parameter.option?.let(::optionDefinition) ?: CodeBlock.of("null"),
            parameter.options?.let(::optionsDefinition) ?: CodeBlock.of("null"),
            parameter.centerIntegers,
            parameter.minimumTicks,
            parameter.registry?.let { CodeBlock.of("%S", it) } ?: CodeBlock.of("null"),
            parameter.sensitive,
            parameter.observed,
            parameter.qualifier?.let { qualifier ->
                CodeBlock.of("%T::class", ClassName.bestGuess(qualifier))
            } ?: CodeBlock.of("null"),
        )
    }

    private fun optionDefinition(option: OptionModel): CodeBlock = CodeBlock.of(
        "%T(name = %S, shortName = %L, type = %T::class, nullable = %L, repeated = %L, presenceAware = %L, qualifier = %L)",
        COMMAND_OPTION_DEFINITION,
        option.name,
        option.shortName?.let { CodeBlock.of("%S.single()", it.toString()) } ?: CodeBlock.of("null"),
        option.className(),
        option.nullable,
        option.repeated,
        option.presenceAware,
        option.qualifier?.let { qualifier ->
            CodeBlock.of("%T::class", ClassName.bestGuess(qualifier))
        } ?: CodeBlock.of("null"),
    )

    private fun optionsDefinition(options: OptionsModel): CodeBlock = CodeBlock.of(
        "%T(members = %L)",
        COMMAND_OPTIONS_DEFINITION,
        list(options.members) { member ->
            CodeBlock.of(
                "%T(propertyName = %S, option = %L)",
                COMMAND_OPTION_MEMBER_DEFINITION,
                member.propertyName,
                optionDefinition(member.option),
            )
        },
    )

    private fun policyList(policies: List<PolicyModel>): CodeBlock = list(policies) { policy ->
        when (policy) {
            is PolicyModel.Cooldown -> CodeBlock.of(
                "%T.Cooldown(seconds = %LL, mode = %T.%L)",
                COMMAND_POLICY_DEFINITION,
                policy.seconds,
                COOLDOWN_MODE,
                policy.mode,
            )
            is PolicyModel.RateLimit -> CodeBlock.of(
                "%T.RateLimit(permits = %L, seconds = %LL, mode = %T.%L)",
                COMMAND_POLICY_DEFINITION,
                policy.permits,
                policy.seconds,
                RATE_LIMIT_MODE,
                policy.mode,
            )
            PolicyModel.SingleFlight -> CodeBlock.of("%T.SingleFlight", COMMAND_POLICY_DEFINITION)
            is PolicyModel.Confirm -> CodeBlock.of(
                "%T.Confirm(seconds = %LL)",
                COMMAND_POLICY_DEFINITION,
                policy.seconds,
            )
            is PolicyModel.Custom -> CodeBlock.of(
                "%T.Custom(annotation = %L, interceptor = %T::class, phase = %T.%L, order = %L)",
                COMMAND_POLICY_DEFINITION,
                annotationValue(policy),
                ClassName.bestGuess(policy.interceptorType),
                COMMAND_POLICY_PHASE,
                policy.phase,
                policy.order,
            )
        }
    }

    private fun annotationValue(policy: PolicyModel.Custom): CodeBlock {
        val arguments = CodeBlock.builder()
        policy.arguments.forEachIndexed { index, argument ->
            if (index > 0) arguments.add(", ")
            arguments.add("%N = %L", argument.name, annotationArgument(argument.value))
        }
        return CodeBlock.of("%T(%L)", ClassName.bestGuess(policy.annotationType), arguments.build())
    }

    private fun annotationArgument(value: Any?): CodeBlock = when (value) {
        null -> CodeBlock.of("null")
        is String -> CodeBlock.of("%S", value)
        is Char -> CodeBlock.of("%L", "'${value.toString().replace("'", "\\'")}'")
        is Boolean, is Int, is Short, is Byte, is Double -> CodeBlock.of("%L", value)
        is Long -> CodeBlock.of("%LL", value)
        is Float -> CodeBlock.of("%LF", value)
        is KSType -> CodeBlock.of(
            "%T::class",
            ClassName.bestGuess(requireNotNull(value.declaration.qualifiedName?.asString())),
        )
        is KSClassDeclaration -> {
            check(value.classKind == ClassKind.ENUM_ENTRY) { "Unsupported annotation class value $value" }
            val parent = value.parentDeclaration as KSClassDeclaration
            CodeBlock.of(
                "%T.%L",
                ClassName.bestGuess(requireNotNull(parent.qualifiedName?.asString())),
                value.simpleName.asString(),
            )
        }
        is List<*> -> {
            val values = value.map(::annotationArgument)
            list(values) { it }
        }
        else -> error("Unsupported custom policy annotation value: ${value::class.qualifiedName}")
    }

    private fun segmentList(segments: List<SegmentModel>): CodeBlock = list(segments) { segment ->
        when (segment) {
            is SegmentModel.Literal -> CodeBlock.of(
                "%T.Literal(names = %L, permissions = %L)",
                COMMAND_SEGMENT_DEFINITION,
                stringList(segment.names),
                stringList(segment.permissions),
            )
            is SegmentModel.Argument -> CodeBlock.of(
                "%T.Argument(%L)",
                COMMAND_SEGMENT_DEFINITION,
                segment.parameterIndex,
            )
            is SegmentModel.ScannedArguments -> CodeBlock.of(
                "%T.ScannedArguments(%L)",
                COMMAND_SEGMENT_DEFINITION,
                segment.firstParameterIndex,
            )
        }
    }

    private fun documentation(documentation: DocumentationModel): CodeBlock = CodeBlock.of(
        "%T(summary = %S, parameters = %L, examples = %L)",
        COMMAND_DOCUMENTATION,
        documentation.summary,
        stringMap(documentation.parameters),
        stringList(documentation.examples),
    )

    private fun stringMap(values: Map<String, String>): CodeBlock {
        if (values.isEmpty()) return CodeBlock.of("emptyMap()")
        val result = CodeBlock.builder().add("mapOf(\n")
        values.forEach { (key, value) -> result.add("%S to %S,\n", key, value) }
        return result.add(")").build()
    }

    private fun stringList(values: List<String>): CodeBlock = list(values) { value -> CodeBlock.of("%S", value) }

    private fun <T> list(values: List<T>, render: (T) -> CodeBlock): CodeBlock {
        if (values.isEmpty()) return CodeBlock.of("emptyList()")
        val result = CodeBlock.builder().add("listOf(\n").indent()
        values.forEach { value -> result.add("%L,\n", render(value)) }
        return result.unindent().add(")").build()
    }

    private fun invoke(
        model: CommandSetModel,
        targetType: ClassName,
    ): FunSpec =
        FunSpec.builder("invokeTyped")
            .addModifiers(KModifier.OVERRIDE)
            .addModifiers(KModifier.SUSPEND)
            .addAnnotation(
                com.squareup.kotlinpoet.AnnotationSpec.builder(Suppress::class)
                    .addMember("%S", "UNCHECKED_CAST")
                    .build(),
            )
            .addParameter("target", targetType)
            .addParameter("route", INT)
            .addParameter("arguments", LIST.parameterizedBy(ANY.copy(nullable = true)))
            .addParameter("dependencies", DEPENDENCY_RESOLVER)
            .returns(ANY.copy(nullable = true))
            .addCode(invokeBody(model.routes))
            .build()

    private fun invokeBody(routes: List<RouteModel>): CodeBlock {
        val result = CodeBlock.builder().beginControlFlow("return when (route)")
        routes.forEachIndexed { index, route ->
            result.add("%L -> %L\n", index, routeInvocation(index, route))
        }
        result.add("else -> %M(route)\n", INVALID_ROUTE)
        return result.endControlFlow().build()
    }

    private fun routeInvocation(
        routeIndex: Int,
        route: RouteModel,
    ): CodeBlock {
        if (route.segments.any { segment -> segment is SegmentModel.ScannedArguments }) {
            val optional = route.parameters.indices.filter { index -> route.parameters[index].optional }
            if (optional.isEmpty()) return invokeHandler(route, route.parameters.size)
            val result = CodeBlock.builder()
                .beginControlFlow("if (arguments.size != %L)", route.parameters.size)
                .add("%M(%L, arguments.size)\n", INVALID_ARGUMENT_COUNT, routeIndex)
                .endControlFlow()
                .beginControlFlow("when")
            optional.forEachIndexed { position, index ->
                result.add(
                    "arguments[%L] === %T -> %L\n",
                    index,
                    MISSING_ARGUMENT,
                    invokeHandler(route, route.parameters.size, optional.drop(position).toSet()),
                )
            }
            result.add("else -> %L\n", invokeHandler(route, route.parameters.size))
            return result.endControlFlow().build()
        }
        val requiredCount = route.parameters.indexOfFirst(ParameterModel::optional)
            .let { firstOptional -> if (firstOptional == -1) route.parameters.size else firstOptional }
        if (requiredCount == route.parameters.size) {
            return invokeHandler(route, route.parameters.size)
        }

        val result = CodeBlock.builder().beginControlFlow("when (arguments.size)")
        for (count in requiredCount..route.parameters.size) {
            result.add("%L -> %L\n", count, invokeHandler(route, count))
        }
        result.add("else -> %M(%L, arguments.size)\n", INVALID_ARGUMENT_COUNT, routeIndex)
        return result.endControlFlow().build()
    }

    private fun invokeHandler(
        route: RouteModel,
        argumentCount: Int,
        omittedArguments: Set<Int> = emptySet(),
    ): CodeBlock {
        val result = CodeBlock.builder().add("run {\n").indent()
        var receiver = "target"
        route.containers.forEachIndexed { index, container ->
            val variable = "container$index"
            result.add("val %N = %N.%N(\n", variable, receiver, container.typeName).indent()
            container.parameters.forEach { parameter ->
                if (parameter.argumentIndex in omittedArguments) return@forEach
                result.add("%N = %L,\n", parameter.name, invocationValue(parameter, route, argumentCount))
            }
            result.unindent().add(")\n")
            receiver = variable
        }
        result.add("%N.%N(\n", receiver, route.functionName).indent()
        route.handlerParameters
            .filter { parameter ->
                parameter.argumentIndex !in omittedArguments &&
                    (parameter.argumentIndex == null || parameter.argumentIndex < argumentCount)
            }
            .forEach { parameter ->
                val model = parameter.argumentIndex?.let(route.parameters::get)
                if (model?.vararg == true) {
                    result.add("%N = *%L,\n", parameter.name, invocationValue(parameter, route, argumentCount))
                } else {
                    result.add("%N = %L,\n", parameter.name, invocationValue(parameter, route, argumentCount))
                }
            }
        return result.unindent().add(")\n").unindent().add("}").build()
    }

    private fun invocationValue(
        parameter: InvocationParameterModel,
        route: RouteModel,
        argumentCount: Int,
    ): CodeBlock = parameter.argumentIndex?.let { index ->
        check(index < argumentCount)
        val model = route.parameters[index]
        val type = if (model.vararg) {
            LIST.parameterizedBy(model.className())
        } else {
            model.declaredTypeName()
        }
        val cast = CodeBlock.of("arguments[%L] as %T", index, type)
        if (model.vararg) CodeBlock.of("%L.%L", cast, model.varargArrayConversion()) else cast
    } ?: CodeBlock.of("dependencies.get(%T::class)", parameter.className())

    private fun ParameterModel.className(): ClassName {
        val packageName = valueType.substringBeforeLast('.', "")
        val simpleName = valueType.substringAfterLast('.')
        return ClassName(packageName, simpleName)
    }

    private fun OptionModel.className(): ClassName = valueType.className()

    private fun String.className(): ClassName =
        ClassName(substringBeforeLast('.', ""), substringAfterLast('.'))

    private fun ParameterModel.declaredTypeName(): com.squareup.kotlinpoet.TypeName = when {
        collection || vararg -> LIST.parameterizedBy(className())
        option?.presenceAware == true -> OPTION_VALUE.parameterizedBy(className())
        typeArguments.isNotEmpty() -> type.className()
            .parameterizedBy(typeArguments.map { argument -> argument.className() })
            .copy(nullable = nullable)
        else -> type.className().copy(nullable = nullable)
    }

    private fun OptionMemberModel.declaredTypeName(): com.squareup.kotlinpoet.TypeName = when {
        collection -> LIST.parameterizedBy(option.className())
        option.presenceAware -> OPTION_VALUE.parameterizedBy(option.className())
        else -> declaredType.className().copy(nullable = option.nullable)
    }

    private fun ParameterModel.varargArrayConversion(): String = when (valueType) {
        "kotlin.Boolean" -> "toBooleanArray()"
        "kotlin.Byte" -> "toByteArray()"
        "kotlin.Char" -> "toCharArray()"
        "kotlin.Double" -> "toDoubleArray()"
        "kotlin.Float" -> "toFloatArray()"
        "kotlin.Int" -> "toIntArray()"
        "kotlin.Long" -> "toLongArray()"
        "kotlin.Short" -> "toShortArray()"
        else -> "toTypedArray()"
    }

    private fun TypeSpec.Builder.addOptionsFactories(model: CommandSetModel) {
        val factories = model.routes.flatMapIndexed { routeIndex, route ->
            route.parameters.mapIndexedNotNull { parameterIndex, parameter ->
                parameter.options?.let { options -> OptionsFactory(routeIndex, parameterIndex, options) }
            }
        }
        if (factories.isEmpty()) return
        factories.forEach { factory ->
            addProperty(
                PropertySpec.builder(factory.defaultsName, factory.options.type.className(), KModifier.PRIVATE)
                    .initializer("%T()", factory.options.type.className())
                    .build(),
            )
        }
        addFunction(
            FunSpec.builder("optionDefaults")
                .addModifiers(KModifier.OVERRIDE)
                .addParameter("route", INT)
                .addParameter("parameter", INT)
                .returns(LIST.parameterizedBy(ANY.copy(nullable = true)))
                .addCode(
                    CodeBlock.builder().beginControlFlow("return when")
                        .apply {
                            factories.forEach { factory ->
                                add(
                                    "route == %L && parameter == %L -> %L\n",
                                    factory.route,
                                    factory.parameter,
                                    list(factory.options.members) { member ->
                                        CodeBlock.of("%N.%N", factory.defaultsName, member.propertyName)
                                    },
                                )
                            }
                        }
                        .add("else -> %M(route, parameter)\n", INVALID_OPTIONS)
                        .endControlFlow()
                        .build(),
                )
                .build(),
        )
        addFunction(
            FunSpec.builder("constructOptions")
                .addModifiers(KModifier.OVERRIDE)
                .addAnnotation(
                    com.squareup.kotlinpoet.AnnotationSpec.builder(Suppress::class)
                        .addMember("%S", "UNCHECKED_CAST")
                        .build(),
                )
                .addParameter("route", INT)
                .addParameter("parameter", INT)
                .addParameter("values", LIST.parameterizedBy(ANY.copy(nullable = true)))
                .returns(ANY)
                .addCode(
                    CodeBlock.builder().beginControlFlow("return when")
                        .apply {
                            factories.forEach { factory ->
                                add("route == %L && parameter == %L -> %T(\n", factory.route, factory.parameter, factory.options.type.className())
                                    .indent()
                                factory.options.members.forEachIndexed { index, member ->
                                    add("%N = values[%L] as %T,\n", member.propertyName, index, member.declaredTypeName())
                                }
                                unindent().add(")\n")
                            }
                        }
                        .add("else -> %M(route, parameter)\n", INVALID_OPTIONS)
                        .endControlFlow()
                        .build(),
                )
                .build(),
        )
    }

    private data class OptionsFactory(
        val route: Int,
        val parameter: Int,
        val options: OptionsModel,
    ) {
        val defaultsName: String = "route${route}Parameter${parameter}Defaults"
    }

    private fun InvocationParameterModel.className(): ClassName {
        val packageName = type.substringBeforeLast('.', "")
        val simpleName = type.substringAfterLast('.')
        return ClassName(packageName, simpleName)
    }

    private companion object {
        val COMMAND_SET_BINDING = ClassName(
            "pink.alex.ashlar.commands.codegen",
            "CommandSetBinding",
        )
        val COMMAND_SET_DEFINITION = ClassName(
            "pink.alex.ashlar.commands.codegen",
            "CommandSetDefinition",
        )
        val COMMAND_ROUTE_DEFINITION = ClassName(
            "pink.alex.ashlar.commands.codegen",
            "CommandRouteDefinition",
        )
        val COMMAND_PARAMETER_DEFINITION = ClassName(
            "pink.alex.ashlar.commands.codegen",
            "CommandParameterDefinition",
        )
        val COMMAND_OPTION_DEFINITION = ClassName(
            "pink.alex.ashlar.commands.codegen",
            "CommandOptionDefinition",
        )
        val COMMAND_OPTIONS_DEFINITION = ClassName(
            "pink.alex.ashlar.commands.codegen",
            "CommandOptionsDefinition",
        )
        val COMMAND_OPTION_MEMBER_DEFINITION = ClassName(
            "pink.alex.ashlar.commands.codegen",
            "CommandOptionMemberDefinition",
        )
        val COMMAND_SEGMENT_DEFINITION = ClassName(
            "pink.alex.ashlar.commands.codegen",
            "CommandSegmentDefinition",
        )
        val COMMAND_DOCUMENTATION = ClassName(
            "pink.alex.ashlar.commands.codegen",
            "CommandDocumentation",
        )
        val COMMAND_POLICY_DEFINITION = ClassName(
            "pink.alex.ashlar.commands.policy",
            "CommandPolicyDefinition",
        )
        val COOLDOWN_MODE = ClassName(
            "pink.alex.ashlar.commands.policy",
            "CooldownMode",
        )
        val RATE_LIMIT_MODE = ClassName(
            "pink.alex.ashlar.commands.policy",
            "RateLimitMode",
        )
        val COMMAND_POLICY_PHASE = ClassName(
            "pink.alex.ashlar.commands.policy",
            "CommandPolicyPhase",
        )
        val KCLASS = ClassName("kotlin.reflect", "KClass")
        val OPTION_VALUE = ClassName("pink.alex.ashlar.commands", "OptionValue")
        val MISSING_ARGUMENT = ClassName(
            "pink.alex.ashlar.commands.codegen",
            "MissingCommandArgument",
        )
        val DEPENDENCY_RESOLVER = ClassName("pink.alex.ashlar.di", "DependencyResolver")
        val CONTRIBUTES = ClassName("pink.alex.ashlar.di", "Contributes")
        val INJECT = ClassName("pink.alex.ashlar.di", "Inject")
        val COMMAND_ROUTE = ClassName("pink.alex.ashlar.commands.route", "CommandRoute")
        val COMMAND_ROUTE_ENCODER = ClassName("pink.alex.ashlar.commands.route", "CommandRouteEncoder")
        val COMMAND_GRAPH = ClassName("pink.alex.ashlar.commands.graph", "CommandGraph")
        val SENSITIVE_ROUTE_VALUE = ClassName(
            "pink.alex.ashlar.commands.route",
            "SensitiveRouteValue",
        )
        val COMMAND_ROUTE_FUNCTION = com.squareup.kotlinpoet.MemberName(
            "pink.alex.ashlar.commands.route",
            "commandRoute",
        )
        val ROUTE_LITERAL = com.squareup.kotlinpoet.MemberName(
            "pink.alex.ashlar.commands.route",
            "routeLiteral",
        )
        val PLUGIN_COMPONENT = ClassName("pink.alex.ashlar", "PluginComponent")
        val COMMAND_SET = com.squareup.kotlinpoet.MemberName(
            "pink.alex.ashlar.commands.codegen",
            "commandSet",
        )
        val INVALID_ROUTE = com.squareup.kotlinpoet.MemberName(
            "pink.alex.ashlar.commands.codegen",
            "invalidCommandRoute",
        )
        val INVALID_ARGUMENT_COUNT = com.squareup.kotlinpoet.MemberName(
            "pink.alex.ashlar.commands.codegen",
            "invalidCommandArgumentCount",
        )
        val INVALID_OPTIONS = com.squareup.kotlinpoet.MemberName(
            "pink.alex.ashlar.commands.codegen",
            "invalidCommandOptions",
        )
    }
}
