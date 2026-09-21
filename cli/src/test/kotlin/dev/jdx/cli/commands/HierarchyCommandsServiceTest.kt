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
 * Behaviour of `hierarchy` and `implementors` against real artifacts (T-032,
 * tier 2): the fixture corpus binary jar.
 *
 * Rendering itself is pinned by `HierarchyGoldenTest` in the index module;
 * here the assertions are structural — flags, exit codes, determinism and the
 * D-017 never-load proof.
 */
@Tag("tier2")
class HierarchyCommandsServiceTest {

    private class TestExit(val code: Int) : RuntimeException()

    private fun fixtureJar(): File {
        val dir = System.getProperty("jdx.fixturesDir")?.let { File(it) }
            ?: fail("jdx.fixturesDir not set (cli build wires it; see cli/build.gradle.kts)")
        return dir.listFiles { file ->
            file.isFile && file.name.startsWith("testfixtures-") && !file.name.endsWith("-sources.jar")
        }?.singleOrNull() ?: fail("expected exactly one binary fixture jar in $dir")
    }

    private data class Run(val output: String, val exit: Int)

    /**
     * Either hierarchy-family command with the real service but a throwing
     * exit (tier-1 style). An isolated store and empty environment: the
     * contributor's ambient `active-workspace` (e.g. `fx`) must not re-root
     * these assertions (the T-066/T-070 trap).
     */
    private fun run(args: List<String>): Run {
        val original = System.out
        val buffer = ByteArrayOutputStream()
        System.setOut(PrintStream(buffer))
        val exit = try {
            val command = when (args.firstOrNull()) {
                "implementors" -> ImplementorsCommand(
                    query = ::defaultHierarchyQuery,
                    terminate = { throw TestExit(it) },
                    store = InMemoryWorkspaceStore(),
                    getenv = { null },
                    discover = { _, _, _, _ -> null },
                )
                else -> HierarchyCommand(
                    query = ::defaultHierarchyQuery,
                    terminate = { throw TestExit(it) },
                    store = InMemoryWorkspaceStore(),
                    getenv = { null },
                    discover = { _, _, _, _ -> null },
                )
            }
            command.parse(args.drop(1))
            0
        } catch (e: TestExit) {
            e.code
        } finally {
            System.setOut(original)
        }
        return Run(buffer.toString(Charsets.UTF_8), exit)
    }

    private fun fixtureArgs(vararg extra: String): List<String> =
        listOf("--jars", fixtureJar().absolutePath, "--no-jdk") + extra

    @Test
    fun `hierarchy of a sealed interface lists its permitted implementors`() {
        val run = run(listOf("hierarchy") + fixtureArgs("dev.jdx.fixtures.SealedHierarchy"))
        run.exit shouldBe 0
        run.output shouldContain "hierarchy of 'dev.jdx.fixtures.SealedHierarchy'"
        run.output shouldContain "dev.jdx.fixtures.SealedHierarchy\$Circle"
        run.output shouldContain "dev.jdx.fixtures.SealedHierarchy\$Rect"
    }

    @Test
    fun `hierarchy up names the superclass`() {
        val run = run(
            listOf("hierarchy") + fixtureArgs(
                "--up",
                "dev.jdx.fixtures.CovariantOverrides\$Child",
            ),
        )
        run.exit shouldBe 0
        run.output shouldContain "extends dev.jdx.fixtures.CovariantOverrides\$Base"
        run.output shouldNotContain "subtypes"
    }

    @Test
    fun `implementors matches hierarchy down`() {
        val hierarchy = run(
            listOf("hierarchy") + fixtureArgs(
                "--down",
                "dev.jdx.fixtures.SealedHierarchy",
            ),
        )
        val implementors = run(
            listOf("implementors") + fixtureArgs("dev.jdx.fixtures.SealedHierarchy"),
        )
        hierarchy.exit shouldBe 0
        implementors.exit shouldBe 0
        // Same down rows; only the headers differ (supertypes section omitted
        // in both, since neither asks for it).
        implementors.output shouldContain "dev.jdx.fixtures.SealedHierarchy\$Circle"
        implementors.output shouldContain "dev.jdx.fixtures.SealedHierarchy\$Rect"
        implementors.output shouldNotContain "supertypes"
    }

    @Test
    fun `unknown symbol is exit 1 and member ref is exit 3`() {
        val unknown = run(listOf("hierarchy") + fixtureArgs("dev.jdx.fixtures.NoSuchType"))
        unknown.exit shouldBe 1
        val member = run(listOf("hierarchy") + fixtureArgs("dev.jdx.fixtures.Generics#identity"))
        member.exit shouldBe 3
        member.output shouldContain "takes a type reference"
    }

    @Test
    fun `answer is deterministic and json carries the rows`() {
        val first = run(listOf("hierarchy") + fixtureArgs("dev.jdx.fixtures.SealedHierarchy"))
        val second = run(listOf("hierarchy") + fixtureArgs("dev.jdx.fixtures.SealedHierarchy"))
        first.output shouldBe second.output
        val json = run(listOf("hierarchy") + fixtureArgs("--json", "dev.jdx.fixtures.SealedHierarchy"))
        json.exit shouldBe 0
        // Outside check (D-007): our hand-rolled JSON parses with a real parser.
        val parsed = Json.parseToJsonElement(json.output).jsonObject
        parsed["command"]?.toString() shouldBe "\"hierarchy\""
        parsed["query"]?.toString() shouldBe "\"dev.jdx.fixtures.SealedHierarchy\""
        json.output shouldContain "dev.jdx.fixtures.SealedHierarchy\$Circle"
    }

    @Test
    fun `inspecting a jar never loads its classes`() {
        // The D-017 proof: the fixture's static-initialiser marker must stay absent.
        val run = run(listOf("hierarchy") + fixtureArgs("dev.jdx.fixtures.StaticInitMarker"))
        run.exit shouldBe 0
        val marker = File(System.getProperty("java.io.tmpdir"), "jdx-fixture-static-init-marker")
        marker.exists() shouldBe false
    }
}
