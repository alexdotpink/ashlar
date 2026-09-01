package pink.alex.ashlar.items

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import org.bukkit.NamespacedKey
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/** Typed, versioned identity and payload definition for one custom item kind. */
public class CustomItemDefinition<T> internal constructor(
    /** Durable namespaced identity embedded in every item. */
    public val id: NamespacedKey,
    /** Current typed payload schema. */
    public val version: Int,
    private val codec: CustomItemCodec<T>,
    private val renderer: (T) -> ItemSpec,
    private val migrations: Map<Int, CustomItemMigration<T>>,
    private val integrity: CustomItemIntegrity?,
    /** Maximum canonical payload bytes accepted by this definition. */
    public val maximumPayloadBytes: Int,
) {
    /** Renders [data], embeds its durable envelope, and returns a mutable Paper stack. */
    public fun create(
        data: T,
        presentation: ItemPresentation = ItemPresentation.Neutral,
        context: ItemPresentationContext = ItemPresentationContext(),
    ): ItemStack {
        val stack = Items.materialize(renderer(data), presentation, context)
        val presentationBytes = if (integrity?.includePresentation == true) {
            canonicalNativeBytes(stack.serializeAsBytes())
        } else null
        val envelopeBytes = encodeEnvelope(data, presentationBytes)
        return stack.also {
            stack.editPersistentDataContainer {
                it.set(ENVELOPE_KEY, PersistentDataType.BYTE_ARRAY, envelopeBytes)
            }
        }
    }

    internal fun encodeEnvelope(data: T, presentationBytes: ByteArray? = null): ByteArray {
        val payload = codec.encode(data)
        require(payload.size <= maximumPayloadBytes) {
            "Custom item '$id' payload is ${payload.size} bytes; maximum is $maximumPayloadBytes"
        }
        val signsPresentation = integrity?.includePresentation == true
        require(!signsPresentation || presentationBytes != null) {
            "Custom item '$id' requires presentation bytes for integrity"
        }
        val unsigned = CustomItemEnvelope(id.asString(), version, codec.id, payload, signsPresentation, null)
        val signature = integrity?.keyring?.sign(unsigned.integrityMessage(presentationBytes))
        return unsigned.copy(signature = signature).encode()
    }

    /** Captures a newly created custom item as an exact snapshot. */
    public fun snapshot(
        data: T,
        presentation: ItemPresentation = ItemPresentation.Neutral,
        context: ItemPresentationContext = ItemPresentationContext(),
    ): ItemSnapshot = Items.capture(create(data, presentation, context))

    /** Reads this custom item from a live stack without mutating it. */
    public fun read(stack: ItemStack?): CustomItemRead<T> {
        if (stack == null || stack.isEmpty) return CustomItemRead.NotThisItem
        val bytes = stack.persistentDataContainer.get(ENVELOPE_KEY, PersistentDataType.BYTE_ARRAY)
            ?: stack.persistentDataContainer.get(LEGACY_ENVELOPE_KEY, PersistentDataType.BYTE_ARRAY)
            ?: return CustomItemRead.NotThisItem
        val presentationBytes = if (integrity?.includePresentation == true) {
            stack.clone().also { copy ->
                copy.editPersistentDataContainer {
                    it.remove(ENVELOPE_KEY)
                    it.remove(LEGACY_ENVELOPE_KEY)
                }
            }.let(ItemStack::serializeAsBytes).let(::canonicalNativeBytes)
        } else null
        return readEnvelope(bytes, presentationBytes)
    }

    /** Reads this custom item from an exact snapshot. */
    public fun read(snapshot: ItemSnapshot): CustomItemRead<T> = read(Items.materialize(snapshot))

    /** Returns typed data only when [stack] is this valid item. */
    public fun readOrNull(stack: ItemStack?): T? = (read(stack) as? CustomItemRead.Found)?.data

    internal fun readEnvelope(bytes: ByteArray, presentationBytes: ByteArray? = null): CustomItemRead<T> {
        val envelope = when (val decoded = CustomItemEnvelope.decode(bytes)) {
            is EnvelopeDecode.Found -> decoded.envelope
            is EnvelopeDecode.Invalid -> return CustomItemRead.InvalidData(decoded.message)
        }
        if (envelope.id != id.asString()) return CustomItemRead.NotThisItem

        if (integrity == null && envelope.signature != null) {
            return CustomItemRead.InvalidSignature("Custom item '$id' is signed but this definition has no keyring")
        }
        if (integrity != null) {
            if (envelope.signsPresentation != integrity.includePresentation) {
                return CustomItemRead.InvalidSignature("Custom item '$id' integrity scope does not match its definition")
            }
            val signature = envelope.signature
                ?: return CustomItemRead.InvalidSignature("Custom item '$id' is missing its signature")
            if (envelope.signsPresentation && presentationBytes == null) {
                return CustomItemRead.InvalidSignature("Custom item '$id' requires presentation verification")
            }
            if (!integrity.keyring.verify(signature, envelope.copy(signature = null).integrityMessage(presentationBytes))) {
                return CustomItemRead.InvalidSignature("Custom item '$id' signature is invalid or uses an unknown key")
            }
        }

        if (envelope.payload.size > maximumPayloadBytes) {
            return CustomItemRead.InvalidData("Custom item '$id' payload exceeds $maximumPayloadBytes bytes")
        }
        if (envelope.version > version) {
            return CustomItemRead.UnsupportedVersion(envelope.version, version)
        }

        return try {
            if (envelope.version == version) {
                if (envelope.codecId != codec.id) {
                    CustomItemRead.InvalidData(
                        "Custom item '$id' uses codec '${envelope.codecId}', expected '${codec.id}'",
                    )
                } else {
                    CustomItemRead.Found(codec.decode(envelope.payload), version, migrated = false)
                }
            } else {
                val migration = migrations[envelope.version]
                    ?: return CustomItemRead.MigrationFailed(
                        envelope.version,
                        "No migration from version ${envelope.version} to $version for '$id'",
                    )
                if (envelope.codecId != migration.codecId) {
                    CustomItemRead.MigrationFailed(
                        envelope.version,
                        "Version ${envelope.version} uses codec '${envelope.codecId}', expected '${migration.codecId}'",
                    )
                } else {
                    CustomItemRead.Found(migration.migrate(envelope.payload), envelope.version, migrated = true)
                }
            }
        } catch (failure: Exception) {
            if (envelope.version == version) {
                CustomItemRead.InvalidData("Could not decode custom item '$id': ${failure.message.orEmpty()}")
            } else {
                CustomItemRead.MigrationFailed(
                    envelope.version,
                    "Could not migrate custom item '$id': ${failure.message.orEmpty()}",
                )
            }
        }
    }

    public companion object {
        /** Shared persistent key used for the framed identity envelope. */
        public val ENVELOPE_KEY: NamespacedKey = NamespacedKey("ashlar", "custom_item")

        private val LEGACY_ENVELOPE_KEY: NamespacedKey = NamespacedKey("framework", "custom_item")
    }
}

/** Structured custom-item recognition result. */
public sealed interface CustomItemRead<out T> {
    /** The stack is this definition and its payload is valid. */
    public data class Found<T>(
        public val data: T,
        public val sourceVersion: Int,
        public val migrated: Boolean,
    ) : CustomItemRead<T>

    /** The stack is ordinary or belongs to another custom-item definition. */
    public data object NotThisItem : CustomItemRead<Nothing>

    /** The envelope or current payload is malformed. */
    public data class InvalidData(public val problem: String) : CustomItemRead<Nothing>

    /** The item was written by a newer definition. */
    public data class UnsupportedVersion(
        public val found: Int,
        public val supported: Int,
    ) : CustomItemRead<Nothing>

    /** A known old version could not be converted. */
    public data class MigrationFailed(
        public val fromVersion: Int,
        public val problem: String,
    ) : CustomItemRead<Nothing>

    /** Required authenticity verification failed. */
    public data class InvalidSignature(public val problem: String) : CustomItemRead<Nothing>
}

/** Builds one [CustomItemDefinition]. */
public class CustomItemBuilder<T> internal constructor(private val id: NamespacedKey) {
    /** Current schema version written by this definition. */
    public var version: Int = 1

    /** Maximum canonical payload size accepted on create and read. */
    public var maximumPayloadBytes: Int = 32 * 1024

    private var codec: CustomItemCodec<T>? = null
    private var renderer: ((T) -> ItemSpec)? = null
    private val migrations: MutableMap<Int, CustomItemMigration<T>> = linkedMapOf()
    private var integrity: CustomItemIntegrity? = null

    /** Selects the framework's canonical Kotlin Serialization JSON protocol. */
    public fun data(serializer: KSerializer<T>) {
        data(KotlinJsonItemCodec(serializer))
    }

    /** Selects a custom JSON protocol with an explicit durable [codecId]. */
    public fun data(serializer: KSerializer<T>, json: Json, codecId: String) {
        data(KotlinJsonItemCodec(serializer, json, codecId))
    }

    /** Selects an existing deterministic protocol codec. */
    public fun data(codec: CustomItemCodec<T>) {
        requireValidCodecId(codec.id)
        this.codec = codec
    }

    /** Supplies the authored visual item for one typed payload. */
    public fun render(block: (T) -> ItemSpec) {
        renderer = block
    }

    /** Converts one prior schema directly into the current typed value. */
    public fun <O> migrate(
        fromVersion: Int,
        codec: CustomItemCodec<O>,
        transform: (O) -> T,
    ) {
        require(fromVersion > 0) { "Migration version must be positive" }
        requireValidCodecId(codec.id)
        require(
            migrations.put(
                fromVersion,
                CustomItemMigration(codec.id) { bytes -> transform(codec.decode(bytes)) },
            ) == null,
        ) {
            "Migration from version $fromVersion is already defined for '$id'"
        }
    }

    /** Enables HMAC-SHA256 integrity and rotation support. */
    public fun integrity(keyring: HmacKeyring, includePresentation: Boolean = false) {
        integrity = CustomItemIntegrity(keyring, includePresentation)
    }

    internal fun build(): CustomItemDefinition<T> {
        require(version > 0) { "Custom item version must be positive" }
        require(maximumPayloadBytes in 1..CustomItemEnvelope.MAX_PAYLOAD_BYTES) {
            "Maximum payload must be from 1 through ${CustomItemEnvelope.MAX_PAYLOAD_BYTES} bytes"
        }
        require(migrations.keys.all { it < version }) { "Migrations must start before current version $version" }
        return CustomItemDefinition(
            id = id,
            version = version,
            codec = checkNotNull(codec) { "Custom item '$id' has no data codec" },
            renderer = checkNotNull(renderer) { "Custom item '$id' has no renderer" },
            migrations = migrations.toMap(),
            integrity = integrity,
            maximumPayloadBytes = maximumPayloadBytes,
        )
    }
}

/** Declares one typed custom-item identity. */
public fun <T> customItem(
    id: NamespacedKey,
    block: CustomItemBuilder<T>.() -> Unit,
): CustomItemDefinition<T> = CustomItemBuilder<T>(id).apply(block).build()

internal class CustomItemMigration<T>(
    val codecId: String,
    val migrate: (ByteArray) -> T,
)

private data class CustomItemEnvelope(
    val id: String,
    val version: Int,
    val codecId: String,
    val payload: ByteArray,
    val signsPresentation: Boolean,
    val signature: ItemSignature?,
) {
    fun signingBytes(): ByteArray = encode(includeSignature = false)

    fun integrityMessage(presentationBytes: ByteArray?): ByteArray {
        val envelope = signingBytes()
        if (!signsPresentation) return envelope
        val presentation = checkNotNull(presentationBytes)
        return ByteArrayOutputStream(envelope.size + presentation.size + 4).also { output ->
            DataOutputStream(output).use { data ->
                data.writeInt(envelope.size)
                data.write(envelope)
                data.write(presentation)
            }
        }.toByteArray()
    }

    fun encode(): ByteArray = encode(includeSignature = true)

    private fun encode(includeSignature: Boolean): ByteArray {
        val output = ByteArrayOutputStream(payload.size + 128)
        DataOutputStream(output).use { data ->
            data.write(MAGIC)
            data.writeByte(FORMAT_VERSION)
            data.writeUTF(id)
            data.writeInt(version)
            data.writeUTF(codecId)
            data.writeInt(payload.size)
            data.write(payload)
            data.writeBoolean(signsPresentation)
            val included = signature.takeIf { includeSignature }
            data.writeBoolean(included != null)
            if (included != null) {
                data.writeUTF(included.keyId)
                data.writeInt(included.bytes.size)
                data.write(included.bytes)
            }
        }
        return output.toByteArray()
    }

    override fun equals(other: Any?): Boolean =
        other is CustomItemEnvelope && id == other.id && version == other.version &&
            codecId == other.codecId && payload.contentEquals(other.payload)

    override fun hashCode(): Int = 31 * id.hashCode() + payload.contentHashCode()

    companion object {
        const val MAX_PAYLOAD_BYTES: Int = 1024 * 1024
        private val MAGIC = byteArrayOf('F'.code.toByte(), 'C'.code.toByte(), 'I'.code.toByte(), 'T'.code.toByte())
        private const val FORMAT_VERSION = 1

        fun decode(bytes: ByteArray): EnvelopeDecode = try {
            require(bytes.size <= MAX_PAYLOAD_BYTES + 512) { "Custom-item envelope exceeds size limit" }
            DataInputStream(ByteArrayInputStream(bytes)).use { data ->
                val magic = ByteArray(MAGIC.size).also(data::readFully)
                require(magic.contentEquals(MAGIC)) { "Custom-item envelope magic is invalid" }
                val format = data.readUnsignedByte()
                require(format == FORMAT_VERSION) { "Unsupported custom-item envelope format $format" }
                val id = data.readUTF()
                val version = data.readInt()
                require(version > 0) { "Invalid custom-item schema version $version" }
                val codecId = data.readUTF()
                val payloadSize = data.readInt()
                require(payloadSize in 0..MAX_PAYLOAD_BYTES) { "Invalid custom-item payload size $payloadSize" }
                val payload = ByteArray(payloadSize).also(data::readFully)
                val signsPresentation = data.readBoolean()
                val signature = if (data.readBoolean()) {
                    val keyId = data.readUTF()
                    val signatureSize = data.readInt()
                    require(signatureSize in 1..128) { "Invalid custom-item signature size $signatureSize" }
                    ItemSignature(keyId, ByteArray(signatureSize).also(data::readFully))
                } else null
                require(data.available() == 0) { "Trailing bytes after custom-item envelope" }
                EnvelopeDecode.Found(CustomItemEnvelope(id, version, codecId, payload, signsPresentation, signature))
            }
        } catch (failure: Exception) {
            EnvelopeDecode.Invalid(failure.message ?: failure::class.simpleName.orEmpty())
        }
    }
}

internal data class CustomItemIntegrity(
    val keyring: HmacKeyring,
    val includePresentation: Boolean,
)

private sealed interface EnvelopeDecode {
    data class Found(val envelope: CustomItemEnvelope) : EnvelopeDecode
    data class Invalid(val message: String) : EnvelopeDecode
}
