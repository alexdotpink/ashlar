package dev.placeholder.framework.items

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** One named HMAC key. Key bytes are copied and never included in diagnostics. */
public class HmacKey(
    /** Stable public key identifier stored beside signatures. */
    public val id: String,
    bytes: ByteArray,
) {
    private val bytes: ByteArray = bytes.copyOf()

    init {
        require(id.isNotBlank()) { "HMAC key id must not be blank" }
        require(this.bytes.size >= 32) { "HMAC-SHA256 keys must contain at least 32 bytes" }
    }

    internal fun sign(message: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(bytes, "HmacSHA256"))
        doFinal(message)
    }
}

/** Active signing key plus accepted previous verification keys. */
public class HmacKeyring(
    /** Key used to sign every newly created item. */
    public val active: HmacKey,
    verificationKeys: Iterable<HmacKey> = emptyList(),
) {
    private val keys: Map<String, HmacKey> =
        (listOf(active) + verificationKeys.toList()).let { all ->
            all.associateBy { it.id }.also {
                require(it.size == all.size) { "HMAC key ids must be unique" }
            }
        }

    internal fun sign(message: ByteArray): ItemSignature = ItemSignature(active.id, active.sign(message))

    internal fun verify(signature: ItemSignature, message: ByteArray): Boolean {
        val key = keys[signature.keyId] ?: return false
        return MessageDigest.isEqual(key.sign(message), signature.bytes)
    }
}

internal class ItemSignature(val keyId: String, bytes: ByteArray) {
    val bytes: ByteArray = bytes.copyOf()
}
