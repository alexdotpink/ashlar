package pink.alex.ashlar.commands.internal

import pink.alex.ashlar.commands.CommandInvocation
import pink.alex.ashlar.commands.codec.CommandArgumentCodec
import pink.alex.ashlar.commands.codec.CommandSyntax
import pink.alex.ashlar.commands.codec.invalidArgument
import pink.alex.ashlar.di.DependencyResolver
import java.util.UUID
import kotlin.reflect.KClass

internal object BuiltinCodecs {
    private val codecs: Map<KClass<*>, CommandArgumentCodec<*>> = listOf(
        codec(String::class, CommandSyntax.STRING, { it }, { it }),
        codec(Int::class, CommandSyntax.INTEGER, { it.toIntOrNull() ?: invalidArgument("expected an integer") }, Int::toString),
        codec(Long::class, CommandSyntax.LONG, { it.toLongOrNull() ?: invalidArgument("expected a whole number") }, Long::toString),
        codec(Float::class, CommandSyntax.FLOAT, { it.toFloatOrNull() ?: invalidArgument("expected a number") }, Float::toString),
        codec(Double::class, CommandSyntax.DOUBLE, { it.toDoubleOrNull() ?: invalidArgument("expected a number") }, Double::toString),
        codec(Boolean::class, CommandSyntax.BOOLEAN, ::parseBoolean, Boolean::toString),
        codec(UUID::class, CommandSyntax.WORD, { raw -> runCatching { UUID.fromString(raw) }.getOrElse { invalidArgument("expected a UUID") } }, UUID::toString),
    ).associateBy(CommandArgumentCodec<*>::type)

    fun find(type: KClass<*>): CommandArgumentCodec<*>? = codecs[type]

    private fun parseBoolean(raw: String): Boolean = when (raw.lowercase()) {
        "true", "yes", "on", "1" -> true
        "false", "no", "off", "0" -> false
        else -> invalidArgument("expected true or false")
    }

    private fun <T : Any> codec(
        type: KClass<T>,
        syntax: CommandSyntax,
        decode: (String) -> T,
        encode: (T) -> String,
    ): CommandArgumentCodec<T> = object : CommandArgumentCodec<T> {
        override val type: KClass<T> = type
        override val syntax: CommandSyntax = syntax

        override suspend fun resolve(
            raw: String,
            invocation: CommandInvocation,
            dependencies: DependencyResolver,
        ): T = decode(raw)

        override fun encode(value: T): String = encode(value)
    }
}
