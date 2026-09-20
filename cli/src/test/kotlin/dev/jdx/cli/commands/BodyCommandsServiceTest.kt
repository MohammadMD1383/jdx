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
 * Behaviour of `body` against real artifacts (T-022, tier 2): the fixture corpus
 * jar with its sibling `-sources.jar`.
 *
 * Rendering itself is pinned by the golden tests; here the assertions are
 * structural — flags, exit codes, determinism and the D-017 never-load proof.
 */
@Tag("tier2")
class BodyCommandsServiceTest {

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
     * The body command with the real service but a throwing exit (tier-1 style).
     * An isolated store and empty environment: the contributor's ambient
     * `active-workspace` (e.g. `fx`) must not re-root these assertions
     * (the T-066/T-070 trap).
     */
    private object JdxTestCli {
        private val noDiscovery: ProjectDiscoveryFn = { _, _, _, _ -> null }
        private val noEnv: (String) -> String? = { null }

        fun parse(args: List<String>) {
            require(args.firstOrNull() == "body") { "expected a body invocation, got: $args" }
            BodyCommand(
                query = ::defaultBodyQuery,
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
    fun `body answers from the paired sources jar`() {
        val run = run(listOf("body") + fixtureArgs("dev.jdx.fixtures.Generics#identity(java.lang.Object)"))
        run.exit shouldBe 0
        run.output shouldContain "dev.jdx.fixtures.Generics#identity(java.lang.Object)"
        run.output shouldContain "return value;"
        run.output shouldContain "-sources.jar · dev/jdx/fixtures/Generics.java:"
    }

    @Test
    fun `under-specified overloads exit 2 with candidates`() {
        val run = run(listOf("body") + fixtureArgs("dev.jdx.fixtures.CovariantOverrides\$Child#copy"))
        run.exit shouldBe 2
        run.output shouldContain "ambiguous:"
        run.output shouldContain "hint: re-run with one of the refs above"
    }

    @Test
    fun `unknown member exits 1 and a type ref exits 3`() {
        val missing = run(listOf("body") + fixtureArgs("dev.jdx.fixtures.Generics#noSuch()"))
        missing.exit shouldBe 1
        missing.output shouldContain "not found:"
        val typeRef = run(listOf("body") + fixtureArgs("dev.jdx.fixtures.Generics"))
        typeRef.exit shouldBe 3
        typeRef.output shouldContain "T-023"
    }

    @Test
    fun `context line-numbers and max-lines shape the output`() {
        val plain = run(listOf("body") + fixtureArgs("dev.jdx.fixtures.Generics#identity(java.lang.Object)"))
        val dressed = run(
            listOf("body") + fixtureArgs(
                "dev.jdx.fixtures.Generics#identity(java.lang.Object)",
                "--context", "1", "--line-numbers",
            ),
        )
        dressed.exit shouldBe 0
        dressed.output shouldContain "|"
        dressed.output.lines().size shouldBe plain.output.lines().size + 2
        val cut = run(
            listOf("body") + fixtureArgs(
                "dev.jdx.fixtures.Generics#identity(java.lang.Object)", "--max-lines", "1",
            ),
        )
        cut.exit shouldBe 0
        cut.output shouldContain "1 of 3 lines shown (--max-lines 3 to see more)"
    }

    @Test
    fun `body json parses and carries the envelope`() {
        val run = run(
            listOf("body") + fixtureArgs("dev.jdx.fixtures.Generics#identity(java.lang.Object)", "--json"),
        )
        run.exit shouldBe 0
        val parsed = Json.parseToJsonElement(run.output.trim()).jsonObject
        parsed["command"]?.jsonPrimitive?.content shouldBe "body"
        parsed["ok"]?.jsonPrimitive?.content shouldBe "true"
        run.output shouldContain "\"origin\":\"sources\""
    }

    @Test
    fun `running twice yields identical bytes`() {
        val ref = "dev.jdx.fixtures.Generics#identity(java.lang.Object)"
        run(listOf("body") + fixtureArgs(ref)).output shouldBe
            run(listOf("body") + fixtureArgs(ref)).output
        run(listOf("body") + fixtureArgs(ref, "--json")).output shouldBe
            run(listOf("body") + fixtureArgs(ref, "--json")).output
    }

    @Test
    fun `no roots at all exits 4 and deferred flags exit 3`() {
        val bare = run(listOf("body", "dev.jdx.fixtures.Generics#identity(java.lang.Object)", "--no-jdk"))
        bare.exit shouldBe 4
        bare.output shouldContain "no workspace"
        val engine = run(listOf("body") + fixtureArgs("dev.jdx.fixtures.Generics#identity(java.lang.Object)", "--engine", "javap"))
        engine.exit shouldBe 3
        engine.output shouldContain "T-027"
    }

    @Test
    fun `querying the marker fixture never loads it (D-017)`() {
        val marker = File(System.getProperty("java.io.tmpdir"), "jdx-fixture-static-init-marker")
        marker.exists() shouldBe false
        val run = run(listOf("body") + fixtureArgs("dev.jdx.fixtures.StaticInitMarker#answer()"))
        run.exit shouldBe 0
        run.output shouldContain "return 42;"
        run.output shouldNotContain "Exception"
        marker.exists() shouldBe false
    }
}
