package pink.alex.ashlar.commands.codec

import pink.alex.ashlar.commands.CommandInvocation
import pink.alex.ashlar.di.DependencyResolver
import kotlin.reflect.KClass

/** Marks a type-use annotation which selects one codec for an otherwise shared Kotlin type. */
@Target(AnnotationTarget.ANNOTATION_CLASS)
@Retention(AnnotationRetention.BINARY)
public annotation class CommandArgumentQualifier

/** Brigadier-facing raw syntax selected before asynchronous domain resolution. */
public enum class CommandSyntax {
    WORD,
    STRING,
    GREEDY_STRING,
    INTEGER,
    LONG,
    FLOAT,
    DOUBLE,
    BOOLEAN,
}

/** Parses, resolves, suggests, and encodes one Kotlin command argument type. */
public interface CommandArgumentCodec<T : Any> {
    public val type: KClass<T>

    public val qualifier: KClass<out Annotation>?
        get() = null

    public val syntax: CommandSyntax
        get() = CommandSyntax.WORD

    public suspend fun resolve(
        raw: String,
        invocation: CommandInvocation,
        dependencies: DependencyResolver,
    ): T

    public suspend fun suggest(
        input: String,
        invocation: CommandInvocation,
        dependencies: DependencyResolver,
    ): List<String> = emptyList()

    public fun encode(value: T): String
}

/** Replaces suggestions for one argument without replacing its codec. */
public interface CommandSuggestionProvider<T : Any> {
    public val type: KClass<T>

    public val qualifier: KClass<out Annotation>?
        get() = null

    public suspend fun suggest(
        input: String,
        invocation: CommandInvocation,
        dependencies: DependencyResolver,
    ): List<String>
}

/** Expected typed argument resolution failure. */
public class CommandArgumentException(public val reason: String) :
    RuntimeException(null, null, false, false)

public fun invalidArgument(reason: String): Nothing = throw CommandArgumentException(reason)
