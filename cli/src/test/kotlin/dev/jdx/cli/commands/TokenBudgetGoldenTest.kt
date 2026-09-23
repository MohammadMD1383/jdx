package dev.jdx.cli.commands

import com.github.ajalt.clikt.core.parse
import dev.jdx.index.workspace.InMemoryWorkspaceStore
import dev.jdx.testsupport.fixtures.FixtureJars
import dev.jdx.testsupport.golden.GoldenFiles
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail

/**
 * Golden tests for the token-budget flags (T-047): `--brief` and `--max-lines`
 * render once as text per covered class, pinned to committed files under
 * `src/test/resources/golden/members-budget/`.
 *
 * Only text is pinned: both flags are text-only presentation by design
 * (`--json` bytes are byte-identical with or without them — pinned in
 * `ReadCommandsTest` and `ReadCommandsServiceTest` instead). JSON goldens for
 * these commands already live in `ReadCommandsGoldenTest`. The covered
 * classes are one generic (`Generics`), one inheriting (`TrafficLight$1`,
 * where brief visibly drops the inherited-from header) and one Kotlin
 * (`KotlinMembers`) fixture. The lookup is the fixture corpus alone
 * (`--no-jdk`) — hermetic, so goldens are byte-identical on every machine
 * (D-028 §7: no JRT, fixed artifact label).
 * Rewrite with `./gradlew :cli:tier2Test -Pgolden.update=true` — then read the
 * diff before committing it. A golden updated without reading is a test deleted.
 */
@Tag("tier2")
class TokenBudgetGoldenTest {

    private class TestExit(val code: Int) : RuntimeException()

    private val noExit: (Int) -> Nothing = { throw TestExit(it) }

    // Goldens pin exact bytes (D-028 hermeticity): project auto-discovery stays off
    // here — it is covered structurally in ReadCommandDiscoveryTest.
    private val noDiscovery: ProjectDiscoveryFn = { _, _, _, _ -> null }

    // Hermetic roots (T-070, the T-066 trap in tier 2): the contributor's ambient
    // `~/.config/jdx/active-workspace` (e.g. `fx`) must not re-root golden capture.
    private val noEnv: (String) -> String? = { null }

    private val classes = listOf(
        "dev.jdx.fixtures.Generics",
        "dev.jdx.fixtures.TrafficLight\$1",
        "dev.jdx.fixtures.KotlinMembers",
    )

    @Test
    fun `brief and max-lines goldens cover the budget flags`() {
        val jar = FixtureJars.binaryJar()
        val roots = listOf("--jars", jar.absolutePath, "--no-jdk")
        val contents = buildMap {
            for (binaryName in classes) {
                put("$binaryName.brief.txt", normalize(runMembers(binaryName, roots, listOf("--brief")), jar))
                put(
                    "$binaryName.max-lines.txt",
                    normalize(runMembers(binaryName, roots, listOf("--max-lines", "6")), jar),
                )
            }
        }
        GoldenFiles.verifyAll(File("src/test/resources/golden/members-budget"), contents)
    }

    private fun runMembers(binary: String, roots: List<String>, budget: List<String>): String {
        val args = listOf(binary) + roots + budget
        return execute { MembersCommand(terminate = noExit, store = InMemoryWorkspaceStore(), getenv = noEnv, discover = noDiscovery).parse(args) }
    }

    private fun execute(block: () -> Unit): String {
        val original = System.out
        val buffer = ByteArrayOutputStream()
        System.setOut(PrintStream(buffer))
        try {
            block()
        } catch (e: TestExit) {
            fail("members exited ${e.code} during golden capture (expected exit 0)")
        } finally {
            System.setOut(original)
        }
        return buffer.toString(Charsets.UTF_8).trimEnd()
    }

    /** The real jar file name carries a version string — it must never leak into goldens. */
    private fun normalize(output: String, jar: File): String =
        output.replace(jar.name, "fixture-corpus.jar")
}
