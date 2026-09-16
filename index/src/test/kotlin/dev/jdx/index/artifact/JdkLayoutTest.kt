package dev.jdx.index.artifact

import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.of
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tests for [JdkLayout] (T-012): the shared `src.zip` lookup behind both the `jrt:/`
 * root's `jdkSources` and the `doctor` `jdk-sources` row.
 *
 * Temp-dir homes only — no real JDK, no environment surgery — so this suite stays in
 * tier 1 (docs/TESTING.md §2). Callers needing the real `jrt:/` live in
 * [ArtifactLoaderTest] (tier 2).
 */
class JdkLayoutTest {

    @TempDir
    private lateinit var root: Path

    private fun homeWith(name: String, srcZip: Boolean): Path {
        val home = root.resolve(name).also { Files.createDirectories(it) }
        if (srcZip) {
            Files.createDirectories(home.resolve("lib"))
            Files.write(home.resolve("lib/src.zip"), byteArrayOf(0x50, 0x4b))
        }
        return home
    }

    @Test
    fun `finds src zip under java home`() {
        val home = homeWith("jdk", srcZip = true)

        JdkLayout.findSrcZip(home) shouldBe home.resolve("lib/src.zip")
    }

    @Test
    fun `null when no home ships one`() {
        val home = homeWith("jdk", srcZip = false)

        JdkLayout.findSrcZip(home) shouldBe null
        JdkLayout.findSrcZip(home, homeWith("envjdk", srcZip = false)) shouldBe null
    }

    @Test
    fun `falls back to the env home when java home lacks one`() {
        val home = homeWith("jdk", srcZip = false)
        val env = homeWith("envjdk", srcZip = true)

        JdkLayout.findSrcZip(home, env) shouldBe env.resolve("lib/src.zip")
    }

    @Test
    fun `prefers java home when both ship one`() {
        val home = homeWith("jdk", srcZip = true)
        val env = homeWith("envjdk", srcZip = true)

        JdkLayout.findSrcZip(home, env) shouldBe home.resolve("lib/src.zip")
    }

    @Test
    fun `a directory named src zip is not sources`() {
        val home = homeWith("jdk", srcZip = false)
        Files.createDirectories(home.resolve("lib/src.zip"))

        JdkLayout.findSrcZip(home) shouldBe null
    }

    @Test
    fun `never throws on missing homes`() {
        JdkLayout.findSrcZip(root.resolve("no-such-jdk")) shouldBe null
        JdkLayout.findSrcZip(root.resolve("no-such-jdk"), root.resolve("no-such-env")) shouldBe null
    }

    @Test
    fun `envJavaHome reads JAVA_HOME and rejects blank or missing values`() {
        JdkLayout.envJavaHome { "/usr/lib/jvm/java-26-openjdk" } shouldBe
            Path.of("/usr/lib/jvm/java-26-openjdk")
        JdkLayout.envJavaHome { null } shouldBe null
        JdkLayout.envJavaHome { "" } shouldBe null
        JdkLayout.envJavaHome { "   " } shouldBe null
        JdkLayout.envJavaHome { throw IllegalStateException("no env here") } shouldBe null
    }

    /**
     * Generative contract (TESTING.md §4): over random home layouts the lookup never
     * throws, returns only a real file under a searched home, and honours the
     * java-home-first priority. Each case builds its own homes under one shared temp
     * root, so 500 cases stay inside the tier-1 budget.
     */
    @Test
    fun `lookup contract holds over generated home layouts`() = runBlocking<Unit> {
        val counter = AtomicInteger(0)
        // javaContent: 0 = absent, 1 = src.zip file, 2 = directory named src.zip.
        // envMode: 0 = unset, 1 = same home as java, 2 = a second home.
        checkAll(500, Arb.of(listOf(0, 1, 2)), Arb.of(listOf(0, 1, 2))) { javaContent, envMode ->
            val id = counter.getAndIncrement()
            val javaHome = root.resolve("gen-$id-jdk").also { Files.createDirectories(it) }
            writeContent(javaHome, javaContent)
            val envHome: Path? = when (envMode) {
                1 -> javaHome
                2 -> root.resolve("gen-$id-env").also {
                    Files.createDirectories(it)
                    writeContent(it, (javaContent + 1) % 3)
                }
                else -> null
            }
            // Completion without throwing is the first assertion.
            val found = JdkLayout.findSrcZip(javaHome, envHome)
            if (found != null) {
                (Files.isRegularFile(found)) shouldBe true
                val underJava = found.startsWith(javaHome)
                val underEnv = envHome != null && found.startsWith(envHome)
                ((underJava || underEnv)) shouldBe true
                // Priority: a java-home file always wins over any env home.
                if (javaContent == 1) found shouldBe javaHome.resolve("lib/src.zip")
            } else {
                // Null means neither searched home holds a file.
                (javaContent == 1) shouldBe false
                if (envHome != null && envHome != javaHome) {
                    (Files.isRegularFile(envHome.resolve("lib/src.zip"))) shouldBe false
                }
            }
        }
    }

    private fun writeContent(home: Path, content: Int) {
        when (content) {
            1 -> {
                Files.createDirectories(home.resolve("lib"))
                Files.write(home.resolve("lib/src.zip"), byteArrayOf(0x50, 0x4b))
            }
            2 -> Files.createDirectories(home.resolve("lib/src.zip"))
            else -> Unit
        }
    }
}
