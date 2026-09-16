package dev.jdx.cli.commands

import com.github.ajalt.clikt.core.parse
import dev.jdx.testsupport.golden.GoldenFiles
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.util.zip.ZipFile
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail

/**
 * Golden tests for the three read commands (T-011): every fixture class renders once
 * as text and once as JSON per command, pinned to committed files under
 * `src/test/resources/golden/{show,members,outline}/`.
 *
 * The lookup is the fixture corpus alone (`--no-jdk`) — hermetic, so goldens are
 * byte-identical on every machine (D-028 §7: no JRT, fixed artifact label). The JDK
 * path is covered structurally (never byte-pinned) in `ReadCommandsServiceTest`.
 * Rewrite with `./gradlew :cli:tier2Test -Pgolden.update=true` — then read the diff
 * before committing it. A golden updated without reading is a test deleted.
 */
@Tag("tier2")
class ReadCommandsGoldenTest {

    private class TestExit(val code: Int) : RuntimeException()

    private val noExit: (Int) -> Nothing = { throw TestExit(it) }

    // Goldens pin exact bytes (D-028 hermeticity): project auto-discovery stays off
    // here — it is covered structurally in ReadCommandDiscoveryTest.
    private val noDiscovery: ProjectDiscoveryFn = { _, _, _, _ -> null }

    @Test
    fun `text and json goldens cover every fixture class for show members and outline`() {
        val jar = fixtureBinaryJar()
        val names = fixtureClassNames(jar)
        val roots = listOf("--jars", jar.absolutePath, "--no-jdk")
        for ((command, run) in commands()) {
            val contents = buildMap {
                for (binaryName in names) {
                    put("$binaryName.txt", normalize(run(binaryName, roots, false), jar))
                    put("$binaryName.json", normalize(run(binaryName, roots, true), jar))
                }
            }
            GoldenFiles.verifyAll(File("src/test/resources/golden/$command"), contents)
        }
    }

    /**
     * One runner per read command: binary name, common roots, and the `--json`
     * switch in, rendered stdout out. The map above keeps the golden layout
     * (one directory per command) while sharing all comparison logic.
     */
    private fun commands(): List<Pair<String, (String, List<String>, Boolean) -> String>> = listOf(
        "show" to { binary, roots, json -> runShow(binary, roots, json) },
        "members" to { binary, roots, json -> runMembers(binary, roots, json) },
        "outline" to { binary, roots, json -> runOutline(binary, roots, json) },
    )

    private fun runShow(binary: String, roots: List<String>, json: Boolean): String {
        val args = listOf(binary) + roots + (if (json) listOf("--json") else emptyList())
        return execute { ShowCommand(terminate = noExit, discover = noDiscovery).parse(args) }
    }

    private fun runMembers(binary: String, roots: List<String>, json: Boolean): String {
        val args = listOf(binary) + roots + (if (json) listOf("--json") else emptyList())
        return execute { MembersCommand(terminate = noExit, discover = noDiscovery).parse(args) }
    }

    private fun runOutline(binary: String, roots: List<String>, json: Boolean): String {
        val args = listOf(binary) + roots + (if (json) listOf("--json") else emptyList())
        return execute { OutlineCommand(terminate = noExit, discover = noDiscovery).parse(args) }
    }

    private fun execute(block: () -> Unit): String {
        val original = System.out
        val buffer = ByteArrayOutputStream()
        System.setOut(PrintStream(buffer))
        try {
            block()
        } catch (e: TestExit) {
            fail("read command exited ${e.code} during golden capture (expected exit 0)")
        } finally {
            System.setOut(original)
        }
        return buffer.toString(Charsets.UTF_8).trimEnd()
    }

    /** The real jar file name carries a version string — it must never leak into goldens. */
    private fun normalize(output: String, jar: File): String =
        output.replace(jar.name, "fixture-corpus.jar")

    private fun fixtureBinaryJar(): File {
        val dir = System.getProperty("jdx.fixturesDir")?.let { File(it) }
            ?: fail("jdx.fixturesDir not set (cli build wires it; see cli/build.gradle.kts)")
        val jars = dir.listFiles { file ->
            file.isFile && file.name.startsWith("testfixtures-") && !file.name.endsWith("-sources.jar")
        }?.toList().orEmpty()
        if (jars.size != 1) fail("expected exactly one binary fixture jar in $dir, found: $jars")
        return jars.single()
    }

    private fun fixtureClassNames(jar: File): List<String> = ZipFile(jar).use { zip ->
        zip.entries().asSequence()
            .map { it.name }
            .filter { it.endsWith(".class") && it != "module-info.class" }
            .map { it.removeSuffix(".class").replace('/', '.') }
            .sorted()
            .toList()
    }
}
