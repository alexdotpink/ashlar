package dev.placeholder.framework.items

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

/** Small typed binary codec seam for custom-item payloads. */
public interface CustomItemCodec<T> {
    /** Stable protocol identifier recorded in custom-item envelopes. */
    public val id: String

    /** Produces deterministic canonical bytes for [value]. */
    public fun encode(value: T): ByteArray

    /** Decodes bytes previously produced by this codec. */
    public fun decode(bytes: ByteArray): T
}

/** Deterministic Kotlin Serialization JSON codec used by default. */
public class KotlinJsonItemCodec<T>(
    private val serializer: KSerializer<T>,
    private val json: Json = DefaultItemJson,
) : CustomItemCodec<T> {
    override val id: String = "kotlin-json-v1"

    override fun encode(value: T): ByteArray =
        json.encodeToString(serializer, value).encodeToByteArray()

    override fun decode(bytes: ByteArray): T =
        json.decodeFromString(serializer, bytes.decodeToString())
}

/** Canonical JSON settings used for custom-item data. Do not mutate this shared instance. */
public val DefaultItemJson: Json = Json {
    encodeDefaults = true
    explicitNulls = true
    ignoreUnknownKeys = false
    isLenient = false
    allowStructuredMapKeys = false
    classDiscriminator = "type"
}
