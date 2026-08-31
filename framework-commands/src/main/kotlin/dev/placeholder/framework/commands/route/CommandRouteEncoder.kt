package dev.placeholder.framework.commands.route

import dev.placeholder.framework.commands.codec.CommandArgumentCodec
import dev.placeholder.framework.commands.internal.BuiltinCodecs
import dev.placeholder.framework.di.DependencyGraph
import dev.placeholder.framework.di.Inject
import dev.placeholder.framework.di.PluginScoped
import kotlin.reflect.KClass

/** Plug-in-scoped encoder used only by generated typed route classes. */
@Inject
@PluginScoped
public class CommandRouteEncoder(
    private val graph: DependencyGraph,
) {
    public fun <T : Any> argument(
        type: KClass<T>,
        value: T,
        qualifier: KClass<out Annotation>? = null,
    ): List<CommandRouteSegment> {
        val codec = codecOrNull(type, qualifier)
        if (codec != null) return listOf(routeArgument(codec.encode(value)))
        return semanticArguments(value)
    }

    public fun <T : Any> sensitiveArgument(
        type: KClass<T>,
        value: SensitiveRouteValue<T>,
        qualifier: KClass<out Annotation>? = null,
    ): CommandRouteSegment = codec(type, qualifier).sensitiveRouteArgument(value)

    public fun <T : Any> option(
        name: String,
        type: KClass<T>,
        value: T,
        qualifier: KClass<out Annotation>? = null,
    ): List<CommandRouteSegment> {
        val encoded = codec(type, qualifier).encode(value)
        return if (type == Boolean::class) {
            listOf(routeOption(name, encoded))
        } else {
            listOf(routeLiteral("--$name"), routeArgument(encoded))
        }
    }

    private fun <T : Any> codec(
        type: KClass<T>,
        qualifier: KClass<out Annotation>?,
    ): CommandArgumentCodec<T> {
        return codecOrNull(type, qualifier) ?: error("No command argument codec for ${type.qualifiedName}")
    }

    private fun <T : Any> codecOrNull(
        type: KClass<T>,
        qualifier: KClass<out Annotation>?,
    ): CommandArgumentCodec<T>? {
        val custom = graph.contributions(CommandArgumentCodec::class)
            .singleOrNull { candidate -> candidate.type == type && candidate.qualifier == qualifier }
        @Suppress("UNCHECKED_CAST")
        return (custom ?: BuiltinCodecs.find(type)) as CommandArgumentCodec<T>?
    }
}
