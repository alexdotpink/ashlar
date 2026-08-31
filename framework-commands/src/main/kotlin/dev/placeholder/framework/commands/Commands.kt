package dev.placeholder.framework.commands

import kotlin.reflect.KClass

/** Declares one automatically installed command root. */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
public annotation class Commands(
    /** Root literal. Empty names are inferred from the class name. */
    val name: String = "",
    val aliases: Array<String> = [],
    /** Best-effort aliases omitted when another plug-in already owns the name. */
    val optionalAliases: Array<String> = [],
    val permission: String = "",
    /** Schema used to expire temporary [CommandRenamed] spellings. */
    val schemaVersion: Int = 1,
    /** Automatic help literal. Empty disables the explicit help subcommand. */
    val helpName: String = "help",
)

/** Overrides a handler or structural group's inferred literal and aliases. */
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
@Repeatable
public annotation class Command(
    val name: String = "",
    val aliases: Array<String> = [],
    val permission: String = "",
)

/** Adds a literal segment and shared requirements around nested handlers. */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
public annotation class Group(
    val name: String = "",
    val aliases: Array<String> = [],
    val permission: String = "",
)

/** Adds invocation dependencies and requirements without adding a path segment. */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
public annotation class Scope(val permission: String = "")

/** Marks a function as the handler for its current path instead of adding a literal. */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
public annotation class Root

/** Retains an older handler spelling until the owning command schema reaches [untilVersion]. */
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
public annotation class CommandRenamed(
    val from: String,
    val untilVersion: Int,
)

/** Reusable named-option value object. */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
public annotation class Options

/** Declares a named option parameter or property. */
@Target(AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.BINARY)
public annotation class Option(
    val name: String = "",
    val short: Char = '\u0000',
)

/** Makes the annotated string consume the decoded terminal positional remainder. */
@Target(AnnotationTarget.TYPE, AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.BINARY)
public annotation class Greedy

/** Makes a terminal list parameter consume repeated positional values. */
@Target(AnnotationTarget.TYPE, AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.BINARY)
public annotation class Repeated

/** Prevents a command parameter from appearing in logs or unwrapped generated links. */
@Target(AnnotationTarget.TYPE, AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.BINARY)
public annotation class Sensitive

/** Explicitly opts a command value into observer metadata. */
@Target(AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.BINARY)
public annotation class Observed

/** Marks a dynamic requirement which may reveal the route in help after denial. */
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
public annotation class Restricted

/** Contributes an independently compiled structural fragment to an owning command set. */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
public annotation class CommandFragment(val root: KClass<*>)

/** Marks a startup function which declares redirects, forks, or external graph edges. */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
public annotation class ConfigureCommandGraph

/** Prevents selected generated command contributions from loading in this plug-in. */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
public annotation class ExcludeCommandContributions(vararg val types: KClass<*>)

/** Source-compatible name for greedy decoded text. */
public typealias GreedyText = @Greedy String

/** Presence-aware direct option, used when null is a meaningful supplied value. */
public sealed interface OptionValue<out T> {
    public data object Absent : OptionValue<Nothing>

    public data class Present<T>(public val value: T) : OptionValue<T>
}
