package dev.jdx.index.artifact

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Tier-1 unit tests for [ArtifactHash]: known vectors, stability, and directory semantics.
 * Hashing real fixture jars is tier 2 ([ArtifactLoaderTest]); everything here is bytes and
 * temp dirs, milliseconds each.
 */
class ArtifactHashTest {

    @Test
    fun `sha256 matches the known vector`() {
        ArtifactHash.sha256Hex("abc".toByteArray(Charsets.UTF_8)) shouldBe
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
    }

    @Test
    fun `short hash is the 128-bit prefix of the full hash`() {
        val bytes = "gson-2.14.0.jar".toByteArray(Charsets.UTF_8)
        ArtifactHash.shortHashHex(bytes) shouldBe ArtifactHash.sha256Hex(bytes).substring(0, 32)
        ArtifactHash.shortHashHex(bytes).length shouldBe 32
    }

    @Test
    fun `hashFile is stable across calls`() {
        val file = Files.createTempFile("jdx-hash", ".bin")
        try {
            Files.write(file, ByteArray(100_000) { it.toByte() })
            ArtifactHash.hashFile(file) shouldBe ArtifactHash.hashFile(file)
        } finally {
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun `hashFile equals the short hash of the same bytes`() {
        val file = Files.createTempFile("jdx-hash", ".bin")
        try {
            val bytes = "deterministic-content".toByteArray(Charsets.UTF_8)
            Files.write(file, bytes)
            ArtifactHash.hashFile(file) shouldBe ArtifactHash.shortHashHex(bytes)
        } finally {
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun `directory hash is stable and content-sensitive`(@TempDir temp: Path) {
        val dir = temp.resolve("classes")
        Files.createDirectories(dir.resolve("com/foo"))
        Files.write(dir.resolve("com/foo/A.class"), byteArrayOf(1, 2, 3))
        val first = ArtifactHash.hashDirectory(dir)
        ArtifactHash.hashDirectory(dir) shouldBe first

        Files.write(dir.resolve("com/foo/A.class"), byteArrayOf(1, 2, 4))
        ArtifactHash.hashDirectory(dir) shouldNotBe first
    }

    @Test
    fun `directory hash notices renames and additions`(@TempDir temp: Path) {
        val dir = temp.resolve("classes")
        Files.createDirectories(dir)
        Files.write(dir.resolve("A.class"), byteArrayOf(1))
        val one = ArtifactHash.hashDirectory(dir)
        Files.write(dir.resolve("B.class"), byteArrayOf(1))
        val two = ArtifactHash.hashDirectory(dir)
        two shouldNotBe one
        Files.move(dir.resolve("B.class"), dir.resolve("C.class"))
        ArtifactHash.hashDirectory(dir) shouldNotBe two
    }
}
