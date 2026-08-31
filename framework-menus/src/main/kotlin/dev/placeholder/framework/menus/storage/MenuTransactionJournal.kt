package dev.placeholder.framework.menus.storage

import dev.placeholder.framework.items.ItemSnapshot
import dev.placeholder.framework.items.ItemSnapshotDecode
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

/** Journal entry written before a transaction crosses an external commit boundary. */
public data class JournaledMenuTransaction(
    public val domainId: String,
    public val proposal: MenuTransactionProposal,
)

/** Durable intent log used to resolve ambiguous external commit outcomes after restart. */
public interface MenuTransactionJournal {
    /** Persists [entry] before its external commit begins. */
    public suspend fun record(entry: JournaledMenuTransaction)

    /** Removes a definitively committed, rejected, or unsubmitted entry. */
    public suspend fun complete(id: MenuTransactionId)

    /** Reads every unresolved entry in stable identifier order. */
    public suspend fun pending(): List<JournaledMenuTransaction>
}

/** File-backed transaction journal with checksummed atomic entry replacement. */
public class FileMenuTransactionJournal(
    private val directory: Path,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : MenuTransactionJournal {
    override suspend fun record(entry: JournaledMenuTransaction): Unit = withContext(dispatcher) {
        Files.createDirectories(directory)
        val bytes = TransactionJournalEncoding.encode(entry)
        val temporary = Files.createTempFile(directory, ".menu-transaction-", ".tmp")
        try {
            Files.write(temporary, bytes)
            moveAtomically(temporary, path(entry.proposal.id))
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    override suspend fun complete(id: MenuTransactionId): Unit = withContext(dispatcher) {
        Files.deleteIfExists(path(id))
    }

    override suspend fun pending(): List<JournaledMenuTransaction> = withContext(dispatcher) {
        if (!Files.isDirectory(directory)) return@withContext emptyList()
        Files.list(directory).use { paths ->
            paths.filter { path -> path.fileName.toString().endsWith(EXTENSION) }
                .sorted()
                .map { path -> TransactionJournalEncoding.decode(Files.readAllBytes(path)) }
                .toList()
        }
    }

    private fun path(id: MenuTransactionId): Path = directory.resolve("${id.value}$EXTENSION")

    private companion object {
        const val EXTENSION: String = ".fmtx"
    }
}

private object TransactionJournalEncoding {
    private val magic: ByteArray = byteArrayOf('F'.code.toByte(), 'M'.code.toByte(), 'T'.code.toByte(), 'X'.code.toByte())
    private const val VERSION: Int = 1
    private const val MAX_ENTRY_BYTES: Int = 64 * 1024 * 1024

    fun encode(entry: JournaledMenuTransaction): ByteArray {
        require(entry.domainId.isNotBlank()) { "Journal transaction domain must not be blank" }
        val bodyOutput = ByteArrayOutputStream()
        DataOutputStream(bodyOutput).use { output ->
            output.writeUTF(entry.domainId)
            output.writeUuid(entry.proposal.id.value)
            output.writeNullableUuid(entry.proposal.playerId)
            output.writeInt(entry.proposal.changes.size)
            for ((id, change) in entry.proposal.changes.toSortedMap(compareBy(MenuStorageId::toString))) {
                output.writeUTF(id.namespace)
                output.writeUTF(id.value)
                output.writeSnapshot(change.before)
                output.writeSnapshot(change.after)
            }
            output.writeNullableItem(entry.proposal.cursorBefore)
            output.writeNullableItem(entry.proposal.cursorAfter)
            output.writeInt(entry.proposal.emissions.size)
            for (emission in entry.proposal.emissions) when (emission) {
                is MenuTransactionEmission.Drop -> {
                    output.writeByte(1)
                    output.writeItem(emission.item)
                }
            }
        }
        val body = bodyOutput.toByteArray()
        require(body.size <= MAX_ENTRY_BYTES) { "Transaction journal entry exceeds $MAX_ENTRY_BYTES bytes" }
        return ByteArrayOutputStream(body.size + 64).also { complete ->
            DataOutputStream(complete).use { output ->
                output.write(magic)
                output.writeByte(VERSION)
                output.writeInt(body.size)
                output.write(body)
                output.write(body.sha256())
            }
        }.toByteArray()
    }

    fun decode(bytes: ByteArray): JournaledMenuTransaction {
        require(bytes.size <= MAX_ENTRY_BYTES + 64) { "Transaction journal entry exceeds size limit" }
        return DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            val actualMagic = ByteArray(magic.size).also(input::readFully)
            require(actualMagic.contentEquals(magic)) { "Transaction journal magic is invalid" }
            val version = input.readUnsignedByte()
            require(version == VERSION) { "Unsupported transaction journal version $version" }
            val bodySize = input.readInt()
            require(bodySize in 1..MAX_ENTRY_BYTES) { "Invalid transaction journal body size $bodySize" }
            val body = ByteArray(bodySize).also(input::readFully)
            val digest = ByteArray(32).also(input::readFully)
            require(input.available() == 0) { "Trailing transaction journal bytes" }
            require(MessageDigest.isEqual(digest, body.sha256())) { "Transaction journal checksum does not match" }
            decodeBody(body)
        }
    }

    private fun decodeBody(body: ByteArray): JournaledMenuTransaction =
        DataInputStream(ByteArrayInputStream(body)).use { input ->
            val domainId = input.readUTF()
            val transactionId = MenuTransactionId(input.readUuid())
            val playerId = input.readNullableUuid()
            val changeCount = input.readInt()
            require(changeCount in 0..1024) { "Invalid transaction storage count $changeCount" }
            val changes = linkedMapOf<MenuStorageId, MenuStorageChange>()
            repeat(changeCount) {
                val id = MenuStorageId(input.readUTF(), input.readUTF())
                val before = input.readSnapshot(id)
                val after = input.readSnapshot(id)
                require(changes.put(id, MenuStorageChange(before, after)) == null) {
                    "Duplicate journal storage $id"
                }
            }
            val cursorBefore = input.readNullableItem()
            val cursorAfter = input.readNullableItem()
            val emissionCount = input.readInt()
            require(emissionCount in 0..4096) { "Invalid transaction emission count $emissionCount" }
            val emissions = buildList {
                repeat(emissionCount) {
                    when (val type = input.readUnsignedByte()) {
                        1 -> add(MenuTransactionEmission.Drop(input.readItem()))
                        else -> error("Unknown transaction emission type $type")
                    }
                }
            }
            require(input.available() == 0) { "Trailing transaction journal body bytes" }
            JournaledMenuTransaction(
                domainId,
                MenuTransactionProposal(
                    id = transactionId,
                    playerId = playerId,
                    changes = changes,
                    cursorBefore = cursorBefore,
                    cursorAfter = cursorAfter,
                    emissions = emissions,
                ),
            )
        }
}

private fun DataOutputStream.writeSnapshot(snapshot: MenuStorageSnapshot) {
    writeLong(snapshot.revision)
    writeInt(snapshot.size)
    snapshot.slots.forEach(::writeNullableItem)
}

private fun DataInputStream.readSnapshot(id: MenuStorageId): MenuStorageSnapshot {
    val revision = readLong()
    val size = readInt()
    require(size in 0..100_000) { "Invalid journal storage size $size" }
    return MenuStorageSnapshot(id, revision, List(size) { readNullableItem() })
}

private fun DataOutputStream.writeItem(item: ItemSnapshot) {
    val bytes = item.encode()
    writeInt(bytes.size)
    write(bytes)
}

private fun DataInputStream.readItem(): ItemSnapshot {
    val size = readInt()
    require(size in 1..ItemSnapshot.MAX_ENCODED_BYTES + 256) { "Invalid journal item size $size" }
    val bytes = ByteArray(size).also(::readFully)
    return when (val decoded = ItemSnapshot.decode(bytes)) {
        is ItemSnapshotDecode.Found -> decoded.snapshot
        is ItemSnapshotDecode.Corrupt -> error(decoded.message)
        is ItemSnapshotDecode.Malformed -> error(decoded.message)
        is ItemSnapshotDecode.UnsupportedVersion -> error("Unsupported item snapshot version ${decoded.version}")
    }
}

private fun DataOutputStream.writeNullableItem(item: ItemSnapshot?) {
    writeBoolean(item != null)
    if (item != null) writeItem(item)
}

private fun DataInputStream.readNullableItem(): ItemSnapshot? = if (readBoolean()) readItem() else null

private fun DataOutputStream.writeUuid(value: UUID) {
    writeLong(value.mostSignificantBits)
    writeLong(value.leastSignificantBits)
}

private fun DataInputStream.readUuid(): UUID = UUID(readLong(), readLong())

private fun DataOutputStream.writeNullableUuid(value: UUID?) {
    writeBoolean(value != null)
    if (value != null) writeUuid(value)
}

private fun DataInputStream.readNullableUuid(): UUID? = if (readBoolean()) readUuid() else null

private fun ByteArray.sha256(): ByteArray = MessageDigest.getInstance("SHA-256").digest(this)

private fun moveAtomically(source: Path, target: Path) {
    try {
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
    }
}
