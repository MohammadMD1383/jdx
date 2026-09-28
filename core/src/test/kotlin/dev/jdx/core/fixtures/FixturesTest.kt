package dev.jdx.core.fixtures

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.jar.JarOutputStream
import java.util.zip.ZipEntry

/** Unit tests for [Fixtures] resolution — fake dirs only, so this stays fast with no corpus. */
class FixturesTest {

    @TempDir
    lateinit var tempDir: File

    private fun touch(name: String): File = File(tempDir, name).apply { writeText("fake") }

    @Test
    fun `binaryJar picks the plain jar and sourcesJar picks the sources jar`() {
        touch("testfixtures-0.1.0-SNAPSHOT.jar")
        touch("testfixtures-0.1.0-SNAPSHOT-sources.jar")

        Fixtures.binaryJar(tempDir).name shouldBe "testfixtures-0.1.0-SNAPSHOT.jar"
        Fixtures.sourcesJar(tempDir).name shouldBe "testfixtures-0.1.0-SNAPSHOT-sources.jar"
    }

    @Test
    fun `resolution fails loudly when a jar is missing or duplicated`() {
        touch("testfixtures-0.1.0-SNAPSHOT-sources.jar")

        shouldThrow<IllegalArgumentException> { Fixtures.binaryJar(tempDir) }

        touch("testfixtures-0.1.0-SNAPSHOT.jar")
        touch("testfixtures-0.1.0-SNAPSHOT-javadoc.jar")
        // A second binary-shaped jar is ambiguous — never guess which one is the corpus.
        touch("testfixtures-0.1.0-SNAPSHOT-extra.jar")
        shouldThrow<IllegalArgumentException> { Fixtures.binaryJar(tempDir) }
    }

    @Test
    fun `unrelated jars in the same directory are ignored`() {
        touch("testfixtures-0.1.0-SNAPSHOT.jar")
        touch("testfixtures-0.1.0-SNAPSHOT-sources.jar")
        touch("gson-2.14.0.jar")

        Fixtures.binaryJar(tempDir).name shouldBe "testfixtures-0.1.0-SNAPSHOT.jar"
    }

    @Test
    fun `fixturesDir honors the system property over the directory search`() {
        withFixturesDir(tempDir) {
            Fixtures.fixturesDir() shouldBe tempDir
        }
    }

    /**
     * Issue #66: `Fixtures` is core's deliberate second copy of the shared `FixtureJars`
     * resolution (core's test source set must not depend on a project), so the two must
     * agree on what "available" means. A suite gates on these to skip rather than fail on
     * a machine with no corpus, which is a correctness-of-skip question, not a convenience:
     * a probe that disagreed with its own resolver would skip a suite that could have run.
     */
    @Test
    fun `availability probes agree with what the resolvers would do`() {
        withFixturesDir(tempDir) {
            Fixtures.binaryCorpusAvailable() shouldBe false
            Fixtures.sourcesCorpusAvailable() shouldBe false

            // A directory that exists but holds no jars is the half-built state; `isDirectory`
            // is not "a corpus is in it".
            touch("testfixtures-0.1.0-SNAPSHOT.jar")
            Fixtures.binaryCorpusAvailable() shouldBe true
            Fixtures.sourcesCorpusAvailable() shouldBe false

            // Two binary-shaped jars is the L-029 ambiguity: unresolvable, so not available.
            touch("testfixtures-0.1.0-SNAPSHOT-extra.jar")
            Fixtures.binaryCorpusAvailable() shouldBe false

            File(tempDir, "testfixtures-0.1.0-SNAPSHOT-extra.jar").delete()
            touch("testfixtures-0.1.0-SNAPSHOT-sources.jar")
            Fixtures.binaryCorpusAvailable() shouldBe true
            Fixtures.sourcesCorpusAvailable() shouldBe true
        }
    }

    @Test
    fun `availability probes follow the wired system property`() {
        withFixturesDir(tempDir) {
            touch("testfixtures-0.1.0-SNAPSHOT.jar")
            touch("testfixtures-0.1.0-SNAPSHOT-sources.jar")
            Fixtures.binaryCorpusAvailable() shouldBe true

            val empty = File(tempDir, "empty").apply { mkdirs() }
            withFixturesDir(empty) {
                Fixtures.fixturesDir() shouldBe empty
                Fixtures.binaryCorpusAvailable() shouldBe false
                Fixtures.sourcesCorpusAvailable() shouldBe false
            }
        }
    }

    /** Runs [block] with `jdx.fixturesDir` pointed at [dir], restoring it afterwards. */
    private fun withFixturesDir(dir: File, block: () -> Unit) {
        val original = System.getProperty("jdx.fixturesDir")
        try {
            System.setProperty("jdx.fixturesDir", dir.absolutePath)
            block()
        } finally {
            if (original == null) System.clearProperty("jdx.fixturesDir")
            else System.setProperty("jdx.fixturesDir", original)
        }
    }

    @Test
    fun `staticInitMarkerFile mirrors the fixture formula without touching the class`() {
        val expected = File(System.getProperty("java.io.tmpdir"), Fixtures.STATIC_INIT_MARKER_NAME)
        Fixtures.staticInitMarkerFile() shouldBe expected
        // Reading the path must not create the file — and no test in this JVM may load the class.
        Fixtures.staticInitMarkerFile().exists() shouldBe false
    }

    @Test
    fun `classNames lists binary names sorted`() {
        val jar = File(tempDir, "sample.jar")
        JarOutputStream(jar.outputStream()).use { out ->
            out.putNextEntry(ZipEntry("com/example/Outer\$Inner.class"))
            out.write(byteArrayOf(1, 2, 3))
            out.closeEntry()
            out.putNextEntry(ZipEntry("com/example/Outer.class"))
            out.write(byteArrayOf(4, 5))
            out.closeEntry()
        }

        Fixtures.classNames(jar) shouldContainExactlyInAnyOrder
            listOf("com.example.Outer", "com.example.Outer\$Inner")

        Fixtures.classBytes(jar, "com.example.Outer\$Inner").contentEquals(byteArrayOf(1, 2, 3)) shouldBe true
        shouldThrow<IllegalStateException> { Fixtures.classBytes(jar, "com.example.Missing") }
    }
}
