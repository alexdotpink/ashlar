package pink.alex.ashlar.commands.route

import pink.alex.ashlar.commands.codec.CommandArgumentCodec

/** A command value explicitly approved for inclusion in a generated link. */
public class SensitiveRouteValue<out T> internal constructor(
    internal val value: T,
) {
    override fun toString(): String = "[sensitive]"
}

/** Explicitly approves a sensitive command value for route encoding. */
public fun <T> sensitive(value: T): SensitiveRouteValue<T> = SensitiveRouteValue(value)

/** Encodes an ordinary typed argument for a generated route. */
public fun <T : Any> CommandArgumentCodec<T>.routeArgument(value: T): CommandRouteSegment =
    routeArgument(encode(value))

/** Encodes a sensitive typed argument after the caller has wrapped it with [sensitive]. */
public fun <T : Any> CommandArgumentCodec<T>.sensitiveRouteArgument(
    value: SensitiveRouteValue<T>,
): CommandRouteSegment = sensitiveRouteArgument(encode(value.value))
