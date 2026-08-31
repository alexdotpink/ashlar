package dev.placeholder.framework.items

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

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
    codecId: String = DEFAULT_CODEC_ID,
) : CustomItemCodec<T> {
    override val id: String = requireValidCodecId(codecId)

    init {
        require(json === DefaultItemJson || codecId != DEFAULT_CODEC_ID) {
            "A custom Json configuration requires an explicit codec id"
        }
    }

    override fun encode(value: T): ByteArray =
        json.encodeToString(JsonElement.serializer(), canonicalize(json.encodeToJsonElement(serializer, value)))
            .encodeToByteArray()

    override fun decode(bytes: ByteArray): T =
        json.decodeFromString(serializer, bytes.decodeToString())

    public companion object {
        /** Protocol identity of [DefaultItemJson] plus recursive object-key ordering. */
        public const val DEFAULT_CODEC_ID: String = "kotlin-json-canonical-v1"
    }
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

private fun canonicalize(element: JsonElement): JsonElement = when (element) {
    is JsonObject -> JsonObject(
        element.entries.sortedBy(Map.Entry<String, JsonElement>::key)
            .associateTo(linkedMapOf()) { (key, value) -> key to canonicalize(value) },
    )
    is JsonArray -> JsonArray(element.map(::canonicalize))
    else -> element
}

internal fun requireValidCodecId(codecId: String): String {
    require(codecId.matches(CODEC_ID_PATTERN)) {
        "Codec id '$codecId' must start with a lowercase letter or digit and contain only lowercase letters, " +
            "digits, dots, underscores, or hyphens"
    }
    require(codecId.encodeToByteArray().size <= MAX_CODEC_ID_BYTES) {
        "Codec id '$codecId' exceeds $MAX_CODEC_ID_BYTES UTF-8 bytes"
    }
    return codecId
}

private val CODEC_ID_PATTERN: Regex = Regex("[a-z0-9][a-z0-9._-]*")
private const val MAX_CODEC_ID_BYTES: Int = 128
