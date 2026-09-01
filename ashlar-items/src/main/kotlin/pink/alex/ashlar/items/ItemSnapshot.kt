package pink.alex.ashlar.items

import io.papermc.paper.datacomponent.DataComponentType
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import org.bukkit.NamespacedKey
import org.bukkit.persistence.PersistentDataType
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.MessageDigest

/** Immutable exact live item or detached semantic item used by server-free planners. */
public class ItemSnapshot internal constructor(
    public val material: Material,
    public val amount: Int,
    /** Effective maximum amount from the live item data components. */
    public val maximumAmount: Int,
    stackabilityIdentity: ByteArray,
    nativeBytes: ByteArray?,
) {
    private val stackabilityIdentity: ByteArray = stackabilityIdentity.copyOf()
    private val nativeBytes: ByteArray? = nativeBytes?.copyOf()

    /** Whether this snapshot can be materialized back into a Paper [ItemStack]. */
    public val hasNativeData: Boolean get() = nativeBytes != null

    init {
        require(maximumAmount in 1..ItemSpecBuilder.MAX_ITEM_AMOUNT) {
            "Maximum item amount must be from 1 through ${ItemSpecBuilder.MAX_ITEM_AMOUNT}, got $maximumAmount"
        }
        require(amount in 1..maximumAmount) { "Item amount must be from 1 through $maximumAmount, got $amount" }
        require(this.stackabilityIdentity.isNotEmpty()) { "Stackability identity must not be empty" }
    }

    /** Returns a snapshot produced by editing an isolated copy. Amount-only edits never require Paper. */
    public fun edit(block: ItemSnapshotEditor.() -> Unit): ItemSnapshot =
        ItemSnapshotEditor(this).apply(block).result()

    /** Returns this snapshot with [amount], without materializing its native payload. */
    public fun withAmount(amount: Int): ItemSnapshot {
        require(amount in 1..maximumAmount) { "Item amount must be from 1 through $maximumAmount, got $amount" }
        return ItemSnapshot(material, amount, maximumAmount, stackabilityIdentity, nativeBytes)
    }

    /**
     * Encodes this native snapshot in the stable, checksummed framework envelope.
     * Detached snapshots deliberately cannot cross a persistence boundary.
     */
    public fun encode(): ByteArray = SnapshotEncoding.encode(this)

    /** Tests stackability using the amount-normalized identity computed at capture time. */
    public fun stackableWith(other: ItemSnapshot): Boolean =
        material == other.material && stackabilityIdentity.contentEquals(other.stackabilityIdentity)

    /** SHA-256 fingerprint of this snapshot's stable semantic identity and quantity. */
    public fun fingerprint(): String {
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { data ->
            data.writeUTF(material.name)
            data.writeInt(amount)
            data.writeInt(maximumAmount)
            data.write(stackabilityIdentity)
            data.writeBoolean(hasNativeData)
        }
        return output.toByteArray().sha256().joinToString("") { "%02x".format(it) }
    }

    internal fun nativeBytes(): ByteArray? = nativeBytes?.copyOf()
    internal fun stackabilityIdentity(): ByteArray = stackabilityIdentity.copyOf()

    override fun equals(other: Any?): Boolean =
        other is ItemSnapshot && material == other.material && amount == other.amount &&
            maximumAmount == other.maximumAmount &&
            stackabilityIdentity.contentEquals(other.stackabilityIdentity) &&
            hasNativeData == other.hasNativeData

    override fun hashCode(): Int {
        var result = material.hashCode()
        result = 31 * result + amount
        result = 31 * result + maximumAmount
        result = 31 * result + stackabilityIdentity.contentHashCode()
        result = 31 * result + hasNativeData.hashCode()
        return result
    }

    override fun toString(): String =
        "ItemSnapshot(material=$material, amount=$amount, maximumAmount=$maximumAmount, " +
            "native=$hasNativeData, fingerprint=${fingerprint().take(12)})"

    public companion object {
        /** Maximum accepted native snapshot payload. */
        public const val MAX_ENCODED_BYTES: Int = 4 * 1024 * 1024

        /**
         * Creates a server-free semantic snapshot for planners, component tests, and domain inventories.
         * [stackabilityKey] must be equal exactly when two detached values may share a stack.
         */
        public fun detached(
            material: Material,
            amount: Int,
            maximumAmount: Int,
            stackabilityKey: String = material.name,
        ): ItemSnapshot {
            require(stackabilityKey.isNotBlank()) { "Detached stackability key must not be blank" }
            val identity = "detached:$stackabilityKey".encodeToByteArray().sha256()
            return ItemSnapshot(material, amount, maximumAmount, identity, null)
        }

        /** Decodes and validates a native framework snapshot against the running pinned Paper version. */
        public fun decode(bytes: ByteArray): ItemSnapshotDecode {
            val decoded = SnapshotEncoding.decode(bytes)
            if (decoded !is ItemSnapshotDecode.Found) return decoded
            return try {
                Items.validateNativeSnapshot(decoded.snapshot)
                decoded
            } catch (failure: Exception) {
                ItemSnapshotDecode.NativeIncompatible(
                    failure.message ?: failure::class.simpleName.orEmpty(),
                )
            } catch (failure: LinkageError) {
                ItemSnapshotDecode.NativeIncompatible(
                    failure.message ?: failure::class.simpleName.orEmpty(),
                )
            }
        }
    }
}

/** Edits one snapshot while delaying native Paper work until a native field is touched. */
public class ItemSnapshotEditor internal constructor(private val source: ItemSnapshot) {
    private var paperStack: ItemStack? = null
    private var detachedMaterial: Material = source.material

    /** Material. Changing it requires native data because Paper owns component normalization. */
    public var material: Material
        get() = paperStack?.type ?: detachedMaterial
        set(value) {
            require(value != Material.AIR && value != Material.CAVE_AIR && value != Material.VOID_AIR) {
                "Material $value is not an item"
            }
            paperStack = paper().withType(value)
            detachedMaterial = value
        }

    /** Amount. Reading and changing it never materializes native data. */
    public var amount: Int = source.amount
        set(value) {
            require(value in 1..source.maximumAmount) {
                "Item amount must be from 1 through ${source.maximumAmount}, got $value"
            }
            field = value
        }

    /** Sets any valued Paper data component. */
    public fun <T : Any> data(type: DataComponentType.Valued<T>, value: T) {
        paper().setData(type, value)
    }

    /** Sets any non-valued Paper data component. */
    public fun data(type: DataComponentType.NonValued) {
        paper().setData(type)
    }

    /** Removes a data component including its material default. */
    public fun unsetData(type: DataComponentType) {
        paper().unsetData(type)
    }

    /** Writes a typed persistent value into the isolated native copy. */
    public fun <T> persistent(key: NamespacedKey, value: T, codec: PersistentValueCodec<T>) {
        val bytes = codec.encode(value)
        require(bytes.size <= ItemSpecBuilder.MAX_PERSISTENT_VALUE_BYTES) {
            "Persistent item value '$key' is ${bytes.size} bytes; maximum is ${ItemSpecBuilder.MAX_PERSISTENT_VALUE_BYTES}"
        }
        paper().editPersistentDataContainer { it.set(key, PersistentDataType.BYTE_ARRAY, bytes.copyOf()) }
    }

    /** Removes one persistent value from the isolated native copy. */
    public fun removePersistent(key: NamespacedKey) {
        paper().editPersistentDataContainer { it.remove(key) }
    }

    /** Runs an advanced mutation against an isolated native copy. */
    public fun paper(block: (ItemStack) -> Unit) {
        block(paper())
    }

    internal fun result(): ItemSnapshot {
        val changedStack = paperStack ?: return source.withAmount(amount)
        changedStack.amount = amount
        return Items.capture(changedStack)
    }

    private fun paper(): ItemStack = paperStack ?: Items.materialize(source).also { paperStack = it }
}

/** Structured result of decoding a persisted [ItemSnapshot]. */
public sealed interface ItemSnapshotDecode {
    /** A complete compatible framework snapshot envelope. */
    public data class Found(public val snapshot: ItemSnapshot) : ItemSnapshotDecode

    /** Bytes are not a valid framework snapshot envelope. */
    public data class Malformed(public val message: String) : ItemSnapshotDecode

    /** The envelope uses a newer unsupported format. */
    public data class UnsupportedVersion(public val version: Int) : ItemSnapshotDecode

    /** Payload integrity verification failed. */
    public data class Corrupt(public val message: String) : ItemSnapshotDecode

    /** The envelope is valid, but its native payload is not compatible with the running Paper version. */
    public data class NativeIncompatible(public val message: String) : ItemSnapshotDecode
}

/** Native Paper boundary for authored items and exact live snapshots. */
public object Items {
    /** Materializes an authored item and then applies [presentation]. */
    public fun materialize(
        spec: ItemSpec,
        presentation: ItemPresentation = ItemPresentation.Neutral,
        context: ItemPresentationContext = ItemPresentationContext(),
    ): ItemStack {
        require(spec.material.isItem) { "Material ${spec.material} is not an item" }
        val stack = ItemStack.of(spec.material, spec.amount)
        spec.changes.forEach { it.applyTo(stack) }
        require(stack.amount <= stack.maxStackSize) {
            "Item amount ${stack.amount} exceeds ${spec.material} maximum stack size ${stack.maxStackSize}"
        }
        presentation.apply(stack, context)
        return stack
    }

    /** Captures exact native bytes and a separate amount-independent semantic identity. */
    public fun capture(stack: ItemStack): ItemSnapshot {
        require(!stack.isEmpty) { "Cannot capture an empty item stack" }
        val copy = stack.clone()
        val amountNormalized = copy.asQuantity(1)
        val nativeBytes = amountNormalized.serializeAsBytes()
        require(nativeBytes.size <= ItemSnapshot.MAX_ENCODED_BYTES) {
            "Native item snapshot is ${nativeBytes.size} bytes; maximum is ${ItemSnapshot.MAX_ENCODED_BYTES}"
        }
        return ItemSnapshot(
            material = copy.type,
            amount = copy.amount,
            maximumAmount = copy.maxStackSize,
            stackabilityIdentity = canonicalNativeBytes(nativeBytes).sha256(),
            nativeBytes = nativeBytes,
        )
    }

    /** Reads one typed persistent value without conflating absence and malformed bytes. */
    public fun <T> persistent(
        stack: ItemStack,
        key: NamespacedKey,
        codec: PersistentValueCodec<T>,
    ): PersistentValueRead<T> {
        val bytes = stack.persistentDataContainer.get(key, PersistentDataType.BYTE_ARRAY)
            ?: return PersistentValueRead.Missing
        if (bytes.size > ItemSpecBuilder.MAX_PERSISTENT_VALUE_BYTES) {
            return PersistentValueRead.Invalid("Persistent item value '$key' exceeds the size limit")
        }
        return try {
            PersistentValueRead.Found(codec.decode(bytes.copyOf()))
        } catch (failure: Exception) {
            PersistentValueRead.Invalid("Could not decode persistent item value '$key': ${failure.message.orEmpty()}")
        }
    }

    /** Reads one typed persistent value from a native snapshot. */
    public fun <T> persistent(
        snapshot: ItemSnapshot,
        key: NamespacedKey,
        codec: PersistentValueCodec<T>,
    ): PersistentValueRead<T> = persistent(materialize(snapshot), key, codec)

    /** Reconstructs an independent mutable Paper stack from a native [snapshot]. */
    public fun materialize(snapshot: ItemSnapshot): ItemStack {
        val restored = validateNativeSnapshot(snapshot)
        restored.amount = snapshot.amount
        return restored
    }

    internal fun validateNativeSnapshot(snapshot: ItemSnapshot): ItemStack {
        val bytes = checkNotNull(snapshot.nativeBytes()) {
            "Detached ItemSnapshot '${snapshot.fingerprint().take(12)}' has no native Paper data and cannot be materialized"
        }
        val restored = ItemStack.deserializeBytes(bytes)
        check(!restored.isEmpty) { "Native item bytes contain an empty item" }
        check(restored.type == snapshot.material) { "Native item bytes disagree with snapshot material" }
        check(restored.maxStackSize == snapshot.maximumAmount) {
            "Native item bytes disagree with snapshot maximum amount"
        }
        val actualIdentity = canonicalNativeBytes(bytes).sha256()
        check(MessageDigest.isEqual(actualIdentity, snapshot.stackabilityIdentity())) {
            "Native item bytes disagree with snapshot stackability identity"
        }
        return restored
    }
}

/** Structured result for a typed persistent item value. */
public sealed interface PersistentValueRead<out T> {
    /** The key contains a valid value. */
    public data class Found<T>(public val value: T) : PersistentValueRead<T>

    /** The key is absent. */
    public data object Missing : PersistentValueRead<Nothing>

    /** The key exists but its bytes cannot be decoded safely. */
    public data class Invalid(public val problem: String) : PersistentValueRead<Nothing>
}

internal object SnapshotEncoding {
    private val magic: ByteArray = byteArrayOf('F'.code.toByte(), 'I'.code.toByte(), 'S'.code.toByte(), 'N'.code.toByte())
    private const val VERSION: Int = 1

    fun encode(snapshot: ItemSnapshot): ByteArray {
        val payload = checkNotNull(snapshot.nativeBytes()) {
            "Detached ItemSnapshot '${snapshot.fingerprint().take(12)}' cannot be persisted"
        }
        val identity = snapshot.stackabilityIdentity()
        val body = ByteArrayOutputStream(payload.size + identity.size + 64)
        DataOutputStream(body).use { data ->
            data.write(magic)
            data.writeByte(VERSION)
            data.writeUTF(snapshot.material.key.asString())
            data.writeInt(snapshot.amount)
            data.writeInt(snapshot.maximumAmount)
            data.writeInt(identity.size)
            data.write(identity)
            data.writeBoolean(true)
            data.writeInt(payload.size)
            data.write(payload)
        }
        val bodyBytes = body.toByteArray()
        return bodyBytes + bodyBytes.sha256()
    }

    fun decode(bytes: ByteArray): ItemSnapshotDecode = try {
        require(bytes.size <= ItemSnapshot.MAX_ENCODED_BYTES + 512) { "Snapshot envelope exceeds size limit" }
        require(bytes.size > magic.size + 1 + CHECKSUM_BYTES) { "Snapshot envelope is truncated" }
        val prefix = bytes.copyOfRange(0, magic.size + 1)
        require(prefix.copyOfRange(0, magic.size).contentEquals(magic)) { "Snapshot magic is invalid" }
        val version = prefix.last().toInt() and 0xff
        if (version != VERSION) return ItemSnapshotDecode.UnsupportedVersion(version)
        val body = bytes.copyOfRange(0, bytes.size - CHECKSUM_BYTES)
        val checksum = bytes.copyOfRange(bytes.size - CHECKSUM_BYTES, bytes.size)
        if (!MessageDigest.isEqual(checksum, body.sha256())) {
            return ItemSnapshotDecode.Corrupt("Snapshot envelope checksum does not match")
        }
        DataInputStream(ByteArrayInputStream(body)).use { input ->
            val actualMagic = ByteArray(magic.size).also(input::readFully)
            require(actualMagic.contentEquals(magic)) { "Snapshot magic is invalid" }
            input.readUnsignedByte()
            val materialKey = input.readUTF()
            val material = Material.matchMaterial(materialKey)
                ?: return ItemSnapshotDecode.Malformed("Unknown material '$materialKey'")
            val amount = input.readInt()
            val maximumAmount = input.readInt()
            val identitySize = input.readInt()
            require(identitySize in 1..128) { "Invalid stackability identity size $identitySize" }
            val identity = ByteArray(identitySize).also(input::readFully)
            require(input.readBoolean()) { "Persisted snapshot has no native payload" }
            val native = run {
                val size = input.readInt()
                require(size in 1..ItemSnapshot.MAX_ENCODED_BYTES) { "Invalid native payload size $size" }
                ByteArray(size).also(input::readFully)
            }
            require(input.available() == 0) { "Trailing bytes after snapshot" }
            ItemSnapshotDecode.Found(ItemSnapshot(material, amount, maximumAmount, identity, native))
        }
    } catch (failure: Exception) {
        ItemSnapshotDecode.Malformed(failure.message ?: failure::class.simpleName.orEmpty())
    }

    private const val CHECKSUM_BYTES: Int = 32
}

internal fun ByteArray.sha256(): ByteArray = MessageDigest.getInstance("SHA-256").digest(this)
