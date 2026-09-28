package dev.jdx.testsupport.fixtures

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import java.io.File
import java.util.jar.JarOutputStream
import java.util.zip.ZipEntry
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Unit tests for the shared [FixtureJars] resolution helper (T-063) — fake dirs
 * only, no real corpus, so they stay fast. Everything here touches the
 * filesystem (a [TempDir] at minimum), so the class is `@Tag("tier2")` like the
 * other shared-helper tests in this module ([GoldenFilesTest][dev.jdx.testsupport.golden.GoldenFilesTest]).
 */
@Tag("tier2")
class FixtureJarsTest {

    @TempDir
    lateinit var tempDir: File

    private fun touch(name: String): File = File(tempDir, name).apply { writeText("fake") }

    @Test
    fun `binaryJar picks the plain jar and sourcesJar picks the sources jar`() {
        touch("testfixtures-0.1.0-SNAPSHOT.jar")
        touch("testfixtures-0.1.0-SNAPSHOT-sources.jar")

        FixtureJars.binaryJar(tempDir).name shouldBe "testfixtures-0.1.0-SNAPSHOT.jar"
        FixtureJars.sourcesJar(tempDir).name shouldBe "testfixtures-0.1.0-SNAPSHOT-sources.jar"
    }

    @Test
    fun `resolution fails loudly when a jar is missing or duplicated`() {
        touch("testfixtures-0.1.0-SNAPSHOT-sources.jar")

        shouldThrow<IllegalArgumentException> { FixtureJars.binaryJar(tempDir) }

        touch("testfixtures-0.1.0-SNAPSHOT.jar")
        touch("testfixtures-0.1.0-SNAPSHOT-javadoc.jar")
        // A second binary-shaped jar is ambiguous — never guess which one is the corpus
        // (the L-029 jar-count trap, asserted here so the guard itself stays guarded).
        touch("testfixtures-0.1.0-SNAPSHOT-extra.jar")
        shouldThrow<IllegalArgumentException> { FixtureJars.binaryJar(tempDir) }
    }

    @Test
    fun `unrelated jars in the same directory are ignored`() {
        touch("testfixtures-0.1.0-SNAPSHOT.jar")
        touch("testfixtures-0.1.0-SNAPSHOT-sources.jar")
        touch("gson-2.14.0.jar")

        FixtureJars.binaryJar(tempDir).name shouldBe "testfixtures-0.1.0-SNAPSHOT.jar"
    }

    @Test
    fun `fixturesDir honors the system property over the directory search`() {
        val original = System.getProperty(FixtureJars.FIXTURES_DIR_PROPERTY)
        try {
            System.setProperty(FixtureJars.FIXTURES_DIR_PROPERTY, tempDir.absolutePath)
            FixtureJars.fixturesDir() shouldBe tempDir
        } finally {
            if (original == null) System.clearProperty(FixtureJars.FIXTURES_DIR_PROPERTY)
            else System.setProperty(FixtureJars.FIXTURES_DIR_PROPERTY, original)
        }
    }

    @Test
    fun `classNames lists binary names sorted and classBytes reads entries back`() {
        val jar = File(tempDir, "sample.jar")
        JarOutputStream(jar.outputStream()).use { out ->
            out.putNextEntry(ZipEntry("com/example/Outer\$Inner.class"))
            out.write(byteArrayOf(1, 2, 3))
            out.closeEntry()
            out.putNextEntry(ZipEntry("com/example/Outer.class"))
            out.write(byteArrayOf(4, 5))
            out.closeEntry()
        }

        FixtureJars.classNames(jar) shouldContainExactlyInAnyOrder
            listOf("com.example.Outer", "com.example.Outer\$Inner")

        FixtureJars.classBytes(jar, "com.example.Outer\$Inner").contentEquals(byteArrayOf(1, 2, 3)) shouldBe true
        shouldThrow<IllegalStateException> { FixtureJars.classBytes(jar, "com.example.Missing") }
    }

    /**
     * Issue #66: the probes are what a suite gates on, so they must be *false* precisely
     * when [binaryJar]/[sourcesJar] would throw, and true when it would not. Pinned as a
     * table over the same directory states the resolvers distinguish, because "a directory
     * exists" is not "a corpus is in it" — the empty-dir case is exactly the half-built
     * state that turned a clean checkout red.
     */
    @Test
    fun `availability probes agree with what the resolvers would do`() {
        withCorpusDir { dir ->
            // Nothing at all: the resolvers throw, so both probes are false.
            FixtureJars.binaryCorpusAvailable() shouldBe false
            FixtureJars.sourcesCorpusAvailable() shouldBe false

            // The directory exists but holds no jars — the half-built state. A probe that
            // only checked `isDirectory` would say yes here and the test would still fail.
            dir.mkdirs()
            FixtureJars.binaryCorpusAvailable() shouldBe false
            FixtureJars.sourcesCorpusAvailable() shouldBe false

            // Binary only: the two probes must disagree, or a suite could skip while the
            // jar it needs is sitting right there.
            touch("testfixtures-0.1.0-SNAPSHOT.jar")
            FixtureJars.binaryCorpusAvailable() shouldBe true
            FixtureJars.sourcesCorpusAvailable() shouldBe false

            // Ambiguous (the L-029 trap): "two jars match" is a failure, not a coin flip.
            touch("testfixtures-0.1.0-SNAPSHOT-extra.jar")
            FixtureJars.binaryCorpusAvailable() shouldBe false

            // Complete.
            File(dir, "testfixtures-0.1.0-SNAPSHOT-extra.jar").delete()
            touch("testfixtures-0.1.0-SNAPSHOT-sources.jar")
            FixtureJars.binaryCorpusAvailable() shouldBe true
            FixtureJars.sourcesCorpusAvailable() shouldBe true
        }
    }

    /** The probes and [fixturesDir] must answer from the same system property. */
    @Test
    fun `availability probes follow the wired system property`() {
        withCorpusDir { dir ->
            touch("testfixtures-0.1.0-SNAPSHOT.jar")
            touch("testfixtures-0.1.0-SNAPSHOT-sources.jar")
            FixtureJars.binaryCorpusAvailable() shouldBe true
            FixtureJars.sourcesCorpusAvailable() shouldBe true

            // Point the property at an empty directory: `fixturesDir()` still returns it
            // (it trusts the property), so the probes — not the directory — must go false.
            withProperty(dir.path) {
                val empty = File(dir, "empty").apply { mkdirs() }
                withProperty(empty.path) {
                    FixtureJars.fixturesDir() shouldBe empty
                    FixtureJars.binaryCorpusAvailable() shouldBe false
                    FixtureJars.sourcesCorpusAvailable() shouldBe false
                }
            }
        }
    }

    /** Runs [block] with the corpus directory property set to a fresh temp dir. */
    private fun withCorpusDir(block: (File) -> Unit) {
        withProperty(tempDir.absolutePath) { block(tempDir) }
    }

    private fun withProperty(value: String, block: () -> Unit) {
        val original = System.getProperty(FixtureJars.FIXTURES_DIR_PROPERTY)
        try {
            System.setProperty(FixtureJars.FIXTURES_DIR_PROPERTY, value)
            block()
        } finally {
            if (original == null) System.clearProperty(FixtureJars.FIXTURES_DIR_PROPERTY)
            else System.setProperty(FixtureJars.FIXTURES_DIR_PROPERTY, original)
        }
    }
}
