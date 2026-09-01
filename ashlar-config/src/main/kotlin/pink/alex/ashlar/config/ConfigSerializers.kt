package pink.alex.ashlar.config

import io.papermc.paper.registry.RegistryKey
import io.papermc.paper.registry.TypedKey
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import net.kyori.adventure.key.Key
import org.bukkit.NamespacedKey
import java.util.UUID
import kotlin.time.Duration

/** Human-readable Kotlin duration serializer, for example `3s` or `250ms`. */
public object ConfigDurationSerializer : StringConfigSerializer<Duration>("pink.alex.ashlar.config.Duration") {
    override fun format(value: Duration): String = value.toString()
    override fun parse(value: String): Duration = Duration.parse(value)
}

/** Canonical lowercase UUID serializer. */
public object ConfigUuidSerializer : StringConfigSerializer<UUID>("pink.alex.ashlar.config.UUID") {
    override fun format(value: UUID): String = value.toString()
    override fun parse(value: String): UUID = UUID.fromString(value)
}

/** Canonical `namespace:value` Adventure key serializer. */
public object ConfigKeySerializer : StringConfigSerializer<Key>("pink.alex.ashlar.config.Key") {
    override fun format(value: Key): String = value.asString()
    override fun parse(value: String): Key = Key.key(value)
}

/** Canonical `namespace:value` Bukkit key serializer. */
public object ConfigNamespacedKeySerializer : StringConfigSerializer<NamespacedKey>(
    "pink.alex.ashlar.config.NamespacedKey",
) {
    override fun format(value: NamespacedKey): String = value.asString()
    override fun parse(value: String): NamespacedKey =
        NamespacedKey.fromString(value) ?: throw IllegalArgumentException("Invalid namespaced key")
}

/** Serializer for a stable entry key within one explicit Paper registry. */
public class ConfigTypedKeySerializer<T : Any>(
    private val registry: RegistryKey<T>,
) : StringConfigSerializer<TypedKey<T>>("pink.alex.ashlar.config.TypedKey") {
    override fun format(value: TypedKey<T>): String {
        require(value.registryKey() == registry) { "Typed key belongs to a different registry" }
        return value.key().asString()
    }

    override fun parse(value: String): TypedKey<T> = registry.typedKey(Key.key(value))
}

/** Base for stable values represented by one redaction-safe string scalar. */
public abstract class StringConfigSerializer<T : Any>(
    serialName: String,
) : KSerializer<T> {
    final override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor(serialName, PrimitiveKind.STRING)

    final override fun serialize(encoder: Encoder, value: T) {
        encoder.encodeString(format(value))
    }

    final override fun deserialize(decoder: Decoder): T {
        val encoded = decoder.decodeString()
        return try {
            parse(encoded)
        } catch (failure: IllegalArgumentException) {
            throw SerializationException("Invalid $descriptor", failure)
        }
    }

    protected abstract fun format(value: T): String
    protected abstract fun parse(value: String): T
}
