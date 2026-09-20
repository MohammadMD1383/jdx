package dev.jdx.cli.commands

import com.github.ajalt.clikt.core.parse
import dev.jdx.index.workspace.InMemoryWorkspaceStore
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail

/**
 * Behaviour of `doc` against real artifacts (T-025, tier 2): the fixture corpus
 * jar with its sibling `-sources.jar`.
 *
 * Rendering itself is pinned by the golden tests; here the assertions are
 * structural — flags, exit codes, determinism and the D-017 never-load proof.
 */
@Tag("tier2")
class DocCommandsServiceTest {

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
     * The doc command with the real service but a throwing exit (tier-1 style).
     * An isolated store and empty environment: the contributor's ambient
     * `active-workspace` (e.g. `fx`) must not re-root these assertions
     * (the T-066/T-070 trap).
     */
    private object JdxTestCli {
        private val noDiscovery: ProjectDiscoveryFn = { _, _, _, _ -> null }
        private val noEnv: (String) -> String? = { null }

        fun parse(args: List<String>) {
            require(args.firstOrNull() == "doc") { "expected a doc invocation, got: $args" }
            DocCommand(
                query = ::defaultDocQuery,
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
    fun `doc answers a type from the paired sources jar`() {
        val run = run(listOf("doc") + fixtureArgs("dev.jdx.fixtures.Generics"))
        run.exit shouldBe 0
        run.output shouldContain "dev.jdx.fixtures.Generics"
        run.output shouldContain "break naive descriptor-only readers"
        run.output shouldContain "-sources.jar · dev/jdx/fixtures/Generics.java:"
    }

    @Test
    fun `undocumented member exits 1 while overloads exit 2`() {
        val missing = run(listOf("doc") + fixtureArgs("dev.jdx.fixtures.Generics#identity(U)"))
        missing.exit shouldBe 1
        missing.output shouldContain "no javadoc comment"
        val ambiguous = run(listOf("doc") + fixtureArgs("dev.jdx.fixtures.CovariantOverrides\$Child#copy"))
        ambiguous.exit shouldBe 2
        ambiguous.output shouldContain "ambiguous:"
    }

    @Test
    fun `max-lines shapes the output`() {
        val cut = run(listOf("doc") + fixtureArgs("dev.jdx.fixtures.Generics", "--max-lines", "1"))
        cut.exit shouldBe 0
        cut.output shouldContain "1 of 2 lines shown (--max-lines 2 to see more)"
    }

    @Test
    fun `doc json parses and carries the envelope`() {
        val run = run(listOf("doc") + fixtureArgs("dev.jdx.fixtures.Generics", "--json"))
        run.exit shouldBe 0
        val parsed = Json.parseToJsonElement(run.output.trim()).jsonObject
        parsed["command"]?.jsonPrimitive?.content shouldBe "doc"
        parsed["ok"]?.jsonPrimitive?.content shouldBe "true"
        run.output shouldContain "\"origin\":\"sources\""
    }

    @Test
    fun `running twice yields identical bytes`() {
        val ref = "dev.jdx.fixtures.Generics"
        run(listOf("doc") + fixtureArgs(ref)).output shouldBe
            run(listOf("doc") + fixtureArgs(ref)).output
        run(listOf("doc") + fixtureArgs(ref, "--json")).output shouldBe
            run(listOf("doc") + fixtureArgs(ref, "--json")).output
    }

    @Test
    fun `no roots at all exits 4 and bad flags exit 3`() {
        val bare = run(listOf("doc", "dev.jdx.fixtures.Generics", "--no-jdk"))
        bare.exit shouldBe 4
        bare.output shouldContain "no workspace"
        val both = run(listOf("doc") + fixtureArgs("dev.jdx.fixtures.Generics", "--inherited", "--no-inherited"))
        both.exit shouldBe 3
        both.output shouldContain "mutually exclusive"
    }

    @Test
    fun `querying the marker fixture never loads it (D-017)`() {
        val marker = File(System.getProperty("java.io.tmpdir"), "jdx-fixture-static-init-marker")
        marker.exists() shouldBe false
        val run = run(listOf("doc") + fixtureArgs("dev.jdx.fixtures.StaticInitMarker"))
        run.exit shouldBe 0
        run.output shouldContain "source: "
        run.output shouldNotContain "jdx-fixture-static-init-marker"
        marker.exists() shouldBe false
    }
}
