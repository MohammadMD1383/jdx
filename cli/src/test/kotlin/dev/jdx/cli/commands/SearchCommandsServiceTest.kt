package dev.jdx.cli.commands

import com.github.ajalt.clikt.core.parse
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import dev.jdx.index.workspace.InMemoryWorkspaceStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail

/**
 * Behaviour of `search`, `resolve`, `ls` and `tree` through the real Clikt
 * commands against real artifacts (T-017, tier 2): the fixture corpus jar.
 *
 * Rendering itself is pinned by the index `SearchGoldenTest`; here the
 * assertions are structural — flags reach the service, exits follow D-015,
 * `--json` parses with the same hits, runs are deterministic — so a fixture
 * change turns goldens red, not these.
 */
@Tag("tier2")
class SearchCommandsServiceTest {

    private class TestExit(val code: Int) : RuntimeException()

    private val noExit: (Int) -> Nothing = { throw TestExit(it) }

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
     * The four search commands with the real service but a throwing exit.
     * Auto-discovery is off: these tests pin exact roots and exits, so the
     * ambient checkout must not leak in (see `ReadCommandDiscoveryTest`).
     */
    private object JdxTestCli {
        private val noDiscovery: ProjectDiscoveryFn = { _, _, _, _ -> null }
        private val noEnv: (String) -> String? = { null }

        fun parse(args: List<String>) {
            val head = args.firstOrNull()
            val rest = args.drop(1)
            // An isolated store and empty environment: the contributor's ambient
            // `active-workspace` (e.g. `fx`) must not re-root these assertions
            // (the T-066 trap: real-home defaults turn hermetic tests red).
            when (head) {
                "resolve" -> ResolveCommand(
                    query = ::defaultResolveQuery,
                    terminate = { throw TestExit(it) },
                    store = InMemoryWorkspaceStore(),
                    getenv = noEnv,
                    discover = noDiscovery,
                ).parse(rest)
                "ls" -> LsCommand(
                    query = ::defaultLsQuery,
                    terminate = { throw TestExit(it) },
                    store = InMemoryWorkspaceStore(),
                    getenv = noEnv,
                    discover = noDiscovery,
                ).parse(rest)
                "tree" -> TreeCommand(
                    query = ::defaultTreeQuery,
                    terminate = { throw TestExit(it) },
                    store = InMemoryWorkspaceStore(),
                    getenv = noEnv,
                    discover = noDiscovery,
                ).parse(rest)
                else -> SearchCommand(
                    query = ::defaultSearchQuery,
                    terminate = { throw TestExit(it) },
                    store = InMemoryWorkspaceStore(),
                    getenv = noEnv,
                    discover = noDiscovery,
                ).parse(if (head == "search") rest else args)
            }
        }
    }

    private fun fixtureArgs(vararg extra: String): List<String> =
        listOf("--jars", fixtureJar().absolutePath, "--no-jdk") + extra

    // -- the four commands reach the service ----------------------------------------

    @Test
    fun `search finds the fixture through the CLI`() {
        val run = run(listOf("search") + fixtureArgs("Generics"))
        run.exit shouldBe 0
        run.output shouldContain "search results for 'Generics'"
        run.output shouldContain "dev.jdx.fixtures.Generics"
    }

    @Test
    fun `resolve ls and tree answer through the CLI`() {
        val resolve = run(listOf("resolve") + fixtureArgs("Generics"))
        resolve.exit shouldBe 0
        resolve.output shouldContain "class dev.jdx.fixtures.Generics"

        val ls = run(listOf("ls") + fixtureArgs("dev.jdx.fixtures"))
        ls.exit shouldBe 0
        ls.output shouldContain "types in package 'dev.jdx.fixtures'"

        val tree = run(listOf("tree") + fixtureArgs(fixtureJar().name, "--counts"))
        tree.exit shouldBe 0
        tree.output shouldContain "dev.jdx.fixtures ("
    }

    @Test
    fun `search flags reach the service`() {
        val humps = run(listOf("search") + fixtureArgs("CovOver"))
        humps.exit shouldBe 0
        humps.output shouldContain "CovariantOverrides"

        val method = run(listOf("search") + fixtureArgs("identity", "--kind", "method"))
        method.exit shouldBe 0
        method.output shouldContain "#identity("

        val regex = run(listOf("search") + fixtureArgs(".*Record", "--regex", "--kind", "record"))
        regex.exit shouldBe 0
        regex.output shouldContain "PersonRecord"

        val fuzzy = run(listOf("search") + fixtureArgs("Generix", "--fuzzy"))
        fuzzy.exit shouldBe 0
        fuzzy.output shouldContain "dev.jdx.fixtures.Generics"
    }

    // -- exits -------------------------------------------------------------------------

    @Test
    fun `a miss exits 1 with did-you-mean`() {
        val run = run(listOf("search") + fixtureArgs("ZzzQqxNope"))
        run.exit shouldBe 1
        run.output shouldContain "not found: ZzzQqxNope"
    }

    @Test
    fun `bad limits and depths exit 3`() {
        run(listOf("search") + fixtureArgs("x", "--limit", "-1")).exit shouldBe 3
        run(listOf("tree") + fixtureArgs("--depth", "-1")).exit shouldBe 3
    }

    @Test
    fun `no roots exits 4`() {
        // --no-jdk with no --jars and no discovery: no workspace to read.
        run(listOf("search", "x", "--no-jdk")).exit shouldBe 4
        run(listOf("ls", "--no-jdk")).exit shouldBe 4
    }

    // -- contracts ------------------------------------------------------------------------

    @Test
    fun `search json parses with the same hits as text`() {
        val text = run(listOf("search") + fixtureArgs("Generics"))
        val json = run(listOf("search") + fixtureArgs("Generics", "--json"))
        text.exit shouldBe 0
        json.exit shouldBe 0
        val parsed = Json.parseToJsonElement(json.output.trim()).jsonObject
        parsed["command"]?.jsonPrimitive?.contentOrNull shouldBe "search"
        parsed["ok"]?.jsonPrimitive?.contentOrNull shouldBe "true"
        val hits = parsed["result"]?.jsonObject?.get("hits")?.jsonArray
            ?: fail("search JSON has no result.hits: ${json.output.trim()}")
        val refs = hits.map { it.jsonObject["ref"]?.jsonPrimitive?.contentOrNull }
        for (line in text.output.lines().filter { it.startsWith("  ") }) {
            val ref = line.trim().split(Regex("\\s+"))[1]
            (refs.contains(ref)) shouldBe true
        }
    }

    @Test
    fun `search twice yields identical bytes`() {
        val first = run(listOf("search") + fixtureArgs("fixtures", "--limit", "25"))
        val second = run(listOf("search") + fixtureArgs("fixtures", "--limit", "25"))
        first.output shouldBe second.output
        val firstJson = run(listOf("search") + fixtureArgs("fixtures", "--limit", "25", "--json"))
        val secondJson = run(listOf("search") + fixtureArgs("fixtures", "--limit", "25", "--json"))
        firstJson.output shouldBe secondJson.output
    }
}
