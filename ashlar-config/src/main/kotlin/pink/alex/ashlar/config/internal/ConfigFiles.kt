package pink.alex.ashlar.config.internal

import pink.alex.ashlar.config.ConfigOperationProblem
import pink.alex.ashlar.config.ConfigOperationProblemCategory
import pink.alex.ashlar.config.ConfigSourceRevision
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.util.UUID

internal class ConfigFiles(
    dataDirectory: Path,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val root: Path = Files.createDirectories(dataDirectory.toAbsolutePath().normalize()).toRealPath()

    fun resolve(relative: String): Path {
        val supplied = Path.of(relative)
        require(!supplied.isAbsolute) { "Configuration path must be relative: $relative" }
        require(relative.isNotBlank()) { "Configuration path cannot be blank" }
        val candidate = root.resolve(supplied).normalize()
        require(candidate.startsWith(root) && candidate != root) {
            "Configuration path escapes the plug-in data directory: $relative"
        }
        createSafeDirectories(checkNotNull(candidate.parent), relative)
        verifyConfined(candidate, relative)
        return candidate
    }

    fun exists(path: Path): Boolean = Files.exists(path)

    fun read(path: Path, relative: String, maximumBytes: Long): FileRead = try {
        verifyConfined(path, relative)
        val size = Files.size(path)
        if (size > maximumBytes) {
            return FileRead.TooLarge(
                size,
                ConfigSourceRevision("unaccepted:$size:${Files.getLastModifiedTime(path).toMillis()}"),
            )
        }
        val bytes = Files.readAllBytes(path)
        if (bytes.size > maximumBytes) return FileRead.TooLarge(bytes.size.toLong(), revision(bytes))
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        FileRead.Accepted(
            text = decoder.decode(ByteBuffer.wrap(bytes)).toString(),
            revision = revision(bytes),
        )
    } catch (_: CharacterCodingException) {
        FileRead.Unavailable(
            ConfigOperationProblem(relative, ConfigOperationProblemCategory.READ_FAILED, "Source is not valid UTF-8"),
        )
    } catch (failure: IllegalArgumentException) {
        FileRead.Unavailable(
            ConfigOperationProblem(relative, ConfigOperationProblemCategory.READ_FAILED, failure.message ?: "Unsafe path"),
        )
    } catch (failure: IOException) {
        FileRead.Unavailable(readProblem(relative, failure))
    }

    fun writeAtomically(path: Path, relative: String, text: String): ConfigOperationProblem? {
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        val temporary = path.resolveSibling(".${path.fileName}.ashlar-${UUID.randomUUID()}.tmp")
        var replacing = false
        try {
            verifyConfined(path, relative)
            FileChannel.open(
                temporary,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
            ).use { channel ->
                var offset = 0
                while (offset < bytes.size) offset += channel.write(ByteBuffer.wrap(bytes, offset, bytes.size - offset))
                channel.force(true)
            }
            replacing = true
            try {
                verifyConfined(path, relative)
                Files.move(
                    temporary,
                    path,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
            }
            forceDirectory(path.parent)
            return null
        } catch (failure: Exception) {
            runCatching { Files.deleteIfExists(temporary) }
            return ConfigOperationProblem(
                relative,
                if (replacing) {
                    ConfigOperationProblemCategory.ATOMIC_REPLACE_FAILED
                } else {
                    ConfigOperationProblemCategory.WRITE_FAILED
                },
                "Could not write the configuration source",
            )
        }
    }

    fun backup(
        path: Path,
        relative: String,
        schemaVersion: Int,
        revision: ConfigSourceRevision,
        maximumRetained: Int,
    ): Result<StoredBackup?> = runCatching {
        if (maximumRetained == 0 || !Files.exists(path)) return@runCatching null
        verifyConfined(path, relative)
        val directory = backupDirectory(relative)
        createSafeDirectories(directory, relative)
        val instant = clock.instant()
        val id = "${instant.toEpochMilli()}-$schemaVersion-${revision.value}-${UUID.randomUUID()}"
        val destination = directory.resolve("$id.bak")
        val temporary = directory.resolve(".$id.tmp")
        try {
            Files.copy(path, temporary)
            forceFile(temporary)
            try {
                Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, destination)
            }
            forceDirectory(directory)
        } catch (failure: Throwable) {
            runCatching { Files.deleteIfExists(temporary) }
            throw failure
        }
        val stored = StoredBackup(id, destination, instant, schemaVersion, revision)
        listBackups(relative).drop(maximumRetained).forEach { old -> Files.deleteIfExists(old.path) }
        stored
    }

    fun listBackups(relative: String): List<StoredBackup> {
        val directory = backupDirectory(relative)
        verifyInternalDirectory(directory, relative)
        if (!Files.isDirectory(directory)) return emptyList()
        return Files.list(directory).use { paths ->
            paths.filter { path -> path.fileName.toString().endsWith(".bak") }
                .map { path -> storedBackup(path) }
                .filter { backup -> backup != null }
                .map { backup -> checkNotNull(backup) }
                .sorted(compareByDescending<StoredBackup> { it.createdAt }.thenByDescending { it.id })
                .toList()
        }
    }

    private fun storedBackup(path: Path): StoredBackup? {
        val id = path.fileName.toString().removeSuffix(".bak")
        val parts = id.split('-', limit = 4)
        if (parts.size != 4) return null
        val epoch = parts[0].toLongOrNull() ?: return null
        val schema = parts[1].toIntOrNull() ?: return null
        return StoredBackup(
            id = id,
            path = path,
            createdAt = Instant.ofEpochMilli(epoch),
            schemaVersion = schema,
            revision = ConfigSourceRevision(parts[2]),
        )
    }

    private fun backupDirectory(relative: String): Path {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(relative.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
            .take(24)
        return root.resolve(".ashlar/backups/$digest")
    }

    private fun createSafeDirectories(directory: Path, relative: String) {
        require(directory.normalize().startsWith(root)) { "Configuration path escapes the data directory: $relative" }
        var current = root
        root.relativize(directory.normalize()).forEach { segment ->
            current = current.resolve(segment)
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                require(!Files.isSymbolicLink(current) && Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                    "Configuration path crosses a symbolic link or non-directory: $relative"
                }
            } else {
                Files.createDirectory(current)
            }
        }
    }

    private fun verifyConfined(path: Path, relative: String) {
        val normalized = path.toAbsolutePath().normalize()
        require(normalized.startsWith(root) && normalized != root) {
            "Configuration path escapes the data directory: $relative"
        }
        var current = root
        root.relativize(checkNotNull(normalized.parent)).forEach { segment ->
            current = current.resolve(segment)
            require(
                Files.exists(current, LinkOption.NOFOLLOW_LINKS) &&
                    !Files.isSymbolicLink(current) &&
                    Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS),
            ) { "Configuration path crosses a symbolic link or missing directory: $relative" }
        }
        if (Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)) {
            require(!Files.isSymbolicLink(normalized)) {
                "Configuration source cannot be a symbolic link: $relative"
            }
        }
    }

    private fun verifyInternalDirectory(directory: Path, relative: String) {
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return
        verifyConfined(directory.resolve("metadata"), relative)
    }

    fun revision(text: String): ConfigSourceRevision = revision(text.toByteArray(StandardCharsets.UTF_8))

    private fun revision(bytes: ByteArray): ConfigSourceRevision = ConfigSourceRevision(
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { byte -> "%02x".format(byte) },
    )

    private fun readProblem(relative: String, failure: IOException): ConfigOperationProblem {
        val denied = failure is java.nio.file.AccessDeniedException
        val missing = failure is java.nio.file.NoSuchFileException
        return ConfigOperationProblem(
            relative,
            when {
                denied -> ConfigOperationProblemCategory.PERMISSION_DENIED
                missing -> ConfigOperationProblemCategory.NOT_FOUND
                else -> ConfigOperationProblemCategory.READ_FAILED
            },
            when {
                denied -> "Permission denied while reading configuration"
                missing -> "Configuration source does not exist"
                else -> "Could not read configuration source"
            },
        )
    }

    private fun forceFile(path: Path) {
        FileChannel.open(path, StandardOpenOption.READ).use { channel -> channel.force(true) }
    }

    private fun forceDirectory(path: Path) {
        runCatching { FileChannel.open(path, StandardOpenOption.READ).use { channel -> channel.force(true) } }
    }
}

internal sealed interface FileRead {
    data class Accepted(val text: String, val revision: ConfigSourceRevision) : FileRead
    data class TooLarge(val bytes: Long, val revision: ConfigSourceRevision) : FileRead
    data class Unavailable(val problem: ConfigOperationProblem) : FileRead
}

internal data class StoredBackup(
    val id: String,
    val path: Path,
    val createdAt: Instant,
    val schemaVersion: Int,
    val revision: ConfigSourceRevision,
)
