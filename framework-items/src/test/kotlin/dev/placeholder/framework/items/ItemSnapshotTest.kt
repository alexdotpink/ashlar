package dev.placeholder.framework.items

import org.bukkit.Material
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal class ItemSnapshotTest {
    @Test
    fun `stable envelope preserves exact native bytes`() {
        val snapshot = nativeSnapshot(Material.DIAMOND, 7, byteArrayOf(8, 6, 7, 5, 3, 0, 9))

        val first = snapshot.encode()
        val second = snapshot.encode()
        val decoded = assertIs<ItemSnapshotDecode.Found>(ItemSnapshot.decode(first)).snapshot

        assertContentEquals(first, second)
        assertEquals(snapshot, decoded)
        assertEquals(snapshot.fingerprint(), decoded.fingerprint())
    }

    @Test
    fun `checksum tampering has a structured outcome`() {
        val encoded = nativeSnapshot(Material.PAPER, 1, byteArrayOf(1, 2, 3)).encode()
        encoded[encoded.lastIndex] = (encoded.last() + 1).toByte()

        assertIs<ItemSnapshotDecode.Corrupt>(ItemSnapshot.decode(encoded))
    }

    @Test
    fun `malformed and unsupported envelopes are distinct`() {
        assertIs<ItemSnapshotDecode.Malformed>(ItemSnapshot.decode(byteArrayOf(1, 2, 3)))

        val encoded = nativeSnapshot(Material.PAPER, 1, byteArrayOf(1)).encode()
        encoded[4] = 99
        val unsupported = assertIs<ItemSnapshotDecode.UnsupportedVersion>(ItemSnapshot.decode(encoded))
        assertEquals(99, unsupported.version)
    }

    @Test
    fun `snapshot equality is byte exact`() {
        val one = nativeSnapshot(Material.PAPER, 1, byteArrayOf(1))
        val same = nativeSnapshot(Material.PAPER, 1, byteArrayOf(1))
        val different = nativeSnapshot(Material.PAPER, 1, byteArrayOf(2))

        assertEquals(one, same)
        assertEquals(one.hashCode(), same.hashCode())
        assertNotEquals(one, different)
    }

    @Test
    fun `detached semantics support server free transactions`() {
        val first = ItemSnapshot.detached(Material.STONE, amount = 40, maximumAmount = 64, stackabilityKey = "plain")
        val second = ItemSnapshot.detached(Material.STONE, amount = 2, maximumAmount = 64, stackabilityKey = "plain")
        val named = ItemSnapshot.detached(Material.STONE, amount = 2, maximumAmount = 64, stackabilityKey = "named")

        assertTrue(first.stackableWith(second))
        assertFalse(first.stackableWith(named))
        assertEquals(12, first.withAmount(12).amount)
        assertEquals(13, first.edit { amount = 13 }.amount)
        assertFalse(first.hasNativeData)
        assertEquals(first, assertIs<ItemSnapshotDecode.Found>(ItemSnapshot.decode(first.encode())).snapshot)
    }

    private fun nativeSnapshot(material: Material, amount: Int, bytes: ByteArray): ItemSnapshot =
        ItemSnapshot(material, amount, 64, bytes.sha256(), bytes)
}
