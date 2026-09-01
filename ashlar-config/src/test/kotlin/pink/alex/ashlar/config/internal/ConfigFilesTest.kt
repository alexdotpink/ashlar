package pink.alex.ashlar.config.internal

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import pink.alex.ashlar.config.ConfigOperationProblemCategory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertFalse
import kotlin.test.assertIs

class ConfigFilesTest {
    @TempDir
    lateinit var root: Path

    @Test
    fun `resolving through an escaping symlink does not create outside directories`() {
        val outside = Files.createTempDirectory("ashlar-config-outside-")
        try {
            Files.createSymbolicLink(root.resolve("linked"), outside)
            val files = ConfigFiles(root)

            kotlin.test.assertFailsWith<IllegalArgumentException> {
                files.resolve("linked/new/settings.json")
            }

            assertFalse(Files.exists(outside.resolve("new")))
        } finally {
            Files.deleteIfExists(root.resolve("linked"))
            Files.deleteIfExists(outside)
        }
    }

    @Test
    fun `a parent replaced by a symlink is rejected on every later operation`() {
        val outside = Files.createTempDirectory("ashlar-config-outside-")
        try {
            val files = ConfigFiles(root)
            val path = files.resolve("nested/settings.json")
            Files.writeString(path, "{}")
            Files.delete(path)
            Files.delete(path.parent)
            Files.createSymbolicLink(path.parent, outside)

            val read = assertIs<FileRead.Unavailable>(files.read(path, "nested/settings.json", 1_024))
            val write = files.writeAtomically(path, "nested/settings.json", "{}")

            kotlin.test.assertEquals(ConfigOperationProblemCategory.READ_FAILED, read.problem.category)
            kotlin.test.assertEquals(ConfigOperationProblemCategory.WRITE_FAILED, write?.category)
            assertFalse(Files.exists(outside.resolve("settings.json")))
        } finally {
            Files.deleteIfExists(root.resolve("nested"))
            Files.deleteIfExists(outside)
        }
    }
}
