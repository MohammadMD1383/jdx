package dev.jdx.cli.commands

import com.github.ajalt.clikt.core.parse
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldMatch
import io.kotest.matchers.string.shouldNotContain
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.util.zip.ZipFile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import dev.jdx.index.workspace.InMemoryWorkspaceStore
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail

/**
 * Behaviour of `show`, `members` and `outline` against real artifacts (T-011, tier 2):
 * the fixture corpus jar plus the running JDK.
 *
 * Rendering itself is pinned by the golden test in this package; here the assertions
 * are structural — flags, exit codes, the JDK zero-config path, determinism and the
 * D-017 never-load proof — so a JDK upgrade cannot turn these red (only the goldens,
 * which deliberately exclude the JDK, pin exact bytes).
 */
@Tag("tier2")
class ReadCommandsServiceTest {

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
     * The three read commands with the real service but a throwing exit (tier-1 style).
     * Auto-discovery is off: these tests pin exact roots and exits (notably exit 4 for
     * "no roots"), so the ambient checkout must not leak in — discovery itself is
     * covered in `ReadCommandDiscoveryTest`.
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
                "show" -> ShowCommand(
                    query = ::defaultShowQuery,
                    terminate = { throw TestExit(it) },
                    store = InMemoryWorkspaceStore(),
                    getenv = noEnv,
                    discover = noDiscovery,
                ).parse(rest)
                "outline" -> OutlineCommand(
                    query = ::defaultMemberQuery,
                    terminate = { throw TestExit(it) },
                    store = InMemoryWorkspaceStore(),
                    getenv = noEnv,
                    discover = noDiscovery,
                ).parse(rest)
                else -> MembersCommand(
                    query = ::defaultMemberQuery,
                    terminate = { throw TestExit(it) },
                    store = InMemoryWorkspaceStore(),
                    getenv = noEnv,
                    discover = noDiscovery,
                ).parse(if (head == "members") rest else args)
            }
        }
    }

    private fun fixtureArgs(vararg extra: String): List<String> =
        listOf("--jars", fixtureJar().absolutePath, "--no-jdk") + extra

    // -- the JDK zero-config path (T-011 acceptance) --------------------------------

    @Test
    fun `members java util HashMap works with no flags via the JDK`() {
        val run = run(listOf("members", "java.util.HashMap", "--limit", "5"))
        run.exit shouldBe 0
        run.output shouldContain "members of java.util.HashMap"
        run.output shouldContain "from java.lang.Object"
        run.output shouldContain "source: java.base (jrt)"
    }

    @Test
    fun `show java util HashMap works with zero configuration`() {
        val run = run(listOf("show", "java.util.HashMap"))
        run.exit shouldBe 0
        run.output shouldContain "class java.util.HashMap"
        run.output shouldContain "next: jdx members java.util.HashMap --inherited"
    }

    @Test
    fun `show HashMap json provenance names the JDK module (T-012)`() {
        val run = run(listOf("show", "java.util.HashMap", "--json"))
        run.exit shouldBe 0
        val parsed = Json.parseToJsonElement(run.output.trim()).jsonObject
        val provenance = parsed["provenance"]?.jsonArray
            ?: fail("show JSON has no top-level provenance: ${run.output.trim()}")
        provenance.single().jsonObject["artifact"]?.jsonPrimitive?.content shouldBe "java.base"
    }

    @Test
    fun `a short JDK name matching several types exits 2 with candidates`() {
        val run = run(listOf("members", "Map", "--limit", "3"))
        run.exit shouldBe 2
        run.output shouldContain "ambiguous:"
        run.output shouldContain "java.util.Map"
    }

    // -- declared vs inherited ---------------------------------------------------------

    @Test
    fun `outline is members --declared byte for byte`() {
        val ref = "dev.jdx.fixtures.Generics"
        val outlineText = run(listOf("outline") + fixtureArgs(ref))
        val declaredText = run(listOf("members") + fixtureArgs(ref, "--declared"))
        outlineText.exit shouldBe 0
        outlineText.output shouldBe declaredText.output
        val outlineJson = run(listOf("outline") + fixtureArgs(ref, "--json"))
        val declaredJson = run(listOf("members") + fixtureArgs(ref, "--declared", "--json"))
        // Same rows; only the envelope's command name differs.
        outlineJson.output.replace("\"command\":\"outline\"", "") shouldBe
            declaredJson.output.replace("\"command\":\"members\"", "")
    }

    @Test
    fun `inherited is the default for members`() {
        val ref = "dev.jdx.fixtures.CovariantOverrides\$Child"
        val plain = run(listOf("members") + fixtureArgs(ref))
        val explicit = run(listOf("members") + fixtureArgs(ref, "--inherited"))
        plain.exit shouldBe 0
        plain.output shouldBe explicit.output
    }

    @Test
    fun `from java lang Object expands the collapsed members`() {
        val collapsed = run(listOf("members", "java.util.HashMap", "--limit", "5"))
        collapsed.output shouldContain "from java.lang.Object"
        val expanded = run(listOf("members", "java.util.HashMap", "--from", "java.lang.Object", "--limit", "5"))
        expanded.exit shouldBe 0
        expanded.output shouldContain "inherited from java.lang.Object:"
        expanded.output shouldNotContain "to expand)"
    }

    // -- filters -------------------------------------------------------------------------

    @Test
    fun `kind access grep and static filters narrow the fixture listing`() {
        val ref = "dev.jdx.fixtures.VarargsAndModifiers"
        val all = run(listOf("members") + fixtureArgs(ref, "--access", "all", "--limit", "200"))
        all.exit shouldBe 0
        val fields = run(listOf("members") + fixtureArgs(ref, "--kind", "field"))
        fields.exit shouldBe 0
        fields.output.lines().filter { it.startsWith("  ") }.forEach { line ->
            line shouldMatch Regex("  field .*")
        }
        val narrowed = run(listOf("members") + fixtureArgs(ref, "--access", "all", "--grep", "zzz-no-match"))
        narrowed.exit shouldBe 0
        narrowed.output shouldNotContain "  method "
        narrowed.output shouldNotContain "  field "
        // `--access all` is a superset of the default.
        val default = run(listOf("members") + fixtureArgs(ref, "--limit", "200"))
        for (line in default.output.lines().filter { it.startsWith("  ") }) {
            all.output shouldContain line.trim().substringAfter(" ")
        }
    }

    @Test
    fun `members --sort serves every order deterministically with the same rows (T-062)`() {
        val ref = "dev.jdx.fixtures.Generics"
        val rowLines = { output: String -> output.lines().filter { it.startsWith("  ") }.toSet() }
        val seen = mutableListOf<Set<String>>()
        for (sort in listOf("kind", "name", "declaring")) {
            val text = run(listOf("members") + fixtureArgs(ref, "--sort", sort))
            text.exit shouldBe 0
            run(listOf("members") + fixtureArgs(ref, "--sort", sort)).output shouldBe text.output
            val json = run(listOf("members") + fixtureArgs(ref, "--sort", sort, "--json"))
            json.exit shouldBe 0
            val parsed = Json.parseToJsonElement(json.output.trim()).jsonObject
            parsed["command"]?.jsonPrimitive?.content shouldBe "members"
            for (line in text.output.lines().filter { it.startsWith("  ") }) {
                json.output shouldContain line.substringAfter("  ").substringAfter(" ")
            }
            seen.add(rowLines(text.output))
        }
        // Sorting reorders, never hides: every order carries the same row set.
        seen.toSet().size shouldBe 1
    }

    @Test
    fun `outline --sort serves every order with exit 0 (T-062)`() {
        val ref = "dev.jdx.fixtures.KotlinMembers"
        for (sort in listOf("kind", "name", "declaring")) {
            val run = run(listOf("outline") + fixtureArgs(ref, "--sort", sort))
            run.exit shouldBe 0
            run.output shouldContain "members of $ref"
        }
    }

    @Test
    fun `limit zero truncates everything with a footer`() {
        val run = run(listOf("members") + fixtureArgs("dev.jdx.fixtures.Generics", "--limit", "0"))
        run.exit shouldBe 0
        run.output shouldMatch Regex("(?s).*0 of \\d+ members shown.*")
    }

    // -- token budgets (T-047) -----------------------------------------------------------

    @Test
    fun `members --brief strips headers and provenance over the JDK`() {
        val full = run(listOf("members", "java.util.HashMap", "--limit", "5"))
        full.exit shouldBe 0
        val brief = run(listOf("members", "java.util.HashMap", "--limit", "5", "--brief"))
        brief.exit shouldBe 0
        brief.output shouldContain "members of java.util.HashMap"
        brief.output shouldNotContain "declared on"
        brief.output shouldNotContain "inherited from"
        brief.output shouldNotContain "source:"
        (brief.output.lines().size < full.output.lines().size) shouldBe true
    }

    @Test
    fun `outline --brief keeps rows and drops the provenance block`() {
        val ref = "dev.jdx.fixtures.Generics"
        val full = run(listOf("outline") + fixtureArgs(ref))
        full.exit shouldBe 0
        val brief = run(listOf("outline") + fixtureArgs(ref, "--brief"))
        brief.exit shouldBe 0
        for (line in brief.output.lines().filter { it.startsWith("  ") }) {
            full.output shouldContain line
        }
        brief.output shouldNotContain "source:"
    }

    @Test
    fun `members --max-lines caps text with a footer and leaves json whole`() {
        val ref = "dev.jdx.fixtures.Generics"
        val full = run(listOf("members") + fixtureArgs(ref))
        full.exit shouldBe 0
        val capped = run(listOf("members") + fixtureArgs(ref, "--max-lines", "4"))
        capped.exit shouldBe 0
        val lines = capped.output.trimEnd().lines()
        lines.take(4) shouldBe full.output.lines().take(4)
        lines.last() shouldMatch Regex("… \\d+ more lines \\(--max-lines \\d+ to see more\\)")
        val plainJson = run(listOf("members") + fixtureArgs(ref, "--json"))
        val cappedJson = run(listOf("members") + fixtureArgs(ref, "--json", "--max-lines", "4", "--brief"))
        cappedJson.exit shouldBe 0
        cappedJson.output shouldBe plainJson.output
    }

    // -- failures --------------------------------------------------------------------------

    @Test
    fun `a missing type exits 1 and names the query`() {
        val run = run(listOf("show") + fixtureArgs("dev.jdx.fixtures.Gson"))
        run.exit shouldBe 1
        run.output shouldContain "not found: dev.jdx.fixtures.Gson"
    }

    @Test
    fun `a missing artifact exits 5`() {
        val run = run(listOf("show", "java.lang.Object", "--jars", "/nonexistent-xyz.jar", "--no-jdk"))
        run.exit shouldBe 5
        run.output shouldContain "artifact read error"
    }

    @Test
    fun `no roots at all exits 4`() {
        val run = run(listOf("members", "java.lang.Object", "--no-jdk"))
        run.exit shouldBe 4
        run.output shouldContain "no workspace"
    }

    @Test
    fun `members json parses and carries the envelope`() {
        val run = run(listOf("members") + fixtureArgs("dev.jdx.fixtures.PersonRecord", "--json"))
        run.exit shouldBe 0
        val parsed = Json.parseToJsonElement(run.output.trim()).jsonObject
        parsed["command"]?.jsonPrimitive?.content shouldBe "members"
        parsed["ok"]?.jsonPrimitive?.content shouldBe "true"
        parsed["query"]?.jsonPrimitive?.content shouldBe "dev.jdx.fixtures.PersonRecord"
    }

    // -- invariants ----------------------------------------------------------------------------

    @Test
    fun `running twice yields identical bytes`() {
        val ref = "dev.jdx.fixtures.KotlinMembers"
        run(listOf("show") + fixtureArgs(ref)).output shouldBe
            run(listOf("show") + fixtureArgs(ref)).output
        run(listOf("members") + fixtureArgs(ref)).output shouldBe
            run(listOf("members") + fixtureArgs(ref)).output
        run(listOf("outline") + fixtureArgs(ref, "--json")).output shouldBe
            run(listOf("outline") + fixtureArgs(ref, "--json")).output
    }

    @Test
    fun `querying the marker fixture never loads it (D-017)`() {
        val marker = File(System.getProperty("java.io.tmpdir"), "jdx-fixture-static-init-marker")
        marker.exists() shouldBe false
        val ref = "dev.jdx.fixtures.StaticInitMarker"
        run(listOf("show") + fixtureArgs(ref)).exit shouldBe 0
        run(listOf("members") + fixtureArgs(ref)).exit shouldBe 0
        run(listOf("outline") + fixtureArgs(ref)).exit shouldBe 0
        marker.exists() shouldBe false
    }

    @Test
    fun `fixture jar holds the expected corpus`() {
        ZipFile(fixtureJar()).use { zip ->
            zip.entries().asSequence().map { it.name }
                .filter { it.endsWith(".class") && it != "module-info.class" }.toList()
        }.size shouldBe 35
    }
}
