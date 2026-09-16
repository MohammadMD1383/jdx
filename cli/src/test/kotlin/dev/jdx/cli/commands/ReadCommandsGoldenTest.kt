package dev.jdx.cli.commands

import com.github.ajalt.clikt.core.parse
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

    @Test
    fun `text and json goldens cover every fixture class for show members and outline`() {
        val jar = fixtureBinaryJar()
        val names = fixtureClassNames(jar)
        var updated = 0
        for (binaryName in names) {
            val roots = listOf("--jars", jar.absolutePath, "--no-jdk")
            updated += checkGolden("show", "$binaryName.txt", runShow(binaryName, roots, json = false, jar))
            updated += checkGolden("show", "$binaryName.json", runShow(binaryName, roots, json = true, jar))
            updated += checkGolden("members", "$binaryName.txt", runMembers(binaryName, roots, json = false, jar))
            updated += checkGolden("members", "$binaryName.json", runMembers(binaryName, roots, json = true, jar))
            updated += checkGolden("outline", "$binaryName.txt", runOutline(binaryName, roots, json = false, jar))
            updated += checkGolden("outline", "$binaryName.json", runOutline(binaryName, roots, json = true, jar))
        }
        failOnOrphans("show", names.map { "$it.txt" }.toSet() + names.map { "$it.json" }.toSet())
        failOnOrphans("members", names.map { "$it.txt" }.toSet() + names.map { "$it.json" }.toSet())
        failOnOrphans("outline", names.map { "$it.txt" }.toSet() + names.map { "$it.json" }.toSet())
        if (updateMode()) println("golden.update: rewrote $updated file(s)")
    }

    private fun runShow(binary: String, roots: List<String>, json: Boolean, jar: File): String {
        val args = listOf(binary) + roots + (if (json) listOf("--json") else emptyList())
        return normalize(execute { ShowCommand(terminate = noExit).parse(args) }, jar)
    }

    private fun runMembers(binary: String, roots: List<String>, json: Boolean, jar: File): String {
        val args = listOf(binary) + roots + (if (json) listOf("--json") else emptyList())
        return normalize(execute { MembersCommand(terminate = noExit).parse(args) }, jar)
    }

    private fun runOutline(binary: String, roots: List<String>, json: Boolean, jar: File): String {
        val args = listOf(binary) + roots + (if (json) listOf("--json") else emptyList())
        return normalize(execute { OutlineCommand(terminate = noExit).parse(args) }, jar)
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

    private fun checkGolden(command: String, fileName: String, actual: String): Int {
        val dir = File("src/test/resources/golden/$command")
        val file = File(dir, fileName)
        if (updateMode()) {
            file.parentFile.mkdirs()
            file.writeText(actual + "\n")
            return 1
        }
        if (!file.isFile) fail("missing golden file: ${file.path} (run with -Pgolden.update=true to create it)")
        val expected = file.readText().removeSuffix("\n")
        if (expected != actual) {
            fail("golden mismatch: ${file.path}\n${firstDivergence(expected, actual)}")
        }
        return 0
    }

    private fun failOnOrphans(command: String, expected: Set<String>) {
        val dir = File("src/test/resources/golden/$command")
        if (!dir.isDirectory) return
        val orphans = dir.listFiles { file -> file.isFile }!!.map { it.name }.toSet() - expected
        if (orphans.isNotEmpty()) {
            fail("orphaned golden files (no fixture references them): ${orphans.sorted()}")
        }
    }

    private fun firstDivergence(expected: String, actual: String): String {
        val expectedLines = expected.lines()
        val actualLines = actual.lines()
        val diverge = expectedLines.zip(actualLines).indexOfFirst { (a, b) -> a != b }
            .takeIf { it != -1 } ?: minOf(expectedLines.size, actualLines.size)
        fun window(lines: List<String>): String = ((diverge - 2)..(diverge + 2))
            .filter { it in lines.indices }
            .joinToString("\n") { i -> "${i + 1}: ${lines[i]}" }
        return "first divergence at line ${diverge + 1}:\nexpected:\n${window(expectedLines)}\nactual:\n${window(actualLines)}"
    }

    private fun updateMode(): Boolean = System.getProperty("jdx.golden.update") == "true"

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
