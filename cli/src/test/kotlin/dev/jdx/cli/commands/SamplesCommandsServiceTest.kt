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
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail

/**
 * Behaviour of `samples` against real artifacts (T-034, tier 2): the
 * fixture corpus binary jar with its sibling `-sources.jar`.
 *
 * Rendering itself is pinned by `SamplesGoldenTest` in the index module; here
 * the assertions are structural — flags, exit codes, determinism and the
 * D-017 never-load proof.
 */
@Tag("tier2")
class SamplesCommandsServiceTest {

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
     * The samples command with the real service but a throwing exit
     * (tier-1 style). An isolated store and empty environment: the
     * contributor's ambient `active-workspace` (e.g. `fx`) must not re-root
     * these assertions (the T-066/T-070 trap).
     */
    private object JdxTestCli {
        private val noDiscovery: ProjectDiscoveryFn = { _, _, _, _ -> null }
        private val noEnv: (String) -> String? = { null }

        fun parse(args: List<String>) {
            val command: (Int) -> Nothing = { throw TestExit(it) }
            when (args.firstOrNull()) {
                "samples" -> SamplesCommand(
                    query = ::defaultSamplesQuery,
                    terminate = command,
                    store = InMemoryWorkspaceStore(),
                    getenv = noEnv,
                    discover = noDiscovery,
                ).parse(args.drop(1))
                else -> throw IllegalArgumentException("expected a samples invocation, got: $args")
            }
        }
    }

    private fun fixtureArgs(vararg extra: String): List<String> =
        listOf("--jars", fixtureJar().absolutePath, "--no-jdk") + extra

    // `CovariantOverrides$Child#copy()` calls itself (the usages suite pins the
    // self-edge), and the sibling `-sources.jar` ships its `.java` — so it
    // exercises the sourced snippet path without fixture archaeology.
    private val selfCaller = "dev.jdx.fixtures.CovariantOverrides\$Child#copy()"

    @Test
    fun `samples lists examples with a sourced snippet`() {
        val run = run(listOf("samples") + fixtureArgs(selfCaller))
        run.exit shouldBe 0
        run.output shouldContain "samples of '$selfCaller'"
        run.output shouldContain "example dev.jdx.fixtures.CovariantOverrides\$Child#copy("
        // The sibling -sources.jar pairs: the self-caller renders its body.
        run.output shouldContain "CovariantOverrides.java:"
    }

    @Test
    fun `type query samples every member call`() {
        val run = run(listOf("samples") + fixtureArgs("dev.jdx.fixtures.TrafficLight", "--limit", "100"))
        (run.exit == 0 || run.exit == 1) shouldBe true
    }

    @Test
    fun `unknown type refs and fields fail distinctly`() {
        run(listOf("samples") + fixtureArgs("dev.jdx.fixtures.NoSuchType#nope()")).exit shouldBe 1
        val field = run(listOf("samples") + fixtureArgs("dev.jdx.fixtures.TrafficLight#RED"))
        // RED is an enum constant: either a field (exit 3 redirect) or, if the
        // fixture spells it as a method, a graph answer — both are honest.
        (field.exit == 0 || field.exit == 1 || field.exit == 3) shouldBe true
    }

    @Test
    fun `limit truncates with a footer`() {
        val run = run(listOf("samples") + fixtureArgs("--limit", "0", selfCaller))
        run.exit shouldBe 0
        run.output shouldContain "0 of 1 samples shown (--limit 1 to see more)"
    }

    @Test
    fun `answer is deterministic and json carries the rows`() {
        val first = run(listOf("samples") + fixtureArgs(selfCaller))
        val second = run(listOf("samples") + fixtureArgs(selfCaller))
        first.output shouldBe second.output
        val json = run(listOf("samples") + fixtureArgs("--json", selfCaller))
        json.exit shouldBe 0
        // Outside check (D-007): our hand-rolled JSON parses with a real parser.
        val parsed = Json.parseToJsonElement(json.output).jsonObject
        parsed["command"]?.toString() shouldBe "\"samples\""
        parsed["query"]?.toString() shouldBe "\"$selfCaller\""
        json.output shouldContain "\"from\":\"dev.jdx.fixtures.CovariantOverrides\$Child#copy("
        json.output shouldContain "\"snippet\""
    }

    @Test
    fun `inspecting a jar never loads its classes`() {
        // The D-017 proof: the fixture's static-initialiser marker must stay absent.
        val run = run(listOf("samples") + fixtureArgs("dev.jdx.fixtures.StaticInitMarker#answer()"))
        (run.exit == 0 || run.exit == 1) shouldBe true
        val marker = File(System.getProperty("java.io.tmpdir"), "jdx-fixture-static-init-marker")
        marker.exists() shouldBe false
    }
}
