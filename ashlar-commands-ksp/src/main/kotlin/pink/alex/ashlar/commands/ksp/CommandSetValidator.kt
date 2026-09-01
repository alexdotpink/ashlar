package pink.alex.ashlar.commands.ksp

internal class CommandSetValidator {
    fun validate(model: CommandSetModel): List<String> = buildList {
        if (!model.rootName.matches(COMMAND_LITERAL)) {
            add("Command root '${model.rootName}' must contain only lowercase letters, digits, '_' or '-'")
        }
        if (model.routes.isEmpty()) {
            add("A @Commands class must declare at least one public command function")
        }

        model.routes
            .groupBy(::rawSignature)
            .filterValues { routes -> routes.size > 1 }
            .forEach { (_, routes) ->
                add("Command route '${routes.first().name}' has an ambiguous raw syntax")
            }

        model.routes.forEach { route -> validateRoute(route, this) }
        val acceptedRoots = (listOf(model.rootName) + model.aliases).map { name -> "/$name" }
        model.routes.flatMap { route -> route.documentation.examples.map { route to it } }
            .forEach { (route, example) ->
                if (acceptedRoots.none { root -> example == root || example.startsWith("$root ") }) {
                    add("Example '$example' for '${route.functionName}' must start with a command root")
                }
            }
    }

    private fun validateRoute(route: RouteModel, problems: MutableList<String>) {
        if (!route.name.matches(COMMAND_LITERAL)) {
            problems += "Command function '${route.functionName}' is not a valid literal"
        }
        var optionalSeen = false
        route.parameters.filter { parameter -> parameter.isPositional() }.forEach { parameter ->
            if (parameter.optional) {
                optionalSeen = true
            } else if (optionalSeen) {
                problems +=
                    "Required command parameter '${route.functionName}.${parameter.name}' cannot follow an optional parameter"
            }
        }
        route.parameters.forEachIndexed { index, parameter ->
            val laterPositional = route.parameters.drop(index + 1).any { later -> later.isPositional() }
            if (parameter.isPositional() && (parameter.greedy || parameter.repeated) && laterPositional) {
                problems += "Command parameter '${route.functionName}.${parameter.name}' must be terminal"
            }
            if (parameter.isPositional() && parameter.nullable) {
                problems +=
                    "Positional command parameter '${route.functionName}.${parameter.name}' must use a Kotlin default instead of nullability"
            }
            if (parameter.repeated && !parameter.collection && !parameter.vararg) {
                problems +=
                    "Repeated command parameter '${route.functionName}.${parameter.name}' must be a List or vararg"
            }
            if (parameter.option != null && parameter.optional) {
                problems +=
                    "Direct option '${route.functionName}.${parameter.name}' cannot declare a Kotlin default; use nullability or OptionValue"
            }
            parameter.options?.members?.filterNot(OptionMemberModel::hasDefault)?.forEach { member ->
                problems += "Options property '${parameter.options.type}.${member.propertyName}' must declare a Kotlin default"
            }
        }
        validateOptionNames(route, problems)
        val scanned = route.segments.filterIsInstance<SegmentModel.ScannedArguments>().singleOrNull()
        if (scanned != null && route.parameters.take(scanned.firstParameterIndex).any { parameter ->
                parameter.option != null || parameter.options != null
            }
        ) {
            problems += "Named options are not supported on structural group constructor parameters"
        }
        route.policies.groupBy(::policyName)
            .filterValues { policies -> policies.size > 1 }
            .forEach { (type, _) ->
                problems += "Command function '${route.functionName}' declares $type more than once"
            }
        route.policies.forEach { policy ->
            when (policy) {
                is PolicyModel.Cooldown -> if (policy.seconds <= 0) {
                    problems += "Cooldown on '${route.functionName}' must be positive"
                }
                is PolicyModel.RateLimit -> {
                    if (policy.permits <= 0) {
                        problems += "Rate limit permits on '${route.functionName}' must be positive"
                    }
                    if (policy.seconds <= 0) {
                        problems += "Rate limit duration on '${route.functionName}' must be positive"
                    }
                }
                PolicyModel.SingleFlight -> Unit
                is PolicyModel.Confirm -> if (policy.seconds <= 0) {
                    problems += "Confirmation expiry on '${route.functionName}' must be positive"
                }
                is PolicyModel.Custom -> Unit
            }
        }
    }

    private fun validateOptionNames(route: RouteModel, problems: MutableList<String>) {
        val options = route.parameters.flatMap { parameter ->
            parameter.option?.let(::listOf)
                ?: parameter.options?.members?.map(OptionMemberModel::option)
                ?: emptyList()
        }
        options.groupBy(OptionModel::name).filterValues { matches -> matches.size > 1 }.keys.forEach { name ->
            problems += "Command route '${route.name}' declares duplicate option '--$name'"
        }
        options.mapNotNull { option -> option.shortName?.let { short -> short to option } }
            .groupBy { (short) -> short }
            .filterValues { matches -> matches.size > 1 }
            .keys
            .forEach { short -> problems += "Command route '${route.name}' declares duplicate option '-$short'" }
    }

    private fun policyName(policy: PolicyModel): String = when (policy) {
        is PolicyModel.Cooldown -> "Cooldown"
        is PolicyModel.RateLimit -> "RateLimit"
        PolicyModel.SingleFlight -> "SingleFlight"
        is PolicyModel.Confirm -> "Confirm"
        is PolicyModel.Custom -> policy.annotationType
    }

    private fun rawSignature(route: RouteModel): String = route.segments.joinToString("/") { segment ->
        when (segment) {
            is SegmentModel.Literal -> segment.names.first()
            is SegmentModel.Argument -> {
                val parameter = route.parameters[segment.parameterIndex]
                "<${parameter.type}:${parameter.greedy}:${parameter.repeated}:${parameter.optional}>"
            }
            is SegmentModel.ScannedArguments -> route.parameters.drop(segment.firstParameterIndex)
                .joinToString(prefix = "<scan:", postfix = ">") { parameter ->
                    parameter.option?.name
                        ?: parameter.options?.members?.joinToString { member -> member.option.name }
                        ?: parameter.valueType
                }
        }
    }

    private fun ParameterModel.isPositional(): Boolean = option == null && options == null

    private companion object {
        val COMMAND_LITERAL = Regex("[a-z0-9_-]+")
    }
}
