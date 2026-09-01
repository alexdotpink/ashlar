package pink.alex.ashlar.commands

import net.kyori.adventure.text.Component

/** Responses produced by one accepted command invocation. */
public class CommandResult private constructor(
    public val responses: List<Component>,
) {
    public companion object {
        public val Empty: CommandResult = CommandResult(emptyList())

        public fun of(response: Component): CommandResult = CommandResult(listOf(response))
    }

    public class Builder {
        private val responses: MutableList<Component> = mutableListOf()

        public fun reply(response: Component): Unit {
            responses += response
        }

        public fun reply(response: String): Unit = reply(Component.text(response))

        internal fun build(): CommandResult = CommandResult(responses.toList())
    }
}

/** Builds a multi-response command result. */
public fun responses(block: CommandResult.Builder.() -> Unit): CommandResult =
    CommandResult.Builder().apply(block).build()

/** Stackless expected command rejection. */
public class CommandRejectedException internal constructor(
    public val response: Component,
) : RuntimeException(null, null, false, false)

/** Stops the current command with literal text. */
public fun reject(message: String): Nothing = throw CommandRejectedException(Component.text(message))

/** Stops the current command with an Adventure response. */
public fun reject(message: Component): Nothing = throw CommandRejectedException(message)

/** Returns this value or rejects the invocation with [message]. */
public inline fun <T : Any> T?.orReject(message: () -> String): T = this ?: reject(message())

/** Returns this value or rejects the invocation with [message]. */
@JvmName("orRejectComponent")
public inline fun <T : Any> T?.orReject(message: () -> Component): T = this ?: reject(message())

/** Converts a custom domain handler result to responses. */
public fun interface CommandResponseCodec<T : Any> {
    public val type: kotlin.reflect.KClass<T>
        get() = error("A command response codec must declare its domain type")

    public suspend fun encode(value: T): CommandResult
}
