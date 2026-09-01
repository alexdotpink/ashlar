package pink.alex.ashlar.commands.codegen

import pink.alex.ashlar.commands.CommandResult
import pink.alex.ashlar.commands.policy.CommandPolicyDefinition
import pink.alex.ashlar.di.DependencyResolver
import pink.alex.ashlar.commands.graph.CommandGraph
import kotlin.reflect.KClass
import net.kyori.adventure.text.Component

/** Immutable command metadata consumed by the runtime Paper adapter. */
public class CommandSetDefinition(
    public val name: String,
    public val aliases: List<String>,
    public val permission: String?,
    public val routes: List<CommandRouteDefinition>,
    public val helpName: String? = "help",
    public val fragment: Boolean = false,
    public val optionalAliases: List<String> = emptyList(),
)

/** One executable literal below a command root. */
public class CommandRouteDefinition(
    public val name: String,
    public val parameters: List<CommandParameterDefinition>,
    public val permission: String? = null,
    public val permissions: List<String> = listOfNotNull(permission),
    public val aliases: List<String> = emptyList(),
    public val documentation: CommandDocumentation = CommandDocumentation(),
    public val policies: List<CommandPolicyDefinition> = emptyList(),
    public val cancelOnExecutorRetire: Boolean = false,
    public val segments: List<CommandSegmentDefinition> =
        listOf(CommandSegmentDefinition.Literal(listOf(name) + aliases, permissions)),
)

/** One structural path segment in an immutable generated command plan. */
public sealed interface CommandSegmentDefinition {
    public data class Literal(
        public val names: List<String>,
        public val permissions: List<String> = emptyList(),
    ) : CommandSegmentDefinition

    public data class Argument(public val parameterIndex: Int) : CommandSegmentDefinition

    /** A raw handler tail whose named options may be interleaved with positionals. */
    public data class ScannedArguments(public val firstParameterIndex: Int) : CommandSegmentDefinition
}

/** One typed handler parameter in an immutable generated command plan. */
public class CommandParameterDefinition(
    public val name: String,
    public val optional: Boolean,
    public val type: KClass<*> = String::class,
    public val greedy: Boolean = false,
    public val repeated: Boolean = false,
    public val nullable: Boolean = false,
    public val option: CommandOptionDefinition? = null,
    public val options: CommandOptionsDefinition? = null,
    public val centerIntegers: Boolean = false,
    public val minimumTicks: Int = 0,
    public val registry: String? = null,
    public val sensitive: Boolean = false,
    public val observed: Boolean = false,
    public val qualifier: KClass<out Annotation>? = null,
)

/** One named option, resolved through the same codec as a positional value. */
public data class CommandOptionDefinition(
    public val name: String,
    public val shortName: Char? = null,
    public val type: KClass<*>,
    public val nullable: Boolean = false,
    public val repeated: Boolean = false,
    public val presenceAware: Boolean = false,
    public val qualifier: KClass<out Annotation>? = null,
)

/** Runtime shape of an [pink.alex.ashlar.commands.Options] value object. */
public data class CommandOptionsDefinition(
    public val members: List<CommandOptionMemberDefinition>,
)

/** One constructor property in an options value object. */
public data class CommandOptionMemberDefinition(
    public val propertyName: String,
    public val option: CommandOptionDefinition,
)

/** Documentation captured from a command handler's KDoc. */
public data class CommandDocumentation(
    public val summary: String = "",
    public val parameters: Map<String, String> = emptyMap(),
    public val examples: List<String> = emptyList(),
)

/** Minimal linkage implemented by generated command-set bindings. */
public interface CommandSetBinding<T : Any> : CommandSetContribution {
    override val targetType: KClass<T>

    override val definition: CommandSetDefinition

    public suspend fun invokeTyped(
        target: T,
        route: Int,
        arguments: List<Any?>,
        dependencies: DependencyResolver,
    ): Any?

    override suspend fun invoke(
        target: Any,
        route: Int,
        arguments: List<Any?>,
        dependencies: DependencyResolver,
    ): Any? {
        @Suppress("UNCHECKED_CAST")
        return invokeTyped(target as T, route, arguments, dependencies)
    }
}

/** Non-generic runtime view contributed by each generated binding. */
public interface CommandSetContribution {
    public val targetType: KClass<*>

    public val definition: CommandSetDefinition

    public suspend fun invoke(
        target: Any,
        route: Int,
        arguments: List<Any?>,
        dependencies: DependencyResolver,
    ): Any?

    /** Returns the single generated defaults instance's property values. */
    public fun optionDefaults(route: Int, parameter: Int): List<Any?> =
        invalidCommandOptions(route, parameter)

    /** Directly calls an options value object's primary constructor. */
    public fun constructOptions(route: Int, parameter: Int, values: List<Any?>): Any =
        invalidCommandOptions(route, parameter)

    /** Executes generated startup graph declarations without generating graph behavior. */
    public fun configureGraph(
        target: Any,
        dependencies: DependencyResolver,
        graph: CommandGraph,
    ): Unit = Unit
}

/** Response returned by generated bindings and delivered by the runtime. */
public sealed interface CommandResponse {
    public class Message(public val value: Component) : CommandResponse
}

/** Converts every built-in handler result without reflection. */
public fun commandResult(value: Any?): CommandResult =
    when (value) {
        null, Unit -> CommandResult.Empty
        is CommandResult -> value
        is String -> CommandResult.of(Component.text(value))
        is Component -> CommandResult.of(value)
        else -> error(
            "No CommandResponseCodec is registered for ${value::class.qualifiedName}",
        )
    }

/** Converts a plain string handler result without parsing formatting codes. */
public fun commandResponse(value: String): CommandResponse =
    CommandResponse.Message(Component.text(value))

/** Preserves an Adventure component handler result. */
public fun commandResponse(value: Component): CommandResponse = CommandResponse.Message(value)

/** Reports an invalid generated route index. */
public fun invalidCommandRoute(route: Int): Nothing =
    error("Generated command binding received unknown route index $route")

/** Reports an invalid argument count passed to generated code. */
public fun invalidCommandArgumentCount(
    route: Int,
    actual: Int,
): Nothing = error("Generated command route $route received $actual arguments")

/** Reports an invalid generated options factory lookup. */
public fun invalidCommandOptions(route: Int, parameter: Int): Nothing =
    error("Generated command route $route has no options factory for parameter $parameter")

/** Internal absence marker which lets generated calls preserve Kotlin defaults. */
public data object MissingCommandArgument
