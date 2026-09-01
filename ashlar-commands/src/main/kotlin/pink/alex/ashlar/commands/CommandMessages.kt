package pink.alex.ashlar.commands

import java.util.Locale
import net.kyori.adventure.text.Component

/** Localized ashlar-originated command responses. */
public interface CommandMessages {
    public fun invalidArgument(
        locale: Locale,
        name: String,
        reason: String,
    ): Component

    public fun missingArgument(
        locale: Locale,
        name: String,
    ): Component

    public fun noPermission(locale: Locale): Component

    public fun unexpectedFailure(locale: Locale): Component
}

/** Plain English defaults which plug-ins may replace through DI. */
public object EnglishCommandMessages : CommandMessages {
    override fun invalidArgument(locale: Locale, name: String, reason: String): Component =
        Component.text("Invalid $name: $reason")

    override fun missingArgument(locale: Locale, name: String): Component =
        Component.text("Missing required argument '$name'.")

    override fun noPermission(locale: Locale): Component = Component.text("You do not have permission to use that command.")

    override fun unexpectedFailure(locale: Locale): Component =
        Component.text("That command could not be completed.")
}

/** Handles one domain exception before the framework falls back to its unexpected-failure response. */
public interface CommandExceptionHandler<T : Throwable> {
    public val type: kotlin.reflect.KClass<T>

    public suspend fun handle(
        error: T,
        invocation: CommandInvocation,
    ): CommandResult
}
