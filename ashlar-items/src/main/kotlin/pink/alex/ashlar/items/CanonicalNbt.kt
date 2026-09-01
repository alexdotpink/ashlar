package pink.alex.ashlar.items

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.zip.GZIPInputStream

/** Canonicalizes Paper's compressed NBT without interpreting or discarding unknown item data. */
internal fun canonicalNativeBytes(bytes: ByteArray): ByteArray =
    DataInputStream(GZIPInputStream(ByteArrayInputStream(bytes))).use { input ->
        val type = input.readUnsignedByte()
        require(type != END) { "Native item NBT cannot have an empty root" }
        val name = input.readUTF()
        val payload = canonicalPayload(type, input)
        require(input.read() == -1) { "Native item NBT has trailing data" }
        ByteArrayOutputStream(payload.size + name.length + 8).also { bytesOut ->
            DataOutputStream(bytesOut).use { output ->
                output.writeByte(type)
                output.writeUTF(name)
                output.write(payload)
            }
        }.toByteArray()
    }

private fun canonicalPayload(type: Int, input: DataInputStream): ByteArray = ByteArrayOutputStream().also { bytes ->
    DataOutputStream(bytes).use { output ->
        when (type) {
            BYTE -> output.writeByte(input.readByte().toInt())
            SHORT -> output.writeShort(input.readShort().toInt())
            INT -> output.writeInt(input.readInt())
            LONG -> output.writeLong(input.readLong())
            FLOAT -> output.writeInt(input.readInt())
            DOUBLE -> output.writeLong(input.readLong())
            BYTE_ARRAY -> output.writeSizedBytes(input.readBoundedLength(), input)
            STRING -> output.writeUTF(input.readUTF())
            LIST -> {
                val elementType = input.readUnsignedByte()
                val size = input.readBoundedLength()
                if (elementType == END) require(size == 0) { "An END list must be empty" }
                output.writeByte(elementType)
                output.writeInt(size)
                repeat(size) { output.write(canonicalPayload(elementType, input)) }
            }
            COMPOUND -> {
                val entries = buildList {
                    while (true) {
                        val entryType = input.readUnsignedByte()
                        if (entryType == END) break
                        val name = input.readUTF()
                        add(CompoundEntry(name, entryType, canonicalPayload(entryType, input)))
                    }
                }
                require(entries.map(CompoundEntry::name).distinct().size == entries.size) {
                    "Native item NBT compound has duplicate keys"
                }
                entries.sortedBy(CompoundEntry::name).forEach { entry ->
                    output.writeByte(entry.type)
                    output.writeUTF(entry.name)
                    output.write(entry.payload)
                }
                output.writeByte(END)
            }
            INT_ARRAY -> {
                val size = input.readBoundedLength()
                output.writeInt(size)
                repeat(size) { output.writeInt(input.readInt()) }
            }
            LONG_ARRAY -> {
                val size = input.readBoundedLength()
                output.writeInt(size)
                repeat(size) { output.writeLong(input.readLong()) }
            }
            else -> error("Unknown native item NBT tag type $type")
        }
    }
}.toByteArray()

private fun DataInputStream.readBoundedLength(): Int = readInt().also { size ->
    require(size in 0..MAX_COLLECTION_SIZE) { "Native item NBT collection size $size is invalid" }
}

private fun DataOutputStream.writeSizedBytes(size: Int, input: DataInputStream) {
    writeInt(size)
    val buffer = ByteArray(size)
    input.readFully(buffer)
    write(buffer)
}

private data class CompoundEntry(val name: String, val type: Int, val payload: ByteArray)

private const val END: Int = 0
private const val BYTE: Int = 1
private const val SHORT: Int = 2
private const val INT: Int = 3
private const val LONG: Int = 4
private const val FLOAT: Int = 5
private const val DOUBLE: Int = 6
private const val BYTE_ARRAY: Int = 7
private const val STRING: Int = 8
private const val LIST: Int = 9
private const val COMPOUND: Int = 10
private const val INT_ARRAY: Int = 11
private const val LONG_ARRAY: Int = 12
private const val MAX_COLLECTION_SIZE: Int = 1_000_000
