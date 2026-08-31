package dev.placeholder.framework.items

import org.bukkit.Material
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

internal class ItemSpecTest {
    @Test
    fun `item builds an immutable recipe and edit leaves source unchanged`() {
        val original = item(Material.COMPASS) {
            amount = 2
        }
        val edited = original.edit {
            amount = 3
        }

        assertEquals(Material.COMPASS, original.material)
        assertEquals(2, original.amount)
        assertEquals(3, edited.amount)
        assertNotSame(original, edited)
        assertTrue(original.toString().contains("changes=0"))
    }

    @Test
    fun `equivalent specs compare structurally across renders`() {
        fun render(label: String) = item(Material.COMPASS) {
            name = net.kyori.adventure.text.Component.text(label)
            persistent(org.bukkit.NamespacedKey("test", "value"), byteArrayOf(1, 2), PersistentValueCodecs.Bytes)
            paper("advanced") { }
        }

        assertEquals(render("same"), render("same"))
        assertEquals(render("same").hashCode(), render("same").hashCode())
        assertNotEquals(render("same"), render("different"))
    }

    @Test
    fun `amount and material validation fail at definition boundary`() {
        assertFailsWith<IllegalArgumentException> { item(Material.STONE) { amount = 100 } }
        assertFailsWith<IllegalArgumentException> { item(Material.AIR) }
    }

    @Test
    fun `persistent values encode eagerly and enforce the bound`() {
        val source = byteArrayOf(1, 2, 3)
        val spec = item(Material.PAPER) {
            persistent(org.bukkit.NamespacedKey("test", "payload"), source, PersistentValueCodecs.Bytes)
        }
        source[0] = 99

        assertTrue(spec.toString().contains("changes=1"))
        assertFailsWith<IllegalArgumentException> {
            item(Material.PAPER) {
                persistent(
                    org.bukkit.NamespacedKey("test", "large"),
                    ByteArray(ItemSpecBuilder.MAX_PERSISTENT_VALUE_BYTES + 1),
                    PersistentValueCodecs.Bytes,
                )
            }
        }
    }
}
