package dev.jdx.index.service

import dev.jdx.index.artifact.ArtifactReadException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * `--jars` root expansion on every filesystem (phase 5): `~`/`~\` home
 * spelling, drive/UNC-safe glob roots, case-insensitive `.jar`/`.zip`, and a
 * symlink-loop guard — all over `@TempDir`, never the real home.
 */
class ExpandJarSpecTest {

    @TempDir
    lateinit var tempDir: Path

    private fun jar(dir: Path, name: String): Path {
        Files.createDirectories(dir)
        return Files.write(dir.resolve(name), byteArrayOf(0x50, 0x4b))
    }

    @Test
    fun `tilde slash expands to the injected home`() {
        JdxService.expandUser("~/lib/a.jar", "/fake/home") shouldBe "/fake/home/lib/a.jar"
        JdxService.expandUser("~", "/fake/home") shouldBe "/fake/home"
    }

    @Test
    fun `tilde backslash expands like tilde slash`() {
        JdxService.expandUser("~\\lib\\a.jar", "C:\\Users\\ada") shouldBe "C:\\Users\\ada\\lib\\a.jar"
    }

    @Test
    fun `non-tilde specs pass through untouched`() {
        JdxService.expandUser("C:\\libs\\a.jar", "/fake/home") shouldBe "C:\\libs\\a.jar"
        JdxService.expandUser("/opt/libs/a.jar", "/fake/home") shouldBe "/opt/libs/a.jar"
        JdxService.expandUser("~other/a.jar", "/fake/home") shouldBe "~other/a.jar"
    }

    @Test
    fun `a tilde glob resolves under the injected home`() {
        val home = tempDir.resolve("home")
        jar(home.resolve("lib"), "a.jar")
        val hits = JdxService.expandJarSpec("~/lib/*.jar", home.toString())
        hits shouldBe listOf(home.resolve("lib/a.jar"))
    }

    @Test
    fun `a plain dir and a plain jar resolve without globbing`() {
        val dir = tempDir.resolve("classes").also { Files.createDirectories(it) }
        val file = jar(tempDir.resolve("libs"), "a.jar")
        JdxService.expandJarSpec(dir.toString(), tempDir.toString()) shouldBe listOf(dir)
        JdxService.expandJarSpec(file.toString(), tempDir.toString()) shouldBe listOf(file)
    }

    @Test
    fun `a missing root is an artifact error`() {
        shouldThrow<ArtifactReadException> {
            JdxService.expandJarSpec(tempDir.resolve("nope.jar").toString(), tempDir.toString())
        }
        shouldThrow<ArtifactReadException> {
            JdxService.expandJarSpec(tempDir.resolve("nothing/*.jar").toString(), tempDir.toString())
        }
    }

    @Test
    fun `an upper-case JAR matches a star glob`() {
        val dir = tempDir.resolve("mixed")
        jar(dir, "FOO.JAR")
        jar(dir, "bar.ZIP")
        jar(dir, "notes.txt")
        val hits = JdxService.expandJarSpec(dir.resolve("*").toString(), tempDir.toString())
        hits.map { it.fileName.toString() }.sorted() shouldBe listOf("FOO.JAR", "bar.ZIP")
    }

    @Test
    fun `a symlink loop degrades to an error, never a hang`() {
        val dir = tempDir.resolve("loop")
        Files.createDirectories(dir.resolve("sub"))
        try {
            Files.createSymbolicLink(dir.resolve("sub/back"), dir)
        } catch (e: UnsupportedOperationException) {
            assumeTrue(false, "symlinks unsupported on this host/filesystem: ${e.message}")
        } catch (e: java.io.IOException) {
            assumeTrue(false, "symlinks need privilege on this host (Windows Developer Mode): ${e.message}")
        } catch (e: SecurityException) {
            assumeTrue(false, "symlinks blocked by security manager: ${e.message}")
        }
        jar(dir, "a.jar")
        // `*` matches the loop link itself; the walk must terminate.
        val hits = JdxService.expandJarSpec(dir.resolve("*").toString(), tempDir.toString())
        (hits.map { it.fileName.toString() }.contains("a.jar")) shouldBe true
    }

    @Test
    fun `glob hits sort deterministically`() {
        val dir = tempDir.resolve("sorted")
        jar(dir, "c.jar")
        jar(dir, "a.jar")
        jar(dir, "b.jar")
        val hits = JdxService.expandJarSpec(dir.resolve("*.jar").toString(), tempDir.toString())
        hits.map { it.fileName.toString() } shouldBe listOf("a.jar", "b.jar", "c.jar")
    }

    @Test
    fun `tilde expansion never leaves a leading tilde`() = runBlocking {
        checkAll(Arb.string(0..24)) { suffix ->
            val expanded = JdxService.expandUser("~/$suffix", "/fake/home")
            (expanded.startsWith("/fake/home/")) shouldBe true
        }
    }
}
