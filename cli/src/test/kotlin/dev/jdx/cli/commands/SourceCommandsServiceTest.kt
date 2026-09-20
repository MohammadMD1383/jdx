package dev.jdx.cli.commands

import com.github.ajalt.clikt.core.parse
import dev.jdx.index.workspace.InMemoryWorkspaceStore
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail
import org.junit.jupiter.api.io.TempDir

/**
 * Behaviour of `source` against real artifacts (T-023, tier 2): the fixture corpus
 * jar with its sibling `-sources.jar`.
 *
 * Rendering itself is pinned by the golden tests; here the assertions are
 * structural — flags, exit codes, determinism and the D-017 never-load proof.
 */
@Tag("tier2")
class SourceCommandsServiceTest {

    private class TestExit(val code: Int) : RuntimeException()

    private fun fixtureJar(): File {
        val dir = System.getProperty("jdx.fixturesDir")?.let { File(it) }
            ?: fail("jdx.fixturesDir not set (cli build wires it; see cli/build.gradle.kts)")
        return dir.listFiles { file ->
            file.isFile && file.name.startsWith("testfixtures-") && !file.name.endsWith("-sources.jar")
        }?.singleOrNull() ?: fail("expected exactly one binary fixture jar in $dir")
    }

    private data class Run(val output: String, val exit: Int)

    private fun run(args: List<String>): Run {
        val original = System.out
        val buffer = ByteArrayOutputStream()
        System.setOut(PrintStream(buffer))
        val exit = try {
            JdxTestCli.parse(args)
            0
        } catch (e: TestExit) {
            e.code
        } finally {
            System.setOut(original)
        }
        return Run(buffer.toString(Charsets.UTF_8), exit)
    }

    /**
     * The source command with the real service but a throwing exit (tier-1 style).
     * An isolated store and empty environment: the contributor's ambient
     * `active-workspace` (e.g. `fx`) must not re-root these assertions
     * (the T-066/T-070 trap).
     */
    private object JdxTestCli {
        private val noDiscovery: ProjectDiscoveryFn = { _, _, _, _ -> null }
        private val noEnv: (String) -> String? = { null }

        fun parse(args: List<String>) {
            require(args.firstOrNull() == "source") { "expected a source invocation, got: $args" }
            SourceCommand(
                query = ::defaultSourceQuery,
                terminate = { throw TestExit(it) },
                store = InMemoryWorkspaceStore(),
                getenv = noEnv,
                discover = noDiscovery,
            ).parse(args.drop(1))
        }
    }

    private fun fixtureArgs(vararg extra: String): List<String> =
        listOf("--jars", fixtureJar().absolutePath, "--no-jdk") + extra

    @Test
    fun `source answers from the paired sources jar`() {
        val run = run(listOf("source") + fixtureArgs("dev.jdx.fixtures.Generics"))
        run.exit shouldBe 0
        run.output shouldContain "dev.jdx.fixtures.Generics"
        run.output shouldContain "public U identity(U value) {"
        run.output shouldContain "-sources.jar · dev/jdx/fixtures/Generics.java:"
    }

    @Test
    fun `lines and around slice the file`() {
        val window = run(listOf("source") + fixtureArgs("dev.jdx.fixtures.Generics", "--lines", "22:24"))
        window.exit shouldBe 0
        window.output shouldContain "Generics.java:22-24"
        window.output shouldContain "public U identity(U value) {"
        window.output shouldNotContain "package dev.jdx.fixtures;"
        val around = run(
            listOf("source") + fixtureArgs(
                "dev.jdx.fixtures.Generics",
                "--around", "dev.jdx.fixtures.Generics#identity(java.lang.Object)",
                "--context", "1", "--line-numbers",
            ),
        )
        around.exit shouldBe 0
        around.output shouldContain "|"
        around.output shouldContain "public U identity(U value) {"
    }

    @Test
    fun `unknown type exits 1 and a member ref exits 3`() {
        val missing = run(listOf("source") + fixtureArgs("dev.jdx.fixtures.NoSuchType"))
        missing.exit shouldBe 1
        missing.output shouldContain "not found:"
        val memberRef = run(listOf("source") + fixtureArgs("dev.jdx.fixtures.Generics#identity(java.lang.Object)"))
        memberRef.exit shouldBe 3
        memberRef.output shouldContain "--around"
    }

    @Test
    fun `exclusive flags exit 3`() {
        val exclusive = run(
            listOf("source") + fixtureArgs(
                "dev.jdx.fixtures.Generics", "--lines", "1:2",
                "--around", "dev.jdx.fixtures.Generics#identity(java.lang.Object)",
            ),
        )
        exclusive.exit shouldBe 3
        exclusive.output shouldContain "mutually exclusive"
    }

    @Test
    fun `forced javap disassembles end to end with a labelled provenance`() {
        val run = run(listOf("source") + fixtureArgs("dev.jdx.fixtures.Generics", "--engine", "javap"))
        run.exit shouldBe 0
        run.output shouldContain "disassembled by javap from testfixtures-"
        run.output shouldContain "reconstructed"
        run.output shouldContain "Compiled from"
        run.output shouldNotContain "package dev.jdx.fixtures;"
    }

    @Test
    fun `sourceless jar reconstructs end to end with a labelled provenance`(@TempDir tempDir: Path) {
        // The one path unit tests cannot cover: the production default engine
        // with the system cache. The entry is content-addressed under jdx's
        // own `~/.cache/jdx/decompile` — regenerable and harmless.
        val bare = tempDir.resolve("bare.jar").toFile()
        ZipFile(fixtureJar()).use { zip ->
            ZipOutputStream(bare.outputStream()).use { out ->
                val name = "dev/jdx/fixtures/Generics.class"
                out.putNextEntry(ZipEntry(name))
                zip.getInputStream(zip.getEntry(name)).copyTo(out)
                out.closeEntry()
            }
        }
        val run = run(
            listOf("source", "--jars", bare.absolutePath, "--no-jdk", "dev.jdx.fixtures.Generics"),
        )
        run.exit shouldBe 0
        run.output shouldContain "decompiled by vineflower from bare.jar"
        run.output shouldContain "reconstructed"
        run.output shouldContain "class Generics"
    }

    @Test
    fun `source json parses and carries the envelope`() {
        val run = run(listOf("source") + fixtureArgs("dev.jdx.fixtures.Generics", "--json"))
        run.exit shouldBe 0
        val parsed = Json.parseToJsonElement(run.output.trim()).jsonObject
        parsed["command"]?.jsonPrimitive?.content shouldBe "source"
        parsed["ok"]?.jsonPrimitive?.content shouldBe "true"
        run.output shouldContain "\"origin\":\"sources\""
    }

    @Test
    fun `running twice yields identical bytes`() {
        val ref = "dev.jdx.fixtures.Generics"
        run(listOf("source") + fixtureArgs(ref)).output shouldBe
            run(listOf("source") + fixtureArgs(ref)).output
        run(listOf("source") + fixtureArgs(ref, "--json")).output shouldBe
            run(listOf("source") + fixtureArgs(ref, "--json")).output
    }

    @Test
    fun `no roots at all exits 4`() {
        val bare = run(listOf("source", "dev.jdx.fixtures.Generics", "--no-jdk"))
        bare.exit shouldBe 4
        bare.output shouldContain "no workspace"
    }

    @Test
    fun `querying the marker fixture never loads it (D-017)`() {
        val marker = File(System.getProperty("java.io.tmpdir"), "jdx-fixture-static-init-marker")
        marker.exists() shouldBe false
        val run = run(listOf("source") + fixtureArgs("dev.jdx.fixtures.StaticInitMarker"))
        run.exit shouldBe 0
        run.output shouldContain "public class StaticInitMarker {"
        // No shouldNotContain "Exception": the file legitimately catches
        // Exception in its own <clinit> text — the proof is the exit code
        // plus the absent marker below, not the absence of the word.
        marker.exists() shouldBe false
    }
}
