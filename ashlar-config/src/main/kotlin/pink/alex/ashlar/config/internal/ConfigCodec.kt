package pink.alex.ashlar.config.internal

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.longOrNull
import pink.alex.ashlar.config.ConfigKeyPath
import pink.alex.ashlar.config.ConfigProblem
import pink.alex.ashlar.config.ConfigProblemCategory
import pink.alex.ashlar.config.ConfigSourceLocation
import pink.alex.ashlar.config.ConfigValue

internal object ConfigCodec {
    private val json = Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = false
        isLenient = false
        allowSpecialFloatingPointValues = false
    }

    fun <T : Any> encode(
        serializer: KSerializer<T>,
        value: T,
        keyNames: Map<ConfigKeyPath, String> = emptyMap(),
    ): ConfigValue.ObjectValue {
        val encoded = json.encodeToJsonElement(serializer, value).toConfigValue()
        check(encoded is ConfigValue.ObjectValue) {
            "Configuration serializer '${serializer.descriptor.serialName}' must encode an object"
        }
        return encoded.externalKeys(serializer.descriptor, emptyList(), keyNames) as ConfigValue.ObjectValue
    }

    fun <T : Any> decode(
        serializer: KSerializer<T>,
        value: ConfigValue.ObjectValue,
        keyNames: Map<ConfigKeyPath, String> = emptyMap(),
    ): T = json.decodeFromJsonElement(
        serializer,
        value.descriptorKeys(serializer.descriptor, emptyList(), keyNames).toJsonElement(),
    )

    fun unknownKeys(
        serializer: KSerializer<*>,
        value: ConfigValue.ObjectValue,
        path: String,
        keyNames: Map<ConfigKeyPath, String> = emptyMap(),
        locate: (ConfigKeyPath) -> ConfigSourceLocation? = { null },
    ): List<ConfigProblem> = buildList {
        collectUnknown(serializer.descriptor, value, emptyList(), ConfigKeyPath(), path, keyNames, locate, this)
    }

    private fun collectUnknown(
        descriptor: SerialDescriptor,
        value: ConfigValue,
        descriptorPath: List<String>,
        keyPath: ConfigKeyPath,
        documentPath: String,
        keyNames: Map<ConfigKeyPath, String>,
        locate: (ConfigKeyPath) -> ConfigSourceLocation?,
        problems: MutableList<ConfigProblem>,
    ) {
        when {
            descriptor.kind == StructureKind.CLASS && value is ConfigValue.ObjectValue -> {
                val known = (0 until descriptor.elementsCount).associateBy { index ->
                    val name = descriptor.getElementName(index)
                    keyNames[ConfigKeyPath(descriptorPath + name)] ?: name.toKebabCase()
                }
                value.entries.forEach { (name, child) ->
                    val childPath = ConfigKeyPath(keyPath.segments + name)
                    val index = known[name]
                    if (index == null) {
                        val nearest = known.keys.minByOrNull { candidate -> editDistance(name, candidate) }
                            ?.takeIf { candidate -> editDistance(name, candidate) <= 3 }
                        problems += ConfigProblem(
                            path = documentPath,
                            key = childPath,
                            category = ConfigProblemCategory.UNKNOWN_KEY,
                            message = "Unknown key '$name'",
                            location = locate(childPath),
                            nearestKnownKey = nearest?.let { ConfigKeyPath(keyPath.segments + it) },
                        )
                    } else {
                        collectUnknown(
                            descriptor.getElementDescriptor(index),
                            child,
                            descriptorPath + descriptor.getElementName(index),
                            childPath,
                            documentPath,
                            keyNames,
                            locate,
                            problems,
                        )
                    }
                }
            }

            descriptor.kind == StructureKind.LIST && value is ConfigValue.ArrayValue -> {
                val element = descriptor.getElementDescriptor(0)
                value.values.forEachIndexed { index, child ->
                    collectUnknown(
                        element,
                        child,
                        descriptorPath,
                        ConfigKeyPath(keyPath.segments + index.toString()),
                        documentPath,
                        keyNames,
                        locate,
                        problems,
                    )
                }
            }

            descriptor.kind == StructureKind.MAP && value is ConfigValue.ObjectValue -> {
                val element = descriptor.getElementDescriptor(1)
                value.entries.forEach { (name, child) ->
                    collectUnknown(
                        element,
                        child,
                        descriptorPath,
                        ConfigKeyPath(keyPath.segments + name),
                        documentPath,
                        keyNames,
                        locate,
                        problems,
                    )
                }
            }
        }
    }

    private fun ConfigValue.externalKeys(
        descriptor: SerialDescriptor,
        descriptorPath: List<String>,
        keyNames: Map<ConfigKeyPath, String>,
    ): ConfigValue = when {
        descriptor.kind == StructureKind.CLASS && this is ConfigValue.ObjectValue -> {
            val indexes = (0 until descriptor.elementsCount).associateBy(descriptor::getElementName)
            ConfigValue.ObjectValue(entries.map { (name, child) ->
                val index = indexes[name]
                if (index == null) name to child else {
                    val nextPath = descriptorPath + name
                    val external = keyNames[ConfigKeyPath(nextPath)] ?: name.toKebabCase()
                    external to child.externalKeys(descriptor.getElementDescriptor(index), nextPath, keyNames)
                }
            }.toMap())
        }
        descriptor.kind == StructureKind.LIST && this is ConfigValue.ArrayValue -> ConfigValue.ArrayValue(
            values.map { child -> child.externalKeys(descriptor.getElementDescriptor(0), descriptorPath, keyNames) },
        )
        descriptor.kind == StructureKind.MAP && this is ConfigValue.ObjectValue -> ConfigValue.ObjectValue(
            entries.mapValues { (_, child) ->
                child.externalKeys(descriptor.getElementDescriptor(1), descriptorPath, keyNames)
            },
        )
        else -> this
    }

    private fun ConfigValue.descriptorKeys(
        descriptor: SerialDescriptor,
        descriptorPath: List<String>,
        keyNames: Map<ConfigKeyPath, String>,
    ): ConfigValue = when {
        descriptor.kind == StructureKind.CLASS && this is ConfigValue.ObjectValue -> {
            val indexes = (0 until descriptor.elementsCount).associateBy { index ->
                val name = descriptor.getElementName(index)
                keyNames[ConfigKeyPath(descriptorPath + name)] ?: name.toKebabCase()
            }
            ConfigValue.ObjectValue(entries.map { (external, child) ->
                val index = indexes[external]
                if (index == null) external to child else {
                    val name = descriptor.getElementName(index)
                    name to child.descriptorKeys(
                        descriptor.getElementDescriptor(index),
                        descriptorPath + name,
                        keyNames,
                    )
                }
            }.toMap())
        }
        descriptor.kind == StructureKind.LIST && this is ConfigValue.ArrayValue -> ConfigValue.ArrayValue(
            values.map { child -> child.descriptorKeys(descriptor.getElementDescriptor(0), descriptorPath, keyNames) },
        )
        descriptor.kind == StructureKind.MAP && this is ConfigValue.ObjectValue -> ConfigValue.ObjectValue(
            entries.mapValues { (_, child) ->
                child.descriptorKeys(descriptor.getElementDescriptor(1), descriptorPath, keyNames)
            },
        )
        else -> this
    }

    private fun JsonElement.toConfigValue(): ConfigValue = when (this) {
        JsonNull -> ConfigValue.NullValue
        is JsonObject -> ConfigValue.ObjectValue(
            this@toConfigValue.entries.associate { (key, value) -> key to value.toConfigValue() },
        )
        is JsonArray -> ConfigValue.ArrayValue(this@toConfigValue.map { value -> value.toConfigValue() })
        is JsonPrimitive -> when {
            isString -> ConfigValue.StringValue(content)
            booleanOrNull != null -> ConfigValue.BooleanValue(booleanOrNull!!)
            longOrNull != null -> ConfigValue.IntegerValue(longOrNull!!)
            else -> ConfigValue.DecimalValue(content)
        }
    }

    private fun ConfigValue.toJsonElement(): JsonElement = when (this) {
        ConfigValue.NullValue -> JsonNull
        is ConfigValue.BooleanValue -> JsonPrimitive(value)
        is ConfigValue.StringValue -> JsonPrimitive(value)
        is ConfigValue.IntegerValue -> JsonPrimitive(value)
        is ConfigValue.DecimalValue -> JsonPrimitive(value)
        is ConfigValue.ArrayValue -> JsonArray(values.map { value -> value.toJsonElement() })
        is ConfigValue.ObjectValue -> JsonObject(entries.mapValues { (_, value) -> value.toJsonElement() })
    }

    private fun String.toKebabCase(): String =
        replace(Regex("([A-Z]+)([A-Z][a-z])"), "$1-$2")
            .replace(Regex("([a-z0-9])([A-Z])"), "$1-$2")
            .lowercase()

    private fun editDistance(left: String, right: String): Int {
        var previous = IntArray(right.length + 1) { it }
        left.forEachIndexed { leftIndex, leftCharacter ->
            val current = IntArray(right.length + 1)
            current[0] = leftIndex + 1
            right.forEachIndexed { rightIndex, rightCharacter ->
                current[rightIndex + 1] = minOf(
                    current[rightIndex] + 1,
                    previous[rightIndex + 1] + 1,
                    previous[rightIndex] + if (leftCharacter == rightCharacter) 0 else 1,
                )
            }
            previous = current
        }
        return previous.last()
    }
}
