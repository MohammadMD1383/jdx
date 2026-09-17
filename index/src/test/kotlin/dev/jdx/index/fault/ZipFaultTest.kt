package dev.jdx.index.fault

import dev.jdx.index.artifact.ArtifactLoader
import dev.jdx.index.artifact.ArtifactTestJars
import dev.jdx.index.artifact.ZipSafety
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Zip-slip and zip-bomb faults (TESTING.md §7, D-017).
 *
 * These tests prove the *defences*, not just survival: a traversal entry must
 * be unlistable, unopenable, and must never materialise outside the jar, and a
 * hostile stream must be rejected after bounded buffering — never read to the
 * end. Every byte here is parsed, never loaded: the static-initialiser marker
 * assertion is the D-017 proof.
 *
 * Tagged `tier2` (docs/TESTING.md §2): crafts jars on disk and reads them back.
 */
@Tag("tier2")
class ZipFaultTest {

    private val binaryJar: File by lazy { ArtifactTestJars.binaryJar() }

    private val realEntry: String = "dev/jdx/fixtures/Generics.class"

    private fun hostileJar(temp: Path): Path {
        val real = ArtifactTestJars.fixtureClassBytes(binaryJar, realEntry)
        return ArtifactTestJars.craftJar(
            temp.resolve("slip.jar"),
            mapOf(
                "../../evil.class" to "evil".toByteArray(),
                "com/../../evil2.class" to "evil".toByteArray(),
                "..\\windows-evil.class" to "evil".toByteArray(),
                "/abs-evil.class" to "evil".toByteArray(),
                "com/ok/Real.class" to real,
            ),
        )
    }

    @Test
    fun `traversal entries are never listed and never open`(@TempDir temp: Path) {
        val jar = hostileJar(temp)
        ArtifactLoader.openJar(jar).use { root ->
            root.classEntryPaths() shouldBe listOf("com/ok/Real.class")
            root.classEntryPaths() shouldNotContain "../../evil.class"
            for (hostile in listOf("../../evil.class", "com/../../evil2.class", "/abs-evil.class")) {
                val failure = shouldThrow<dev.jdx.index.artifact.ArtifactReadException> {
                    root.openClass(hostile)
                }
                // The error names the rejected entry, never a stack frame.
                (failure.message?.contains("evil") ?: false).shouldBeTrue()
                FaultSupport.assertNoStackTrace(failure.message ?: "", "zip-slip rejection")
            }
        }
    }

    @Test
    fun `traversal entries never escape the jar directory`(@TempDir temp: Path) {
        val jar = hostileJar(temp)
        ArtifactLoader.openJar(jar).use { root ->
            // Read everything the jar serves, the way a query would.
            root.classEntryPaths().forEach { path ->
                root.openClass(path).use { it.readAllBytes() }
            }
        }
        // Defence proof (D-017): listing/opening a hostile jar writes nothing —
        // no `evil*.class` file exists anywhere under (or beside) the temp dir.
        Files.walk(temp).use { walk ->
            val escapes = walk
                .filter { Files.isRegularFile(it) }
                .map { it.fileName.toString() }
                .filter { it.startsWith("evil") || it == "abs-evil.class" || it == "windows-evil.class" }
                .toList()
            escapes shouldBe emptyList()
        }
    }

    @Test
    fun `reading a hostile jar never initialises a fixture class`(@TempDir temp: Path) {
        val jar = hostileJar(temp)
        ArtifactLoader.openJar(jar).use { root ->
            root.classEntryPaths().forEach { path ->
                root.openClass(path).use { it.readAllBytes() }
            }
        }
        // StaticInitMarker's `<clinit>` writes this file on class *load*. Bytes were
        // parsed by ASM, never loaded (D-017) — the marker must be absent.
        File(System.getProperty("java.io.tmpdir"), "jdx-fixture-static-init-marker")
            .exists().shouldBeFalse()
    }

    @Test
    fun `the real class inside a hostile jar still answers exit 0`(@TempDir temp: Path) {
        val jar = hostileJar(temp)
        val roots = FaultSupport.rootsOf(jar)
        // Degrade, don't fail: the hostile entries are dropped, the honest class
        // answers normally through both read paths.
        val captured = FaultSupport.captureStreams { jar to dev.jdx.index.service.JdxService.show("com.ok.Real", roots) }
        FaultSupport.assertNoStackTrace(captured.stdout, "hostile-jar show stdout")
        FaultSupport.assertNoStackTrace(captured.stderr, "hostile-jar show stderr")
        captured.value.second.exitCode shouldBe 0
    }

    // -- zip bombs: bounded rejection, never OOM --------------------------------

    /** An infinite stream of zero bytes: any reader that buffers to the end OOMs. */
    private fun infiniteZeros(): InputStream = object : InputStream() {
        override fun read(): Int = 0
    }

    @Test
    fun `an unbounded entry stream is rejected after bounded buffering`() {
        // JarArtifact.openClass funnels every entry through readCapped, so this
        // infinite stream returning at all proves the cap bounds memory: only
        // cap+1 bytes are ever buffered, never the whole hostile entry.
        val failure = shouldThrow<dev.jdx.index.artifact.ArtifactReadException> {
            infiniteZeros().use { ZipSafety.readCapped(it, "bomb.class", cap = 64 * 1024) }
        }
        (failure.message?.contains("bomb.class") ?: false).shouldBeTrue()
        (failure.message?.contains("zip bomb") ?: false).shouldBeTrue()
        FaultSupport.assertNoStackTrace(failure.message ?: "", "zip-bomb rejection")
    }

    @Test
    fun `a huge declared entry size names the artifact and the cap`() {
        val failure = shouldThrow<dev.jdx.index.artifact.ArtifactReadException> {
            ZipSafety.checkDeclaredSize(
                "huge.class",
                ZipSafety.MAX_SINGLE_ENTRY_BYTES + 1,
                "bomb.jar",
            )
        }
        (failure.message?.contains("bomb.jar") ?: false).shouldBeTrue()
        (failure.message?.contains("huge.class") ?: false).shouldBeTrue()
        (failure.message?.contains("probable zip bomb") ?: false).shouldBeTrue()
    }

    @Test
    fun `a huge declared total size names the artifact and the cap`() {
        val failure = shouldThrow<dev.jdx.index.artifact.ArtifactReadException> {
            ZipSafety.checkTotalSize(ZipSafety.MAX_TOTAL_UNCOMPRESSED_BYTES + 1, "bomb.jar")
        }
        (failure.message?.contains("bomb.jar") ?: false).shouldBeTrue()
        (failure.message?.contains("probable zip bomb") ?: false).shouldBeTrue()
    }

    @Test
    fun `an absurd entry count names the artifact and the cap`() {
        val failure = shouldThrow<dev.jdx.index.artifact.ArtifactReadException> {
            ZipSafety.checkEntryCount(ZipSafety.MAX_ENTRY_COUNT + 1, "bomb.jar")
        }
        (failure.message?.contains("bomb.jar") ?: false).shouldBeTrue()
        (failure.message?.contains("probable zip bomb") ?: false).shouldBeTrue()
    }

    @Test
    fun `a corrupt zip file exits 5 naming the artifact`(@TempDir temp: Path) {
        val jar = temp.resolve("not-a-zip.jar")
        Files.write(jar, "this is not a zip file".toByteArray())
        val roots = FaultSupport.rootsOf(jar)
        // Opening the jar itself fails: exit 5 (artifact read error), naming the file.
        val rendered = FaultSupport.checkShow("com.acme.Anything", roots, 5, "not-a-zip.jar")
        rendered.json shouldContain "\"code\":5"
    }
}
