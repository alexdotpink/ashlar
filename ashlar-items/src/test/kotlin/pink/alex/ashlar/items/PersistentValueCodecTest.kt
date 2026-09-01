package pink.alex.ashlar.items

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

internal class PersistentValueCodecTest {
    @Test
    fun `built in codecs round trip canonical values`() {
        val uuid = UUID.fromString("1ed2dfa7-07df-4d36-8bc0-436b500bc3f2")
        assertEquals("héllo", PersistentValueCodecs.StringUtf8.decode(PersistentValueCodecs.StringUtf8.encode("héllo")))
        assertEquals(42, PersistentValueCodecs.Int32.decode(PersistentValueCodecs.Int32.encode(42)))
        assertEquals(Long.MIN_VALUE, PersistentValueCodecs.Int64.decode(PersistentValueCodecs.Int64.encode(Long.MIN_VALUE)))
        assertEquals(uuid, PersistentValueCodecs.Uuid.decode(PersistentValueCodecs.Uuid.encode(uuid)))
        assertContentEquals(byteArrayOf(1, 2), PersistentValueCodecs.Bytes.decode(byteArrayOf(1, 2)))
    }
}
