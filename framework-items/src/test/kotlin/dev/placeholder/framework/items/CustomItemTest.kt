package dev.placeholder.framework.items

import kotlinx.serialization.Serializable
import org.bukkit.Material
import org.bukkit.NamespacedKey
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class CustomItemTest {
    @Test
    fun `Kotlin serialization payload and envelope are stable`() {
        val definition = tokenDefinition()
        val value = TokenData("market", 4)

        val first = definition.encodeEnvelope(value)
        val second = definition.encodeEnvelope(value)
        val found = assertIs<CustomItemRead.Found<TokenData>>(definition.readEnvelope(first))

        assertContentEquals(first, second)
        assertEquals(value, found.data)
        assertEquals(2, found.sourceVersion)
        assertTrue(!found.migrated)
    }

    @Test
    fun `wrong identity is not this item`() {
        val source = tokenDefinition(NamespacedKey("test", "source"))
        val target = tokenDefinition(NamespacedKey("test", "target"))

        assertEquals(CustomItemRead.NotThisItem, target.readEnvelope(source.encodeEnvelope(TokenData("a", 1))))
    }

    @Test
    fun `malformed payload is distinct from wrong identity`() {
        val definition = tokenDefinition()
        assertIs<CustomItemRead.InvalidData>(definition.readEnvelope(byteArrayOf(1, 2, 3)))
        assertNull(definition.readOrNull(null))
    }

    @Test
    fun `migration converts a known old schema without rewriting bytes`() {
        val oldCodec = KotlinJsonItemCodec(OldToken.serializer())
        val oldDefinition = customItem<OldToken>(NamespacedKey("test", "token")) {
            version = 1
            data(oldCodec)
            render { item(Material.PAPER) }
        }
        val current = customItem<TokenData>(NamespacedKey("test", "token")) {
            version = 2
            data(TokenData.serializer())
            migrate(1, oldCodec) { TokenData(it.name, 1) }
            render { item(Material.PAPER) }
        }

        val found = assertIs<CustomItemRead.Found<TokenData>>(
            current.readEnvelope(oldDefinition.encodeEnvelope(OldToken("spawn"))),
        )
        assertEquals(TokenData("spawn", 1), found.data)
        assertEquals(1, found.sourceVersion)
        assertTrue(found.migrated)
    }

    @Test
    fun `missing migration is structured`() {
        val old = customItem<OldToken>(NamespacedKey("test", "token")) {
            version = 1
            data(OldToken.serializer())
            render { item(Material.PAPER) }
        }

        assertIs<CustomItemRead.MigrationFailed>(
            tokenDefinition().readEnvelope(old.encodeEnvelope(OldToken("old"))),
        )
    }

    @Test
    fun `future version and wrong current codec are distinct`() {
        val future = customItem<TokenData>(NamespacedKey("test", "token")) {
            version = 3
            data(TokenData.serializer())
            render { item(Material.PAPER) }
        }
        assertIs<CustomItemRead.UnsupportedVersion>(
            tokenDefinition().readEnvelope(future.encodeEnvelope(TokenData("future", 1))),
        )

        val otherCodec = object : CustomItemCodec<TokenData> {
            override val id: String = "other-v1"
            override fun encode(value: TokenData): ByteArray = "${value.name}:${value.uses}".encodeToByteArray()
            override fun decode(bytes: ByteArray): TokenData = error("must not decode")
        }
        val other = customItem<TokenData>(NamespacedKey("test", "token")) {
            version = 2
            data(otherCodec)
            render { item(Material.PAPER) }
        }
        assertIs<CustomItemRead.InvalidData>(
            tokenDefinition().readEnvelope(other.encodeEnvelope(TokenData("other", 1))),
        )
    }

    @Test
    fun `migration exception is structured`() {
        val old = customItem<OldToken>(NamespacedKey("test", "token")) {
            version = 1
            data(OldToken.serializer())
            render { item(Material.PAPER) }
        }
        val broken = customItem<TokenData>(NamespacedKey("test", "token")) {
            version = 2
            data(TokenData.serializer())
            migrate(1, KotlinJsonItemCodec(OldToken.serializer())) { error("migration exploded") }
            render { item(Material.PAPER) }
        }

        val failure = assertIs<CustomItemRead.MigrationFailed>(
            broken.readEnvelope(old.encodeEnvelope(OldToken("old"))),
        )
        assertTrue(failure.problem.contains("migration exploded"))
    }

    @Test
    fun `signature detects payload tampering and accepts a rotated key`() {
        val oldKey = HmacKey("2025", ByteArray(32) { 5 })
        val oldDefinition = signedDefinition(HmacKeyring(oldKey))
        val envelope = oldDefinition.encodeEnvelope(TokenData("signed", 2))

        val rotated = signedDefinition(
            HmacKeyring(HmacKey("2026", ByteArray(32) { 6 }), listOf(oldKey)),
        )
        assertIs<CustomItemRead.Found<TokenData>>(rotated.readEnvelope(envelope))

        val payloadMarker = "signed".encodeToByteArray()
        val markerIndex = envelope.lastIndexOfSubsequence(payloadMarker)
        envelope[markerIndex] = 'x'.code.toByte()
        assertIs<CustomItemRead.InvalidSignature>(rotated.readEnvelope(envelope))
    }

    @Test
    fun `unknown signing key and unsigned item are rejected by signed definition`() {
        val signed = signedDefinition(HmacKeyring(HmacKey("active", ByteArray(32) { 7 })))
        val other = signedDefinition(HmacKeyring(HmacKey("other", ByteArray(32) { 8 })))
        val unsigned = tokenDefinition(NamespacedKey("test", "signed_token"))

        assertIs<CustomItemRead.InvalidSignature>(signed.readEnvelope(other.encodeEnvelope(TokenData("x", 1))))
        assertIs<CustomItemRead.InvalidSignature>(signed.readEnvelope(unsigned.encodeEnvelope(TokenData("x", 1))))
    }

    @Test
    fun `definitions require a codec renderer and valid schema`() {
        assertFailsWith<IllegalStateException> {
            customItem<TokenData>(NamespacedKey("test", "broken")) {
                render { item(Material.PAPER) }
            }
        }
        assertFailsWith<IllegalStateException> {
            customItem<TokenData>(NamespacedKey("test", "broken")) {
                data(TokenData.serializer())
            }
        }
    }

    private fun tokenDefinition(
        id: NamespacedKey = NamespacedKey("test", "token"),
    ): CustomItemDefinition<TokenData> = customItem(id) {
        version = 2
        data(TokenData.serializer())
        render { item(Material.PAPER) }
    }

    private fun signedDefinition(keyring: HmacKeyring): CustomItemDefinition<TokenData> =
        customItem(NamespacedKey("test", "signed_token")) {
            version = 2
            data(TokenData.serializer())
            integrity(keyring)
            render { item(Material.PAPER) }
        }

    @Serializable
    private data class TokenData(val name: String, val uses: Int)

    @Serializable
    private data class OldToken(val name: String)

    private fun ByteArray.lastIndexOfSubsequence(value: ByteArray): Int =
        indices.reversed().first { start ->
            start + value.size <= size && value.indices.all { offset -> this[start + offset] == value[offset] }
        }
}
