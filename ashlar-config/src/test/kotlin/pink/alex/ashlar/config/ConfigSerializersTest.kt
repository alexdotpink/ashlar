package pink.alex.ashlar.config

import kotlinx.serialization.json.Json
import net.kyori.adventure.key.Key
import org.bukkit.NamespacedKey
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class ConfigSerializersTest {
    private val json = Json

    @Test
    fun `duration serializer uses concise human-readable units`() {
        assertEquals("\"3s\"", json.encodeToString(ConfigDurationSerializer, 3.seconds))
        assertEquals(250.milliseconds, json.decodeFromString(ConfigDurationSerializer, "\"250ms\""))
    }

    @Test
    fun `stable identity serializers round trip canonical strings`() {
        val uuid = UUID.fromString("123e4567-e89b-12d3-a456-426614174000")
        assertEquals(uuid, roundTrip(ConfigUuidSerializer, uuid))

        val key = Key.key("ashlar", "waypoint")
        assertEquals(key, roundTrip(ConfigKeySerializer, key))

        val namespaced = NamespacedKey("ashlar", "waypoint")
        assertEquals(namespaced, roundTrip(ConfigNamespacedKeySerializer, namespaced))
    }

    private fun <T : Any> roundTrip(serializer: kotlinx.serialization.KSerializer<T>, value: T): T =
        json.decodeFromString(serializer, json.encodeToString(serializer, value))
}
