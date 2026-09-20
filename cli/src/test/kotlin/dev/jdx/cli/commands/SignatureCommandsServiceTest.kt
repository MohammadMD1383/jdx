package dev.jdx.cli.commands

import com.github.ajalt.clikt.core.parse
import dev.jdx.index.workspace.InMemoryWorkspaceStore
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
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
 * Behaviour of `signature` against real artifacts (T-024, tier 2): the
 * fixture corpus binary jar (sources never read — signatures are
 * bytecode-only).
 *
 * Rendering itself is pinned by the golden tests; here the assertions are
 * structural — flags, exit codes, determinism and the D-017 never-load proof.
 */
@Tag("tier2")
class SignatureCommandsServiceTest {

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
     * The signature command with the real service but a throwing exit
     * (tier-1 style). An isolated store and empty environment: the
     * contributor's ambient `active-workspace` (e.g. `fx`) must not re-root
     * these assertions (the T-066/T-070 trap).
     */
    private object JdxTestCli {
        private val noDiscovery: ProjectDiscoveryFn = { _, _, _, _ -> null }
        private val noEnv: (String) -> String? = { null }

        fun parse(args: List<String>) {
            require(args.firstOrNull() == "signature") { "expected a signature invocation, got: $args" }
            SignatureCommand(
                query = ::defaultSignatureQuery,
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
    fun `signature answers from bytecode without sources`() {
        val run = run(listOf("signature") + fixtureArgs("dev.jdx.fixtures.Generics#identity(U)"))
        run.exit shouldBe 0
        run.output shouldContain "signatures of dev.jdx.fixtures.Generics#identity"
        run.output shouldContain "public U identity(U value)"
        run.output shouldContain "(bytecode)"
    }

    @Test
    fun `under-specified overloads list every overload with exit 0`() {
        val run = run(listOf("signature") + fixtureArgs("dev.jdx.fixtures.CovariantOverrides\$Child#copy"))
        run.exit shouldBe 0
        run.output shouldContain "Child copy()"
    }

    @Test
    fun `include-synthetic reveals the bridge`() {
        val plain = run(listOf("signature") + fixtureArgs("dev.jdx.fixtures.CovariantOverrides\$Child#copy"))
        val synthetic = run(
            listOf("signature") + fixtureArgs(
                "dev.jdx.fixtures.CovariantOverrides\$Child#copy",
                "--include-synthetic",
            ),
        )
        synthetic.exit shouldBe 0
        (synthetic.output.lines().size > plain.output.lines().size) shouldBe true
    }

    @Test
    fun `unknown member exits 1 and a type ref exits 3`() {
        val missing = run(listOf("signature") + fixtureArgs("dev.jdx.fixtures.Generics#noSuch()"))
        missing.exit shouldBe 1
        missing.output shouldContain "not found:"
        val typeRef = run(listOf("signature") + fixtureArgs("dev.jdx.fixtures.Generics"))
        typeRef.exit shouldBe 3
        typeRef.output shouldContain "jdx show"
    }

    @Test
    fun `limit truncates with a footer`() {
        val run = run(
            listOf("signature") + fixtureArgs(
                "dev.jdx.fixtures.TrafficLight#seconds",
                "--limit", "1",
            ),
        )
        run.exit shouldBe 0
        run.output shouldContain "1 of 2 signatures shown (--limit 2 to see more)"
    }

    @Test
    fun `json carries the same signatures`() {
        val run = run(
            listOf("signature") + fixtureArgs(
                "dev.jdx.fixtures.Generics#identity(U)",
                "--json",
            ),
        )
        run.exit shouldBe 0
        val parsed = Json.parseToJsonElement(run.output).jsonObject
        parsed["ok"]?.jsonPrimitive?.content shouldBe "true"
        run.output shouldContain "public U identity(U value)"
    }

    @Test
    fun `same query twice renders identical bytes`() {
        val args = listOf("signature") + fixtureArgs("dev.jdx.fixtures.Generics#identity(U)")
        run(args).output shouldBe run(args).output
    }

    @Test
    fun `inspecting a jar never loads its classes`() {
        // The D-017 proof: the fixture's static-initialiser marker must stay absent.
        val run = run(listOf("signature") + fixtureArgs("dev.jdx.fixtures.StaticInitMarker#answer()"))
        run.exit shouldBe 0
        run.output shouldContain "public int answer()"
        val marker = File(System.getProperty("java.io.tmpdir"), "jdx-fixture-static-init-marker")
        marker.exists() shouldBe false
    }
}
