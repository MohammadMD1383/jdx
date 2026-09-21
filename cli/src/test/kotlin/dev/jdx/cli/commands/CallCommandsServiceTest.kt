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
 * Behaviour of `callers`/`calls` against real artifacts (T-033, tier 2): the
 * fixture corpus binary jar.
 *
 * Rendering itself is pinned by `CallGraphGoldenTest` in the index module; here
 * the assertions are structural — flags, exit codes, determinism and the
 * D-017 never-load proof.
 */
@Tag("tier2")
class CallCommandsServiceTest {

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
     * Both call commands with the real service but a throwing exit
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
                "callers" -> CallersCommand(
                    query = ::defaultCallGraphQuery,
                    terminate = command,
                    store = InMemoryWorkspaceStore(),
                    getenv = noEnv,
                    discover = noDiscovery,
                ).parse(args.drop(1))
                "calls" -> CallsCommand(
                    query = ::defaultCallGraphQuery,
                    terminate = command,
                    store = InMemoryWorkspaceStore(),
                    getenv = noEnv,
                    discover = noDiscovery,
                ).parse(args.drop(1))
                else -> throw IllegalArgumentException("expected a callers/calls invocation, got: $args")
            }
        }
    }

    private fun fixtureArgs(vararg extra: String): List<String> =
        listOf("--jars", fixtureJar().absolutePath, "--no-jdk") + extra

    // `CovariantOverrides$Child#copy()` calls itself (the usages suite pins the
    // self-edge), so it exercises both directions without fixture archaeology.
    private val selfCaller = "dev.jdx.fixtures.CovariantOverrides\$Child#copy()"

    @Test
    fun `callers lists the calling methods`() {
        val run = run(listOf("callers") + fixtureArgs(selfCaller))
        run.exit shouldBe 0
        run.output shouldContain "callers of '$selfCaller'"
        run.output shouldContain "call dev.jdx.fixtures.CovariantOverrides\$Child#copy("
    }

    @Test
    fun `calls lists the called methods`() {
        val run = run(listOf("calls") + fixtureArgs(selfCaller))
        run.exit shouldBe 0
        run.output shouldContain "calls from '$selfCaller'"
        run.output shouldContain "call dev.jdx.fixtures.CovariantOverrides\$Child#copy("
    }

    @Test
    fun `depth widens the tree`() {
        val shallow = run(listOf("callers") + fixtureArgs(selfCaller))
        val deep = run(listOf("callers") + fixtureArgs("--depth", "3", selfCaller))
        shallow.exit shouldBe 0
        deep.exit shouldBe 0
        (deep.output.lines().size >= shallow.output.lines().size) shouldBe true
    }

    @Test
    fun `external-only prunes the single fixture artifact`() {
        val run = run(listOf("calls") + fixtureArgs("--external-only", selfCaller))
        run.exit shouldBe 1
        run.output shouldContain "no calls from 'dev.jdx.fixtures.CovariantOverrides\$Child#copy'"
    }

    @Test
    fun `unknown type refs and fields fail distinctly`() {
        run(listOf("callers") + fixtureArgs("dev.jdx.fixtures.NoSuchType#nope()")).exit shouldBe 1
        val type = run(listOf("callers") + fixtureArgs("dev.jdx.fixtures.CovariantOverrides\$Child"))
        type.exit shouldBe 3
        type.output shouldContain "takes a member reference"
        val field = run(listOf("callers") + fixtureArgs("dev.jdx.fixtures.TrafficLight#RED"))
        // RED is an enum constant: either a field (exit 3 redirect) or, if the
        // fixture spells it as a method, a graph answer — both are honest.
        (field.exit == 0 || field.exit == 1 || field.exit == 3) shouldBe true
    }

    @Test
    fun `answer is deterministic and json carries the rows`() {
        val first = run(listOf("callers") + fixtureArgs(selfCaller))
        val second = run(listOf("callers") + fixtureArgs(selfCaller))
        first.output shouldBe second.output
        val json = run(listOf("callers") + fixtureArgs("--json", selfCaller))
        json.exit shouldBe 0
        // Outside check (D-007): our hand-rolled JSON parses with a real parser.
        val parsed = Json.parseToJsonElement(json.output).jsonObject
        parsed["command"]?.toString() shouldBe "\"callers\""
        parsed["query"]?.toString() shouldBe "\"$selfCaller\""
        json.output shouldContain "\"direction\":\"callers\""
        json.output shouldContain "dev.jdx.fixtures.CovariantOverrides\$Child#copy("
    }

    @Test
    fun `inspecting a jar never loads its classes`() {
        // The D-017 proof: the fixture's static-initialiser marker must stay absent.
        val run = run(listOf("calls") + fixtureArgs("dev.jdx.fixtures.StaticInitMarker#answer()"))
        (run.exit == 0 || run.exit == 1) shouldBe true
        val marker = File(System.getProperty("java.io.tmpdir"), "jdx-fixture-static-init-marker")
        marker.exists() shouldBe false
    }
}
