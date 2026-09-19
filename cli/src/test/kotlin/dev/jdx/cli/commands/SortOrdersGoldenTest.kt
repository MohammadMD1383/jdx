package dev.jdx.cli.commands

import com.github.ajalt.clikt.core.parse
import dev.jdx.testsupport.fixtures.FixtureJars
import dev.jdx.testsupport.golden.GoldenFiles
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail

/**
 * Golden tests for the three `--sort` orders (T-062): every order renders once
 * as text and once as JSON per covered class, pinned to committed files under
 * `src/test/resources/golden/members-sort/`.
 *
 * The covered classes are one generic (`Generics`), one nested
 * (`Nesting$Inner`) and one Kotlin (`KotlinMembers`) fixture — the acceptance
 * minimum. The lookup is the fixture corpus alone (`--no-jdk`) — hermetic, so
 * goldens are byte-identical on every machine (D-028 §7: no JRT, fixed
 * artifact label). `outline` shares the listing model and is covered
 * structurally (exit 0 + row parity) in `ReadCommandsServiceTest`.
 * Rewrite with `./gradlew :cli:tier2Test -Pgolden.update=true` — then read the
 * diff before committing it. A golden updated without reading is a test deleted.
 */
@Tag("tier2")
class SortOrdersGoldenTest {

    private class TestExit(val code: Int) : RuntimeException()

    private val noExit: (Int) -> Nothing = { throw TestExit(it) }

    // Goldens pin exact bytes (D-028 hermeticity): project auto-discovery stays off
    // here — it is covered structurally in ReadCommandDiscoveryTest.
    private val noDiscovery: ProjectDiscoveryFn = { _, _, _, _ -> null }

    // One generic, one nested and one Kotlin fixture (the acceptance minimum).
    // `TrafficLight$1` is the strongest case: an enum-constant body inheriting
    // from `TrafficLight`, so `declaring` (alphabetical) visibly differs from
    // `kind` (linearisation) and `name` (flat) reorders fields before methods.
    // `KotlinMembers` differentiates `name` from `kind`; `Generics` coincides
    // in all three orders (single group, alphabetical members) and pins that
    // honest no-op.
    private val classes = listOf(
        "dev.jdx.fixtures.Generics",
        "dev.jdx.fixtures.TrafficLight\$1",
        "dev.jdx.fixtures.KotlinMembers",
    )

    private val sorts = listOf("kind", "name", "declaring")

    @Test
    fun `text and json goldens cover every sort order`() {
        val jar = FixtureJars.binaryJar()
        val roots = listOf("--jars", jar.absolutePath, "--no-jdk")
        val contents = buildMap {
            for (binaryName in classes) {
                for (sort in sorts) {
                    put("$binaryName.$sort.txt", normalize(runMembers(binaryName, roots, sort, false), jar))
                    put("$binaryName.$sort.json", normalize(runMembers(binaryName, roots, sort, true), jar))
                }
            }
        }
        GoldenFiles.verifyAll(File("src/test/resources/golden/members-sort"), contents)
    }

    private fun runMembers(binary: String, roots: List<String>, sort: String, json: Boolean): String {
        val args = listOf(binary) + roots + listOf("--sort", sort) + (if (json) listOf("--json") else emptyList())
        return execute { MembersCommand(terminate = noExit, discover = noDiscovery).parse(args) }
    }

    private fun execute(block: () -> Unit): String {
        val original = System.out
        val buffer = ByteArrayOutputStream()
        System.setOut(PrintStream(buffer))
        try {
            block()
        } catch (e: TestExit) {
            fail("members command exited ${e.code} during golden capture (expected exit 0)")
        } finally {
            System.setOut(original)
        }
        return buffer.toString(Charsets.UTF_8).trimEnd()
    }

    /** The real jar file name carries a version string — it must never leak into goldens. */
    private fun normalize(output: String, jar: File): String =
        output.replace(jar.name, "fixture-corpus.jar")
}
