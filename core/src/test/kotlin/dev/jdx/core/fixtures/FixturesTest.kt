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
        val original = System.getProperty("jdx.fixturesDir")
        try {
            System.setProperty("jdx.fixturesDir", tempDir.absolutePath)
            Fixtures.fixturesDir() shouldBe tempDir
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
