package dev.placeholder.framework.menus.storage

import dev.placeholder.framework.items.ItemSnapshot
import dev.placeholder.framework.items.ItemSnapshotDecode
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** One durable item waiting for safe delivery to a player. */
public data class RecoveredMenuItem(
    public val id: UUID,
    /** Stable source identifier which makes replayed deposits idempotent. */
    public val deliveryId: UUID,
    public val playerId: UUID,
    public val item: ItemSnapshot,
    public val storedAt: Instant,
)

/** Durable overflow storage used when a menu cannot return an item to live inventory. */
public interface ItemRecoveryMailbox {
    /** Stores one replay-safe delivery, returning the existing records when [deliveryId] is repeated. */
    public suspend fun deposit(
        deliveryId: UUID,
        playerId: UUID,
        items: List<ItemSnapshot>,
    ): List<RecoveredMenuItem>

    /** Stores exact [items] before their source cursor or transaction is acknowledged. */
    public suspend fun deposit(playerId: UUID, items: List<ItemSnapshot>): List<RecoveredMenuItem> =
        deposit(UUID.randomUUID(), playerId, items)

    /** Returns pending items in insertion order. */
    public suspend fun pending(playerId: UUID): List<RecoveredMenuItem>

    /** Removes items only after their delivery has completed. */
    public suspend fun acknowledge(playerId: UUID, ids: Set<UUID>)

    /** Forgets replay metadata after the durable source can no longer resubmit [deliveryId]. */
    public suspend fun complete(deliveryId: UUID, playerId: UUID) {}
}

/** Atomic file-backed recovery mailbox with one checksummed file per player. */
public class FileItemRecoveryMailbox(
    private val directory: Path,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: Clock = Clock.systemUTC(),
) : ItemRecoveryMailbox {
    private val locks: ConcurrentHashMap<UUID, Mutex> = ConcurrentHashMap()

    override suspend fun deposit(
        deliveryId: UUID,
        playerId: UUID,
        items: List<ItemSnapshot>,
    ): List<RecoveredMenuItem> {
        if (items.isEmpty()) return emptyList()
        return lock(playerId).withLock {
            withContext(dispatcher) {
                val current = read(playerId).toMutableList()
                val fingerprint = deliveryFingerprint(items)
                readMarker(playerId, deliveryId)?.let { recorded ->
                    require(MessageDigest.isEqual(recorded, fingerprint)) {
                        "Recovery delivery $deliveryId was replayed with a different payload"
                    }
                    return@withContext current.filter { entry -> entry.deliveryId == deliveryId }
                }
                val replay = current.filter { entry -> entry.deliveryId == deliveryId }
                if (replay.isNotEmpty()) {
                    require(replay.map(RecoveredMenuItem::item) == items) {
                        "Recovery delivery $deliveryId was replayed with a different payload"
                    }
                    writeMarker(playerId, deliveryId, fingerprint)
                    return@withContext replay
                }
                require(current.size + items.size <= MAX_ITEMS_PER_PLAYER) {
                    "Recovery mailbox for $playerId exceeds $MAX_ITEMS_PER_PLAYER items"
                }
                val now = clock.instant().truncatedTo(ChronoUnit.MILLIS)
                val added = items.mapIndexed { index, item ->
                    RecoveredMenuItem(deliveryEntryId(deliveryId, index), deliveryId, playerId, item, now)
                }
                current += added
                write(playerId, current)
                writeMarker(playerId, deliveryId, fingerprint)
                added
            }
        }
    }

    override suspend fun pending(playerId: UUID): List<RecoveredMenuItem> = lock(playerId).withLock {
        withContext(dispatcher) { read(playerId) }
    }

    override suspend fun acknowledge(playerId: UUID, ids: Set<UUID>) {
        if (ids.isEmpty()) return
        lock(playerId).withLock {
            withContext(dispatcher) {
                val retained = read(playerId).filterNot { it.id in ids }
                if (retained.isEmpty()) Files.deleteIfExists(path(playerId)) else write(playerId, retained)
            }
        }
    }

    override suspend fun complete(deliveryId: UUID, playerId: UUID) {
        lock(playerId).withLock {
            withContext(dispatcher) { Files.deleteIfExists(markerPath(playerId, deliveryId)) }
        }
    }

    private fun lock(playerId: UUID): Mutex = locks.computeIfAbsent(playerId) { Mutex() }

    private fun read(playerId: UUID): List<RecoveredMenuItem> {
        val path = path(playerId)
        if (!Files.isRegularFile(path)) return emptyList()
        return RecoveryMailboxEncoding.decode(playerId, Files.readAllBytes(path))
    }

    private fun write(playerId: UUID, items: List<RecoveredMenuItem>) {
        Files.createDirectories(directory)
        val temporary = Files.createTempFile(directory, ".menu-recovery-", ".tmp")
        try {
            Files.write(temporary, RecoveryMailboxEncoding.encode(items))
            try {
                Files.move(temporary, path(playerId), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, path(playerId), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun path(playerId: UUID): Path = directory.resolve("$playerId.fmri")

    private fun markerPath(playerId: UUID, deliveryId: UUID): Path =
        directory.resolve("$playerId.$deliveryId.fmrd")

    private fun readMarker(playerId: UUID, deliveryId: UUID): ByteArray? = markerPath(playerId, deliveryId)
        .takeIf(Files::isRegularFile)
        ?.let(Files::readAllBytes)

    private fun writeMarker(playerId: UUID, deliveryId: UUID, fingerprint: ByteArray) {
        Files.createDirectories(directory)
        val temporary = Files.createTempFile(directory, ".menu-delivery-", ".tmp")
        try {
            Files.write(temporary, fingerprint)
            try {
                Files.move(
                    temporary,
                    markerPath(playerId, deliveryId),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, markerPath(playerId, deliveryId), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun deliveryEntryId(deliveryId: UUID, index: Int): UUID = UUID.nameUUIDFromBytes(
        "$deliveryId:$index".encodeToByteArray(),
    )

    private companion object {
        const val MAX_ITEMS_PER_PLAYER: Int = 10_000
    }
}

private fun deliveryFingerprint(items: List<ItemSnapshot>): ByteArray {
    val output = ByteArrayOutputStream()
    DataOutputStream(output).use { data ->
        data.writeInt(items.size)
        items.forEach { item ->
            val encoded = item.encode()
            data.writeInt(encoded.size)
            data.write(encoded)
        }
    }
    return output.toByteArray().sha256()
}

private object RecoveryMailboxEncoding {
    private val magic: ByteArray = byteArrayOf('F'.code.toByte(), 'M'.code.toByte(), 'R'.code.toByte(), 'I'.code.toByte())
    private const val VERSION: Int = 2
    private const val MAX_FILE_BYTES: Int = 64 * 1024 * 1024

    fun encode(items: List<RecoveredMenuItem>): ByteArray {
        val bodyOutput = ByteArrayOutputStream()
        DataOutputStream(bodyOutput).use { output ->
            output.writeInt(items.size)
            for (entry in items) {
                output.writeLong(entry.id.mostSignificantBits)
                output.writeLong(entry.id.leastSignificantBits)
                output.writeLong(entry.deliveryId.mostSignificantBits)
                output.writeLong(entry.deliveryId.leastSignificantBits)
                output.writeLong(entry.storedAt.toEpochMilli())
                val item = entry.item.encode()
                output.writeInt(item.size)
                output.write(item)
            }
        }
        val body = bodyOutput.toByteArray()
        require(body.size <= MAX_FILE_BYTES) { "Recovery mailbox exceeds $MAX_FILE_BYTES bytes" }
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

    fun decode(playerId: UUID, bytes: ByteArray): List<RecoveredMenuItem> {
        require(bytes.size <= MAX_FILE_BYTES + 64) { "Recovery mailbox exceeds size limit" }
        return DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            val actualMagic = ByteArray(magic.size).also(input::readFully)
            require(actualMagic.contentEquals(magic)) { "Recovery mailbox magic is invalid" }
            val version = input.readUnsignedByte()
            require(version in 1..VERSION) { "Unsupported recovery mailbox version $version" }
            val size = input.readInt()
            require(size in 1..MAX_FILE_BYTES) { "Invalid recovery mailbox size $size" }
            val body = ByteArray(size).also(input::readFully)
            val digest = ByteArray(32).also(input::readFully)
            require(input.available() == 0) { "Trailing recovery mailbox bytes" }
            require(MessageDigest.isEqual(digest, body.sha256())) { "Recovery mailbox checksum does not match" }
            decodeBody(playerId, body, version)
        }
    }

    private fun decodeBody(playerId: UUID, body: ByteArray, version: Int): List<RecoveredMenuItem> =
        DataInputStream(ByteArrayInputStream(body)).use { input ->
            val count = input.readInt()
            require(count in 0..10_000) { "Invalid recovery mailbox item count $count" }
            val result = List(count) {
                val id = UUID(input.readLong(), input.readLong())
                val deliveryId = if (version >= 2) UUID(input.readLong(), input.readLong()) else id
                val storedAt = Instant.ofEpochMilli(input.readLong())
                val size = input.readInt()
                require(size in 1..ItemSnapshot.MAX_ENCODED_BYTES + 256) { "Invalid recovered item size $size" }
                val encoded = ByteArray(size).also(input::readFully)
                val snapshot = when (val decoded = ItemSnapshot.decode(encoded)) {
                    is ItemSnapshotDecode.Found -> decoded.snapshot
                    is ItemSnapshotDecode.Corrupt -> error(decoded.message)
                    is ItemSnapshotDecode.Malformed -> error(decoded.message)
                    is ItemSnapshotDecode.UnsupportedVersion -> error("Unsupported item version ${decoded.version}")
                    is ItemSnapshotDecode.NativeIncompatible -> error(decoded.message)
                }
                RecoveredMenuItem(id, deliveryId, playerId, snapshot, storedAt)
            }
            require(input.available() == 0) { "Trailing recovery mailbox body bytes" }
            result
        }
}

private fun ByteArray.sha256(): ByteArray = MessageDigest.getInstance("SHA-256").digest(this)
