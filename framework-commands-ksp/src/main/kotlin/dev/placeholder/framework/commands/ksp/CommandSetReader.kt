package dev.placeholder.framework.commands.ksp

import com.google.devtools.ksp.symbol.FunctionKind
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSClassifierReference
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSTypeAlias
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSValueParameter
import com.google.devtools.ksp.symbol.Modifier
import com.google.devtools.ksp.symbol.Nullability
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.KspExperimental

internal class CommandSetReader {
    private var aliasAnnotations: Map<String, Set<String>> = emptyMap()

    fun read(
        declaration: KSClassDeclaration,
        resolver: Resolver,
    ): CommandSetModel {
        aliasAnnotations = readAliasAnnotations(resolver)
        val commandSet = declaration.annotation(COMMANDS_ANNOTATION)
        return read(declaration, commandSet.values(), fragment = false)
    }

    fun readFragment(
        declaration: KSClassDeclaration,
        resolver: Resolver,
    ): CommandSetModel {
        aliasAnnotations = readAliasAnnotations(resolver)
        val fragment = declaration.annotation(COMMAND_FRAGMENT_ANNOTATION).values()
        val rootType = fragment.getValue("root") as KSType
        val root = rootType.declaration as KSClassDeclaration
        val values = root.annotation(COMMANDS_ANNOTATION).values().toMutableMap()
        if ((values.getValue("name") as String).isBlank()) {
            values["name"] = root.simpleName.asString().removeSuffix("Commands").toKebabCase()
        }
        return read(declaration, values, fragment = true)
    }

    private fun read(
        declaration: KSClassDeclaration,
        values: Map<String, Any?>,
        fragment: Boolean,
    ): CommandSetModel {
        return CommandSetModel(
            packageName = declaration.packageName.asString(),
            typeName = declaration.simpleName.asString(),
            rootName = (values.getValue("name") as String).ifBlank {
                declaration.simpleName.asString().removeSuffix("Commands").toKebabCase()
            },
            aliases = values.strings("aliases"),
            optionalAliases = values.strings("optionalAliases"),
            permission = values.string("permission"),
            helpName = (values["helpName"] as String).ifBlank { null },
            schemaVersion = values["schemaVersion"] as Int,
            fragment = fragment,
            graphFunctions = declaration.declarations
                .filterIsInstance<KSFunctionDeclaration>()
                .filter { function -> function.annotationOrNull(CONFIGURE_GRAPH_ANNOTATION) != null }
                .map { function ->
                    require(Modifier.SUSPEND !in function.modifiers) {
                        "@ConfigureCommandGraph function '${function.simpleName.asString()}' cannot suspend"
                    }
                    GraphFunctionModel(
                        function.simpleName.asString(),
                        function.parameters.map { parameter ->
                            InvocationParameterModel(parameter.name(), parameter.typeName(), null)
                        },
                    )
                }
                .toList(),
            routes = readRoutes(
                owner = declaration,
                containers = emptyList(),
                segments = emptyList(),
                parameters = emptyList(),
                inheritedPermissions = emptyList(),
                inheritedPolicies = readPolicies(declaration),
                schemaVersion = values["schemaVersion"] as Int,
            ),
        )
    }

    private fun readRoutes(
        owner: KSClassDeclaration,
        containers: List<ContainerModel>,
        segments: List<SegmentModel>,
        parameters: List<ParameterModel>,
        inheritedPermissions: List<String>,
        inheritedPolicies: List<PolicyModel>,
        schemaVersion: Int,
    ): List<RouteModel> = buildList {
        owner.declarations
            .filterIsInstance<KSFunctionDeclaration>()
            .filter(::isHandler)
            .forEach { function ->
                add(
                    readRoute(
                        function,
                        containers,
                        segments,
                        parameters,
                        inheritedPermissions,
                        inheritedPolicies,
                        schemaVersion,
                    ),
                )
            }
        owner.declarations
            .filterIsInstance<KSClassDeclaration>()
            .forEach { nested ->
                val group = nested.annotationOrNull(GROUP_ANNOTATION)
                val scope = nested.annotationOrNull(SCOPE_ANNOTATION)
                if (group == null && scope == null) return@forEach
                val annotation = group ?: scope!!
                val values = annotation.values()
                val permission = values.string("permission")
                val permissions = inheritedPermissions + listOfNotNull(permission)
                val policies = inheritedPolicies + readPolicies(nested)
                val constructorParameters = nested.primaryConstructor?.parameters.orEmpty()
                val nextParameters = parameters.toMutableList()
                val invocationParameters = constructorParameters.map { parameter ->
                    if (group != null) {
                        val argumentIndex = nextParameters.size
                        nextParameters += parameter.inputModel(optionalAllowed = false)
                        InvocationParameterModel(parameter.name(), parameter.typeName(), argumentIndex)
                    } else {
                        InvocationParameterModel(parameter.name(), parameter.typeName(), null)
                    }
                }
                val nextSegments = if (group == null) {
                    segments
                } else {
                    val inferred = nested.simpleName.asString().toKebabCase()
                    val name = (values["name"] as? String).orEmpty().ifBlank { inferred }
                    val renamed = nested.annotationOrNull(RENAMED_ANNOTATION)
                        ?.values()
                        ?.takeIf { value -> schemaVersion < (value.getValue("untilVersion") as Int) }
                        ?.get("from")
                        ?.let { oldName -> listOf(oldName as String) }
                        .orEmpty()
                    segments + SegmentModel.Literal(
                        names = listOf(name) + values.strings("aliases") + renamed,
                        permissions = permissions,
                    ) + invocationParameters.mapNotNull { parameter ->
                        parameter.argumentIndex?.takeIf { index ->
                            nextParameters[index].option == null && nextParameters[index].options == null
                        }?.let(SegmentModel::Argument)
                    }
                }
                addAll(
                    readRoutes(
                        owner = nested,
                        containers = containers + ContainerModel(
                            typeName = nested.simpleName.asString(),
                            parameters = invocationParameters,
                        ),
                        segments = nextSegments,
                        parameters = nextParameters,
                        inheritedPermissions = permissions,
                        inheritedPolicies = policies,
                        schemaVersion = schemaVersion,
                    ),
                )
            }
    }

    private fun readRoute(
        function: KSFunctionDeclaration,
        containers: List<ContainerModel>,
        inheritedSegments: List<SegmentModel>,
        inheritedParameters: List<ParameterModel>,
        inheritedPermissions: List<String>,
        inheritedPolicies: List<PolicyModel>,
        schemaVersion: Int,
    ): RouteModel {
        val command = function.annotationOrNull(COMMAND_ANNOTATION)
        val values = command?.values().orEmpty()
        val inferredName = function.simpleName.asString().toKebabCase()
        val name = (values["name"] as? String).orEmpty().ifBlank { inferredName }
        val aliases = values.strings("aliases") + function.annotationOrNull(RENAMED_ANNOTATION)
            ?.values()
            ?.takeIf { renamed -> schemaVersion < (renamed.getValue("untilVersion") as Int) }
            ?.get("from")
            ?.let { oldName -> listOf(oldName as String) }
            .orEmpty()
        val permissions = inheritedPermissions + listOfNotNull(values.string("permission"))
        val routeParameters = inheritedParameters.toMutableList()
        val firstHandlerParameter = routeParameters.size
        val handlerParameters = function.parameters.map { parameter ->
            val argumentIndex = routeParameters.size
            routeParameters += parameter.inputModel(optionalAllowed = true)
            InvocationParameterModel(parameter.name(), parameter.typeName(), argumentIndex)
        }
        val leaf = if (function.annotationOrNull(ROOT_ANNOTATION) == null) {
            listOf(SegmentModel.Literal(listOf(name) + aliases, permissions))
        } else {
            emptyList()
        }
        val scansNamedOptions = routeParameters.drop(firstHandlerParameter).any { parameter ->
            parameter.option != null || parameter.options != null
        }
        val handlerSegments = if (scansNamedOptions) {
            listOf(SegmentModel.ScannedArguments(firstHandlerParameter))
        } else {
            handlerParameters.mapNotNull { parameter ->
                parameter.argumentIndex?.let(SegmentModel::Argument)
            }
        }
        return RouteModel(
            name = name,
            functionName = function.simpleName.asString(),
            returnType = function.returnType?.resolve()?.declaration?.qualifiedName?.asString().orEmpty(),
            suspending = Modifier.SUSPEND in function.modifiers,
            parameters = routeParameters,
            segments = inheritedSegments + leaf + handlerSegments,
            containers = containers,
            handlerParameters = handlerParameters,
            aliases = aliases,
            permissions = permissions,
            documentation = function.documentation(),
            policies = inheritedPolicies + readPolicies(function),
            cancelOnExecutorRetire = function.annotationOrNull(CANCEL_ON_RETIRE_ANNOTATION) != null,
        )
    }

    private fun readPolicies(declaration: KSAnnotated): List<PolicyModel> = buildList {
        declaration.annotationOrNull(COOLDOWN_ANNOTATION)?.values()?.let { values ->
            add(
                PolicyModel.Cooldown(
                    seconds = values.getValue("value") as Long,
                    mode = values.getValue("mode").enumName(),
                ),
            )
        }
        declaration.annotationOrNull(RATE_LIMIT_ANNOTATION)?.values()?.let { values ->
            add(
                PolicyModel.RateLimit(
                    permits = values.getValue("permits") as Int,
                    seconds = values.getValue("per") as Long,
                    mode = values.getValue("mode").enumName(),
                ),
            )
        }
        if (declaration.annotationOrNull(SINGLE_FLIGHT_ANNOTATION) != null) {
            add(PolicyModel.SingleFlight)
        }
        declaration.annotationOrNull(CONFIRM_ANNOTATION)?.values()?.let { values ->
            add(PolicyModel.Confirm(values.getValue("expiresAfterSeconds") as Long))
        }
        declaration.annotations.forEach { annotation ->
            val annotationType = annotation.annotationType.resolve().declaration as? KSClassDeclaration
                ?: return@forEach
            val meta = annotationType.annotationOrNull(COMMAND_POLICY_ANNOTATION) ?: return@forEach
            val metaValues = meta.values()
            val interceptor = metaValues.getValue("interceptor") as KSType
            add(
                PolicyModel.Custom(
                    annotationType = requireNotNull(annotationType.qualifiedName?.asString()),
                    arguments = annotation.arguments.map { argument ->
                        AnnotationArgumentModel(requireNotNull(argument.name?.asString()), argument.value)
                    },
                    interceptorType = requireNotNull(interceptor.declaration.qualifiedName?.asString()),
                    phase = metaValues.getValue("phase").enumName(),
                    order = metaValues.getValue("order") as Int,
                ),
            )
        }
    }

    private fun Any?.enumName(): String = when (this) {
        is KSType -> declaration.simpleName.asString()
        is KSClassDeclaration -> simpleName.asString()
        else -> toString().substringAfterLast('.')
    }

    private fun KSValueParameter.inputModel(optionalAllowed: Boolean): ParameterModel {
        val resolved = type.resolve()
        val declaredType = typeName()
        val optionsType = (resolved.declaration as? KSClassDeclaration)
            ?.takeIf { declaration -> declaration.annotationOrNull(OPTIONS_ANNOTATION) != null }
        if (optionsType != null) {
            val members = optionsType.primaryConstructor?.parameters.orEmpty().map { member ->
                val shape = member.valueShape()
                OptionMemberModel(
                    propertyName = member.name(),
                    declaredType = member.typeName(),
                    option = member.optionModel(shape),
                    hasDefault = member.hasDefault,
                    collection = shape.collection,
                    sensitive = member.hasAnnotation(SENSITIVE_ANNOTATION),
                    observed = member.hasAnnotation(OBSERVED_ANNOTATION),
                )
            }
            return ParameterModel(
                name = name(),
                type = declaredType,
                optional = false,
                options = OptionsModel(declaredType, members),
                sensitive = members.any(OptionMemberModel::sensitive),
                observed = members.any(OptionMemberModel::observed) && members.none(OptionMemberModel::sensitive),
            )
        }

        val shape = valueShape()
        val directOption = annotationOrNull(OPTION_ANNOTATION)?.let { optionModel(shape) }
        val minimumTicks = annotationValues(MINIMUM_TICKS_ANNOTATION)?.get("value") as? Int ?: 0
        val registry = annotationValues(FROM_REGISTRY_ANNOTATION)?.get("value") as? String
        return ParameterModel(
            name = name(),
            type = declaredType,
            typeArguments = resolved.arguments.mapNotNull { argument ->
                argument.type?.resolve()?.declaration?.qualifiedName?.asString()
            },
            optional = optionalAllowed && hasDefault,
            greedy = hasAnnotation(GREEDY_ANNOTATION),
            repeated = hasAnnotation(REPEATED_ANNOTATION) || isVararg,
            valueType = shape.valueType,
            nullable = shape.nullable,
            collection = shape.collection,
            vararg = isVararg,
            option = directOption,
            centerIntegers = hasAnnotation(CENTER_INTEGERS_ANNOTATION),
            minimumTicks = minimumTicks,
            registry = registry,
            sensitive = hasAnnotation(SENSITIVE_ANNOTATION),
            observed = hasAnnotation(OBSERVED_ANNOTATION),
            qualifier = commandQualifier(),
        )
    }

    private fun KSValueParameter.commandQualifier(): String? {
        val matches = (annotations + type.annotations + type.resolve().annotations)
            .mapNotNull { annotation ->
                val declaration = annotation.annotationType.resolve().declaration as? KSClassDeclaration
                    ?: return@mapNotNull null
                if (declaration.annotationOrNull(COMMAND_ARGUMENT_QUALIFIER) == null) return@mapNotNull null
                declaration.qualifiedName?.asString()
            }
            .distinct()
            .toList()
        require(matches.size <= 1) { "Command parameter '${name()}' has multiple argument qualifiers" }
        return matches.singleOrNull()
    }

    private fun KSValueParameter.annotationValues(annotationName: String): Map<String, Any?>? =
        annotationOrNull(annotationName)?.values()
            ?: type.annotations.firstOrNull { annotation -> annotation.qualifiedName() == annotationName }?.values()
            ?: type.resolve().annotations.firstOrNull { annotation ->
                annotation.qualifiedName() == annotationName
            }?.values()

    private data class ValueShape(
        val valueType: String,
        val nullable: Boolean,
        val collection: Boolean,
        val presenceAware: Boolean,
    )

    private fun KSValueParameter.valueShape(): ValueShape {
        val resolved = type.resolve()
        val declaredName = resolved.declaration.qualifiedName?.asString().orEmpty()
        val collection = declaredName == LIST_TYPE || isVararg
        val presenceAware = declaredName == OPTION_VALUE_TYPE
        val unwrap = collection || presenceAware
        val value = if (unwrap) {
            resolved.arguments.singleOrNull()?.type?.resolve()
                ?: error("Command parameter '${name()}' must declare an element type")
        } else {
            resolved
        }
        return ValueShape(
            valueType = value.declaration.qualifiedName?.asString()
                ?: error("Command parameter '${name()}' has no class value type"),
            nullable = resolved.nullability == Nullability.NULLABLE,
            collection = collection,
            presenceAware = presenceAware,
        )
    }

    private fun KSValueParameter.optionModel(shape: ValueShape): OptionModel {
        val values = annotationOrNull(OPTION_ANNOTATION)?.values().orEmpty()
        val short = values["short"] as? Char
        return OptionModel(
            name = (values["name"] as? String).orEmpty().ifBlank { name().toKebabCase() },
            shortName = short?.takeUnless { it == '\u0000' },
            valueType = shape.valueType,
            nullable = shape.nullable,
            repeated = shape.collection,
            presenceAware = shape.presenceAware,
            qualifier = commandQualifier(),
        )
    }

    private fun KSValueParameter.typeName(): String =
        type.resolve().declaration.qualifiedName?.asString()
            ?: error("Command parameter '${name()}' has no class type")

    private fun KSValueParameter.name(): String = requireNotNull(name?.asString())

    private fun KSValueParameter.hasAnnotation(annotationName: String): Boolean =
        annotationOrNull(annotationName) != null ||
            type.annotations.any { annotation -> annotation.qualifiedName() == annotationName } ||
            type.resolve().annotations.any { annotation -> annotation.qualifiedName() == annotationName } ||
            typeAliasHasAnnotation(type.resolve().declaration, annotationName) ||
            ((type.element as? KSClassifierReference)?.referencedName()?.let(aliasAnnotations::get)
                ?.contains(annotationName) == true)

    @OptIn(KspExperimental::class)
    private fun readAliasAnnotations(resolver: Resolver): Map<String, Set<String>> {
        val sourceAliases = resolver.getAllFiles()
            .flatMap { file -> file.declarations }
            .filterIsInstance<KSTypeAlias>()
        val frameworkAliases = resolver.getDeclarationsFromPackage("dev.placeholder.framework.commands")
            .filterIsInstance<KSTypeAlias>()
        return (sourceAliases + frameworkAliases)
            .associate { alias ->
                alias.simpleName.asString() to (
                    alias.annotations.mapNotNull { annotation -> annotation.qualifiedName() } +
                        alias.type.annotations.mapNotNull { annotation -> annotation.qualifiedName() } +
                        alias.type.resolve().annotations.mapNotNull { annotation -> annotation.qualifiedName() }
                    ).toSet()
            }
    }

    private fun typeAliasHasAnnotation(
        declaration: KSDeclaration,
        annotationName: String,
    ): Boolean {
        if (declaration !is KSTypeAlias) return false
        if (declaration.annotations.any { annotation -> annotation.qualifiedName() == annotationName }) return true
        return typeAliasHasAnnotation(declaration.type.resolve().declaration, annotationName)
    }

    private fun KSFunctionDeclaration.documentation(): DocumentationModel {
        val lines = docString.orEmpty().lines().map(String::trim)
        val summary = lines.takeWhile { line -> !line.startsWith('@') }.joinToString(" ").trim()
        val parameters = lines.filter { line -> line.startsWith("@param ") }
            .associate { line ->
                val content = line.removePrefix("@param ")
                content.substringBefore(' ') to content.substringAfter(' ', "").trim()
            }
        val examples = lines.filter { line -> line.startsWith("@example ") }
            .map { line -> line.removePrefix("@example ").trim() }
        return DocumentationModel(summary, parameters, examples)
    }

    private fun isHandler(function: KSFunctionDeclaration): Boolean =
        function.functionKind == FunctionKind.MEMBER &&
            function.simpleName.asString() != "<init>" &&
            function.isPublic() &&
            function.annotationOrNull(CONFIGURE_GRAPH_ANNOTATION) == null

    /** KSP reports only explicitly written visibility modifiers. Kotlin's absence means public. */
    private fun KSFunctionDeclaration.isPublic(): Boolean =
        modifiers.none { modifier ->
            modifier == Modifier.PRIVATE ||
                modifier == Modifier.PROTECTED ||
                modifier == Modifier.INTERNAL
        }

    private fun KSAnnotated.annotation(name: String): KSAnnotation =
        requireNotNull(annotationOrNull(name)) { "Missing annotation $name" }

    private fun KSAnnotated.annotationOrNull(name: String): KSAnnotation? =
        annotations.singleOrNull { annotation -> annotation.qualifiedName() == name }

    private fun KSAnnotation.qualifiedName(): String? =
        annotationType.resolve().declaration.qualifiedName?.asString()

    private fun KSAnnotation.values(): Map<String, Any?> =
        arguments.associate { argument -> requireNotNull(argument.name?.asString()) to argument.value }

    private fun Map<String, Any?>.strings(name: String): List<String> =
        (get(name) as? List<*>)?.map { value -> value as String }.orEmpty()

    private fun Map<String, Any?>.string(name: String): String? =
        (get(name) as? String)?.ifBlank { null }

    private fun String.toKebabCase(): String =
        replace(Regex("([a-z0-9])([A-Z])"), "$1-$2").lowercase()

    private companion object {
        const val COMMANDS_ANNOTATION = "dev.placeholder.framework.commands.Commands"
        const val COMMAND_FRAGMENT_ANNOTATION = "dev.placeholder.framework.commands.CommandFragment"
        const val COMMAND_ANNOTATION = "dev.placeholder.framework.commands.Command"
        const val GROUP_ANNOTATION = "dev.placeholder.framework.commands.Group"
        const val SCOPE_ANNOTATION = "dev.placeholder.framework.commands.Scope"
        const val ROOT_ANNOTATION = "dev.placeholder.framework.commands.Root"
        const val GREEDY_ANNOTATION = "dev.placeholder.framework.commands.Greedy"
        const val REPEATED_ANNOTATION = "dev.placeholder.framework.commands.Repeated"
        const val OPTION_ANNOTATION = "dev.placeholder.framework.commands.Option"
        const val OPTIONS_ANNOTATION = "dev.placeholder.framework.commands.Options"
        const val OPTION_VALUE_TYPE = "dev.placeholder.framework.commands.OptionValue"
        const val LIST_TYPE = "kotlin.collections.List"
        const val RENAMED_ANNOTATION = "dev.placeholder.framework.commands.CommandRenamed"
        const val CENTER_INTEGERS_ANNOTATION =
            "dev.placeholder.framework.commands.minecraft.CenterIntegers"
        const val MINIMUM_TICKS_ANNOTATION =
            "dev.placeholder.framework.commands.minecraft.MinimumTicks"
        const val FROM_REGISTRY_ANNOTATION =
            "dev.placeholder.framework.commands.minecraft.FromRegistry"
        const val SENSITIVE_ANNOTATION = "dev.placeholder.framework.commands.Sensitive"
        const val OBSERVED_ANNOTATION = "dev.placeholder.framework.commands.Observed"
        const val COMMAND_ARGUMENT_QUALIFIER =
            "dev.placeholder.framework.commands.codec.CommandArgumentQualifier"
        const val COOLDOWN_ANNOTATION = "dev.placeholder.framework.commands.policy.Cooldown"
        const val RATE_LIMIT_ANNOTATION = "dev.placeholder.framework.commands.policy.RateLimit"
        const val SINGLE_FLIGHT_ANNOTATION = "dev.placeholder.framework.commands.policy.SingleFlight"
        const val CONFIRM_ANNOTATION = "dev.placeholder.framework.commands.policy.Confirm"
        const val COMMAND_POLICY_ANNOTATION = "dev.placeholder.framework.commands.policy.CommandPolicy"
        const val CANCEL_ON_RETIRE_ANNOTATION =
            "dev.placeholder.framework.commands.policy.CancelOnExecutorRetire"
        const val CONFIGURE_GRAPH_ANNOTATION = "dev.placeholder.framework.commands.ConfigureCommandGraph"
    }
}
