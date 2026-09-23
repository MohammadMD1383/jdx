package dev.jdx.cli.commands

import com.github.ajalt.clikt.core.parse
import dev.jdx.cli.BuildInfo
import dev.jdx.index.workspace.InMemoryWorkspaceStore
import dev.jdx.testsupport.fixtures.FixtureJars
import dev.jdx.testsupport.golden.GoldenFiles
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * Tier 2: `jdx batch` against the real service over the fixture corpus jar
 * (T-045): a mixed ok/not-found/ambiguous/malformed stream is golden-pinned,
 * and every read line is byte-identical to the one-shot `--json` answer for
 * the same query (the T-046 parity proof in miniature).
 *
 * Hermetic like the read-command goldens (T-070): the fixture corpus alone
 * (`--no-jdk`), an isolated store, no environment, no project discovery.
 */
@Tag("tier2")
class BatchCommandsServiceTest {

    private class TestExit(val code: Int) : RuntimeException()

    private val noExit: (Int) -> Nothing = { throw TestExit(it) }
    private val noDiscovery: ProjectDiscoveryFn = { _, _, _, _ -> null }
    private val noEnv: (String) -> String? = { null }
    private val lenientJson: Json = Json { ignoreUnknownKeys = true }

    private data class Run(val lines: List<String>, val exit: Int)

    private fun runBatch(stdinText: String, roots: List<String>): Run {
        val printed = mutableListOf<String>()
        val exit = try {
            BatchCommand(
                terminate = noExit,
                store = InMemoryWorkspaceStore(),
                getenv = noEnv,
                discover = noDiscovery,
                stdin = { BufferedReader(stdinText.reader()) },
                printer = printed::add,
            ).parse(roots)
            0
        } catch (e: TestExit) {
            e.code
        }
        return Run(printed, exit)
    }

    /** One-shot `--json` stdout for a single query (the parity reference). */
    private fun runOneShot(command: String, ref: String, roots: List<String>): String {
        val original = System.out
        val buffer = ByteArrayOutputStream()
        System.setOut(PrintStream(buffer))
        try {
            val args = listOf(ref) + roots + "--json"
            when (command) {
                "show" -> ShowCommand(
                    terminate = noExit,
                    store = InMemoryWorkspaceStore(),
                    getenv = noEnv,
                    discover = noDiscovery,
                ).parse(args)
                "body" -> BodyCommand(
                    terminate = noExit,
                    store = InMemoryWorkspaceStore(),
                    getenv = noEnv,
                    discover = noDiscovery,
                ).parse(args)
                else -> fail("one-shot parity covers show/body only, not $command")
            }
        } catch (e: TestExit) {
            // Non-zero one-shot outcomes still print their envelope first.
            if (e.code !in 1..2) throw e
        } finally {
            System.setOut(original)
        }
        return buffer.toString(Charsets.UTF_8).trimEnd()
    }

    private fun exitOf(line: String): Int {
        val root = lenientJson.parseToJsonElement(line).jsonObject
        return if (root["ok"]?.jsonPrimitive?.booleanOrNull == true) {
            0
        } else {
            root["error"]?.jsonObject?.get("code")?.jsonPrimitive?.intOrNull
                ?: fail("batch line has no exit signal: $line")
        }
    }

    @Test
    fun `a mixed stream pins goldens and matches one-shot json byte for byte`() {
        val jar = FixtureJars.binaryJar()
        val roots = listOf("--jars", jar.absolutePath, "--no-jdk")
        val stdinText = listOf(
            """{"command":"show","query":"dev.jdx.fixtures.Generics"}""",
            "{\"command\":\"body\",\"query\":\"dev.jdx.fixtures.CovariantOverrides\$Child#copy\"}",
            """{"command":"show","query":"dev.jdx.fixtures.NoSuchClass"}""",
            "not a request line",
            """{"command":"version","query":""}""",
        ).joinToString("\n")

        val run = runBatch(stdinText, roots)

        // Per-query failures ride their envelope, never abort the stream;
        // the exit code is the maximum query exit code.
        run.lines.size shouldBe 5
        exitOf(run.lines[0]) shouldBe 0
        exitOf(run.lines[1]) shouldBe 2
        run.lines[1] shouldContain "ambiguous:"
        exitOf(run.lines[2]) shouldBe 1
        run.lines[2] shouldContain "not found:"
        exitOf(run.lines[3]) shouldBe 6
        exitOf(run.lines[4]) shouldBe 0
        run.exit shouldBe 6

        // Byte parity with the one-shot CLI (T-046 in miniature): the batch
        // line is the exact --json stdout for the same query.
        run.lines[0] shouldBe runOneShot("show", "dev.jdx.fixtures.Generics", roots)
        run.lines[1] shouldBe runOneShot(
            "body",
            "dev.jdx.fixtures.CovariantOverrides\$Child#copy",
            roots,
        )

        GoldenFiles.verifyAll(
            File("src/test/resources/golden/batch"),
            mapOf("mixed.txt" to normalize(run.lines.joinToString("\n"), jar)),
        )
    }

    /** Jar file names carry versions and the build stamps its own — neither leaks into goldens. */
    private fun normalize(output: String, jar: File): String =
        output.replace(jar.name, "fixture-corpus.jar")
            .replace(BuildInfo.version, "VERSION")
}
