package pink.alex.ashlar.commands.route

import java.security.MessageDigest
import net.kyori.adventure.text.event.ClickEvent

/** The canonical semantic identity of one typed command invocation. */
public class CommandRouteIdentity internal constructor(
    public val route: String,
    arguments: List<String>,
) {
    internal val arguments: List<String> = arguments.toList()

    override fun equals(other: Any?): Boolean =
        other is CommandRouteIdentity && route == other.route && arguments == other.arguments

    override fun hashCode(): Int = 31 * route.hashCode() + arguments.hashCode()

    override fun toString(): String = route
}

internal fun commandRouteIdentity(route: String, arguments: List<String>): CommandRouteIdentity =
    CommandRouteIdentity(route, arguments.map { argument -> "v:$argument" })

/** An immutable, canonically encoded command produced by a generated typed route. */
public class CommandRoute internal constructor(
    public val identity: CommandRouteIdentity,
    public val command: String,
) {
    /** Creates a click event which executes this command. */
    public fun runLink(): ClickEvent<*> = ClickEvent.runCommand(command)

    /** Creates a click event which places this command in the client's input. */
    public fun suggestLink(): ClickEvent<*> = ClickEvent.suggestCommand(command)

    override fun equals(other: Any?): Boolean =
        other is CommandRoute && identity == other.identity

    override fun hashCode(): Int = identity.hashCode()

    override fun toString(): String = "CommandRoute(${identity.route})"
}

/** One ordered literal or encoded argument in a typed command route. */
public sealed class CommandRouteSegment protected constructor() {
    internal abstract val commandToken: String
    internal abstract val identityArgument: String?
}

private class LiteralSegment(
    override val commandToken: String,
) : CommandRouteSegment() {
    override val identityArgument: String? = null

    override fun toString(): String = commandToken
}

private class ArgumentSegment(
    value: String,
    sensitive: Boolean,
) : CommandRouteSegment() {
    override val commandToken: String = quoteCommandToken(value)
    override val identityArgument: String =
        if (sensitive) "s:${value.sha256()}" else "v:$value"

    override fun toString(): String = "[argument]"
}

private class OptionSegment(name: String, value: String) : CommandRouteSegment() {
    override val commandToken: String = "--$name=$value"
    override val identityArgument: String = "v:$value"
}

/** Creates a validated command literal. Generated routes use canonical names, never aliases. */
public fun routeLiteral(value: String): CommandRouteSegment {
    require(value.isNotEmpty()) { "Command literals cannot be empty" }
    require(value.all(::isAllowedInUnquotedString)) {
        "Command literal '$value' contains a character Brigadier cannot read unquoted"
    }
    return LiteralSegment(value)
}

/** Adds an already codec-encoded, non-sensitive value to a typed route. */
public fun routeArgument(value: String): CommandRouteSegment {
    requireSafeCommandValue(value)
    return ArgumentSegment(value, sensitive = false)
}

internal fun sensitiveRouteArgument(value: String): CommandRouteSegment {
    requireSafeCommandValue(value)
    return ArgumentSegment(value, sensitive = true)
}

internal fun routeOption(name: String, value: String): CommandRouteSegment {
    require(name.matches(Regex("[a-z0-9_-]+"))) { "Invalid option name '$name'" }
    requireSafeCommandValue(value)
    require(value.none(Char::isWhitespace)) {
        "Option values containing whitespace must use the separate option encoder"
    }
    return OptionSegment(name, value)
}

/** Builds an immutable route from its stable generated identifier and ordered segments. */
public fun commandRoute(
    route: String,
    segments: List<CommandRouteSegment>,
): CommandRoute {
    require(route.isNotBlank()) { "Command route identity cannot be blank" }
    require(route.none(Char::isISOControl)) { "Command route identity cannot contain control characters" }
    require(segments.isNotEmpty()) { "A command route needs at least one segment" }
    require(segments.first() is LiteralSegment) { "A command route must start with its root literal" }

    val immutableSegments = segments.toList()
    val identity = CommandRouteIdentity(
        route = route,
        arguments = immutableSegments.mapNotNull(CommandRouteSegment::identityArgument),
    )
    val command = immutableSegments.joinToString(separator = " ", prefix = "/") {
        it.commandToken
    }
    return CommandRoute(identity, command)
}

private fun quoteCommandToken(value: String): String =
    if (value.isNotEmpty() && value.all(::isAllowedInUnquotedString)) {
        value
    } else {
        buildString(value.length + 2) {
            append('"')
            value.forEach { character ->
                if (character == '\\' || character == '"') append('\\')
                append(character)
            }
            append('"')
        }
    }

private fun requireSafeCommandValue(value: String) {
    require(value.none(Char::isISOControl)) { "Command arguments cannot contain control characters" }
}

private fun isAllowedInUnquotedString(character: Char): Boolean =
    character in '0'..'9' ||
        character in 'A'..'Z' ||
        character in 'a'..'z' ||
        character == '_' ||
        character == '-' ||
        character == '.' ||
        character == '+'

private fun String.sha256(): String =
    MessageDigest.getInstance("SHA-256")
        .digest(toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte) }
