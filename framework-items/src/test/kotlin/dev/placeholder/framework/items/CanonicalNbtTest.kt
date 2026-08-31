package dev.placeholder.framework.items

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse

class CanonicalNbtTest {
    @Test
    fun `compound key order does not change native item identity`() {
        val first = compressedCompound(listOf("id" to "minecraft:compass", "custom" to "fixture"))
        val reversed = compressedCompound(listOf("custom" to "fixture", "id" to "minecraft:compass"))

        assertFalse(first.contentEquals(reversed))
        assertContentEquals(canonicalNativeBytes(first), canonicalNativeBytes(reversed))
    }

    private fun compressedCompound(entries: List<Pair<String, String>>): ByteArray =
        ByteArrayOutputStream().also { bytes ->
            GZIPOutputStream(bytes).use { gzip ->
                DataOutputStream(gzip).use { output ->
                    output.writeByte(10)
                    output.writeUTF("")
                    entries.forEach { (name, value) ->
                        output.writeByte(8)
                        output.writeUTF(name)
                        output.writeUTF(value)
                    }
                    output.writeByte(0)
                }
            }
        }.toByteArray()
}
