package dev.placeholder.framework.items

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.UUID

/** Converts one typed persistent value to bounded, deterministic bytes. */
public interface PersistentValueCodec<T> {
    /** Stable identifier recorded by protocols which need to identify this codec. */
    public val id: String

    /** Encodes [value] without framework-specific framing. */
    public fun encode(value: T): ByteArray

    /** Decodes bytes previously returned by [encode]. */
    public fun decode(bytes: ByteArray): T
}

/** Common deterministic codecs for item persistent values. */
public object PersistentValueCodecs {
    /** UTF-8 text. */
    public val StringUtf8: PersistentValueCodec<String> = object : PersistentValueCodec<String> {
        override val id: String = "utf8"
        override fun encode(value: String): ByteArray = value.toByteArray(StandardCharsets.UTF_8)
        override fun decode(bytes: ByteArray): String = bytes.toString(StandardCharsets.UTF_8)
    }

    /** Signed big-endian 32-bit integers. */
    public val Int32: PersistentValueCodec<Int> = object : PersistentValueCodec<Int> {
        override val id: String = "int32-be"
        override fun encode(value: Int): ByteArray = ByteBuffer.allocate(Int.SIZE_BYTES).putInt(value).array()
        override fun decode(bytes: ByteArray): Int {
            require(bytes.size == Int.SIZE_BYTES) { "Expected 4 bytes, got ${bytes.size}" }
            return ByteBuffer.wrap(bytes).int
        }
    }

    /** Signed big-endian 64-bit integers. */
    public val Int64: PersistentValueCodec<Long> = object : PersistentValueCodec<Long> {
        override val id: String = "int64-be"
        override fun encode(value: Long): ByteArray = ByteBuffer.allocate(Long.SIZE_BYTES).putLong(value).array()
        override fun decode(bytes: ByteArray): Long {
            require(bytes.size == Long.SIZE_BYTES) { "Expected 8 bytes, got ${bytes.size}" }
            return ByteBuffer.wrap(bytes).long
        }
    }

    /** Two big-endian 64-bit UUID halves. */
    public val Uuid: PersistentValueCodec<UUID> = object : PersistentValueCodec<UUID> {
        override val id: String = "uuid-v1"
        override fun encode(value: UUID): ByteArray =
            ByteBuffer.allocate(16).putLong(value.mostSignificantBits).putLong(value.leastSignificantBits).array()

        override fun decode(bytes: ByteArray): UUID {
            require(bytes.size == 16) { "Expected 16 bytes, got ${bytes.size}" }
            val buffer = ByteBuffer.wrap(bytes)
            return UUID(buffer.long, buffer.long)
        }
    }

    /** Opaque bytes copied at both boundaries. */
    public val Bytes: PersistentValueCodec<ByteArray> = object : PersistentValueCodec<ByteArray> {
        override val id: String = "bytes"
        override fun encode(value: ByteArray): ByteArray = value.copyOf()
        override fun decode(bytes: ByteArray): ByteArray = bytes.copyOf()
    }
}
