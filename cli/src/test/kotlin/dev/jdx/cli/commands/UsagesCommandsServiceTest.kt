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
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail

/**
 * Behaviour of `usages` against real artifacts (T-030, tier 2): the fixture
 * corpus binary jar.
 *
 * Rendering itself is pinned by `UsagesGoldenTest` in the index module; here
 * the assertions are structural — flags, exit codes, determinism and the
 * D-017 never-load proof.
 */
@Tag("tier2")
class UsagesCommandsServiceTest {

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
     * The usages command with the real service but a throwing exit
     * (tier-1 style). An isolated store and empty environment: the
     * contributor's ambient `active-workspace` (e.g. `fx`) must not re-root
     * these assertions (the T-066/T-070 trap).
     */
    private object JdxTestCli {
        private val noDiscovery: ProjectDiscoveryFn = { _, _, _, _ -> null }
        private val noEnv: (String) -> String? = { null }

        fun parse(args: List<String>) {
            require(args.firstOrNull() == "usages") { "expected a usages invocation, got: $args" }
            UsagesCommand(
                query = ::defaultUsagesQuery,
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
    fun `type query groups call sites by artifact`() {
        val run = run(listOf("usages") + fixtureArgs("dev.jdx.fixtures.TrafficLight"))
        run.exit shouldBe 0
        run.output shouldContain "usages of 'dev.jdx.fixtures.TrafficLight'"
        run.output shouldContain "testfixtures-"
        run.output shouldContain "read dev.jdx.fixtures.TrafficLight#values() -> #\$VALUES"
    }

    @Test
    fun `member query lists the referencing methods`() {
        val run = run(listOf("usages") + fixtureArgs("dev.jdx.fixtures.CovariantOverrides\$Child#copy()"))
        run.exit shouldBe 0
        run.output shouldContain "usages of 'dev.jdx.fixtures.CovariantOverrides\$Child#copy()'"
        run.output shouldContain "call dev.jdx.fixtures.CovariantOverrides\$Child#copy()"
    }

    @Test
    fun `kind narrows the hit set`() {
        val all = run(listOf("usages") + fixtureArgs("dev.jdx.fixtures.TrafficLight"))
        val calls = run(listOf("usages") + fixtureArgs("--kind", "call", "dev.jdx.fixtures.TrafficLight"))
        all.exit shouldBe 0
        calls.exit shouldBe 0
        calls.output shouldContain "call "
        calls.output shouldNotContain "read dev.jdx.fixtures.TrafficLight#"
        (calls.output.lines().size < all.output.lines().size) shouldBe true
    }

    @Test
    fun `deferred kind names its command`() {
        val run = run(listOf("usages") + fixtureArgs("--kind", "impl", "dev.jdx.fixtures.TrafficLight"))
        run.exit shouldBe 3
        run.output shouldContain "jdx hierarchy"
    }

    @Test
    fun `unknown symbol is exit 1 and unused symbol names itself`() {
        val unknown = run(listOf("usages") + fixtureArgs("dev.jdx.fixtures.NoSuchType"))
        unknown.exit shouldBe 1
        // Generics is referenced by no fixture method body.
        val unused = run(listOf("usages") + fixtureArgs("dev.jdx.fixtures.Generics"))
        unused.exit shouldBe 1
        unused.output shouldContain "no usages of 'dev.jdx.fixtures.Generics'"
    }

    @Test
    fun `answer is deterministic and json carries the rows`() {
        val first = run(listOf("usages") + fixtureArgs("dev.jdx.fixtures.TrafficLight"))
        val second = run(listOf("usages") + fixtureArgs("dev.jdx.fixtures.TrafficLight"))
        first.output shouldBe second.output
        val json = run(listOf("usages") + fixtureArgs("--json", "dev.jdx.fixtures.TrafficLight"))
        json.exit shouldBe 0
        // Outside check (D-007): our hand-rolled JSON parses with a real parser.
        val parsed = Json.parseToJsonElement(json.output).jsonObject
        parsed["command"]?.toString() shouldBe "\"usages\""
        parsed["query"]?.toString() shouldBe "\"dev.jdx.fixtures.TrafficLight\""
        json.output shouldContain "dev.jdx.fixtures.TrafficLight#values()"
    }

    @Test
    fun `inspecting a jar never loads its classes`() {
        // The D-017 proof: the fixture's static-initialiser marker must stay absent.
        val run = run(listOf("usages") + fixtureArgs("dev.jdx.fixtures.StaticInitMarker#answer()"))
        (run.exit == 0 || run.exit == 1) shouldBe true
        val marker = File(System.getProperty("java.io.tmpdir"), "jdx-fixture-static-init-marker")
        marker.exists() shouldBe false
    }

    @Test
    fun `src dir mentions appear as ref rows`() {
        val src = java.nio.file.Files.createTempDirectory("usages-cli-src")
        java.nio.file.Files.writeString(src.resolve("Use.java"), "class Use { dev.jdx.fixtures.TrafficLight t; }\n")
        val run = run(
            listOf("usages") + fixtureArgs("--src", src.toString(), "dev.jdx.fixtures.TrafficLight"),
        )
        run.exit shouldBe 0
        run.output shouldContain "  ref Use.java:1"
    }

    @Test
    fun `missing src dir exits 5 naming the path`() {
        val run = run(
            listOf("usages") + fixtureArgs("--src", "/no/such/src-dir-xyz", "dev.jdx.fixtures.TrafficLight"),
        )
        run.exit shouldBe 5
        run.output shouldContain "src-dir-xyz"
    }
}
