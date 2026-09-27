package dev.jdx.cli.commands

import com.github.ajalt.clikt.core.parse
import com.github.ajalt.clikt.core.subcommands
import dev.jdx.cli.JdxCli
import dev.jdx.core.diff.ApiDiff
import dev.jdx.core.diff.ApiSurface
import dev.jdx.core.diff.CompatRule
import dev.jdx.core.diff.DiffFinding
import dev.jdx.core.diff.FailOn
import dev.jdx.core.diff.SeverityFilter
import dev.jdx.core.model.Origin
import dev.jdx.core.model.Provenance
import dev.jdx.core.render.DEFAULT_DIFF_LIMIT
import dev.jdx.core.render.ErrorResult
import dev.jdx.core.render.buildDiffReport
import dev.jdx.index.service.JdxService
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test

/**
 * In-process tests for `jdx diff` (issue #23).
 *
 * No disk, no jars, no network: the service query and the process exit are both
 * injected, so every path — the report, the CI gate, the exit-3 guards, the
 * non-zero exits — runs in tier 1. Real two-jar behaviour is tier 2 in
 * `DiffCommandsServiceTest` and `DiffCommandsGoldenTest`.
 *
 * The fake outcome is a **real** report: `ApiDiff` findings over real
 * `CompatRule`s put through the real `buildDiffReport`, so a rendering or
 * ordering change in `core` fails here rather than being frozen into a
 * hand-written string. A hand-rolled fake shape would pin nothing.
 */
class DiffCommandTest {

    private class TestExit(val code: Int) : RuntimeException()

    private val noExit: (Int) -> Nothing = { throw TestExit(it) }

    private val breaking = DiffFinding(
        rule = CompatRule.MEMBER_REMOVED,
        type = "difffix.Api",
        ref = "difffix.Api#legacy()",
    )
    private val suspicious = DiffFinding(
        rule = CompatRule.CHECKED_EXCEPTION_ADDED,
        type = "difffix.Api",
        ref = "difffix.Api#checked()",
        detail = "java.io.IOException",
    )
    private val informational = DiffFinding(
        rule = CompatRule.MEMBER_ADDED,
        type = "difffix.Api",
        ref = "difffix.Api#added()",
    )

    /** A real `ServiceOutcome.Diff` over real findings — never a fake shape. */
    private fun diffOutcome(
        findings: List<DiffFinding> = listOf(breaking, suspicious, informational),
        failOn: FailOn = FailOn.NONE,
        severityFilter: SeverityFilter = SeverityFilter.ALL,
        maxFindings: Int = DEFAULT_DIFF_LIMIT,
        surface: ApiSurface = ApiSurface.PUBLIC,
    ): JdxService.ServiceOutcome.Diff = JdxService.ServiceOutcome.Diff(
        buildDiffReport(
            diff = ApiDiff(
                oldArtifact = "old.jar",
                newArtifact = "new.jar",
                oldTypeCount = 2,
                newTypeCount = 2,
                findings = findings,
            ),
            surface = surface,
            severityFilter = severityFilter,
            failOn = failOn,
            maxFindings = maxFindings,
            provenance = listOf(
                Provenance(artifact = "old.jar", origin = Origin.BYTECODE),
                Provenance(artifact = "new.jar", origin = Origin.BYTECODE),
            ),
        ),
    )

    /** Everything one invocation handed the service, plus what it printed. */
    private data class Call(
        val old: JdxService.ArtifactSpec,
        val new: JdxService.ArtifactSpec,
        val options: JdxService.DiffOptions,
    )

    private data class Run(val output: String, val exit: Int, val call: Call?)

    private fun run(args: List<String>, outcome: JdxService.ServiceOutcome = diffOutcome()): Run {
        var call: Call? = null
        val query: DiffQuery = { old, new, options ->
            call = Call(old, new, options)
            outcome
        }
        val original = System.out
        val buffer = ByteArrayOutputStream()
        System.setOut(PrintStream(buffer))
        val exit = try {
            DiffCommand(query = query, terminate = noExit).parse(args)
            0
        } catch (e: TestExit) {
            e.code
        } finally {
            System.setOut(original)
        }
        return Run(buffer.toString(Charsets.UTF_8), exit, call)
    }

    // -- the default run -----------------------------------------------------------

    @Test
    fun `diff prints the report and exits 0`() {
        val run = run(listOf("old.jar", "new.jar"))
        run.exit shouldBe 0
        run.output shouldContain "api diff old.jar -> new.jar (public surface)"
        run.output shouldContain "breaking  MEMBER_REMOVED  difffix.Api#legacy()"
        run.output shouldContain "suspicious  CHECKED_EXCEPTION_ADDED  difffix.Api#checked(): java.io.IOException"
        run.output shouldContain "info  MEMBER_ADDED  difffix.Api#added()"
        run.output shouldContain "1 breaking · 1 suspicious · 1 informational · 0 types added · 0 types removed"
        run.output shouldContain "source: old.jar (bytecode)"
        run.output shouldContain "next: jdx members difffix.Api"
    }

    @Test
    fun `the default flags reach the service as the documented defaults`() {
        val run = run(listOf("old.jar", "new.jar"))
        run.call shouldBe Call(
            old = JdxService.ArtifactSpec(spec = "old.jar", allowFetch = false, repos = emptyList()),
            new = JdxService.ArtifactSpec(spec = "new.jar", allowFetch = false, repos = emptyList()),
            options = JdxService.DiffOptions(
                visibility = ApiSurface.PUBLIC,
                includeSynthetic = false,
                severityFilter = SeverityFilter.ALL,
                failOn = FailOn.NONE,
                maxFindings = DEFAULT_DIFF_LIMIT,
            ),
        )
    }

    // -- every flag reaches the service ---------------------------------------------

    @Test
    fun `visibility all reaches the service`() {
        val run = run(listOf("old.jar", "new.jar", "--visibility", "all"))
        run.exit shouldBe 0
        run.call?.options?.visibility shouldBe ApiSurface.ALL
    }

    @Test
    fun `include-synthetic reaches the service`() {
        val run = run(listOf("old.jar", "new.jar", "--include-synthetic"))
        run.exit shouldBe 0
        run.call?.options?.includeSynthetic shouldBe true
    }

    @Test
    fun `severity breaking reaches the service`() {
        val run = run(listOf("old.jar", "new.jar", "--severity", "breaking"))
        run.exit shouldBe 0
        run.call?.options?.severityFilter shouldBe SeverityFilter.BREAKING
    }

    @Test
    fun `fail-on any reaches the service`() {
        val run = run(listOf("old.jar", "new.jar", "--fail-on", "any"))
        run.exit shouldBe 0
        run.call?.options?.failOn shouldBe FailOn.ANY
    }

    @Test
    fun `limit 7 reaches the service`() {
        val run = run(listOf("old.jar", "new.jar", "--limit", "7"))
        run.exit shouldBe 0
        run.call?.options?.maxFindings shouldBe 7
    }

    @Test
    fun `fetch and repo reach both artifact specs in flag order`() {
        val run = run(
            listOf(
                "old.jar", "new.jar", "--fetch",
                "--repo", "https://mirror.example.com/maven2",
                "--repo", "https://other.example.com/maven2",
            ),
        )
        run.exit shouldBe 0
        val repos = listOf("https://mirror.example.com/maven2", "https://other.example.com/maven2")
        run.call shouldBe Call(
            old = JdxService.ArtifactSpec(spec = "old.jar", allowFetch = true, repos = repos),
            new = JdxService.ArtifactSpec(spec = "new.jar", allowFetch = true, repos = repos),
            options = JdxService.DiffOptions(maxFindings = DEFAULT_DIFF_LIMIT),
        )
    }

    @Test
    fun `every flag at once reaches the service as one value`() {
        val run = run(
            listOf(
                "com.example:old:1.0", "com.example:new:2.0",
                "--visibility", "all", "--include-synthetic", "--severity", "breaking",
                "--fail-on", "any", "--limit", "7", "--fetch",
            ),
        )
        run.exit shouldBe 0
        run.call shouldBe Call(
            old = JdxService.ArtifactSpec(spec = "com.example:old:1.0", allowFetch = true, repos = emptyList()),
            new = JdxService.ArtifactSpec(spec = "com.example:new:2.0", allowFetch = true, repos = emptyList()),
            options = JdxService.DiffOptions(
                visibility = ApiSurface.ALL,
                includeSynthetic = true,
                severityFilter = SeverityFilter.BREAKING,
                failOn = FailOn.ANY,
                maxFindings = 7,
            ),
        )
    }

    // -- JSON ------------------------------------------------------------------------

    @Test
    fun `json prints the envelope and not the text report`() {
        val run = run(listOf("old.jar", "new.jar", "--json"))
        run.exit shouldBe 0
        val parsed = Json.parseToJsonElement(run.output.trim()).jsonObject
        parsed["command"]?.jsonPrimitive?.content shouldBe "diff"
        parsed["ok"]?.jsonPrimitive?.content shouldBe "true"
        parsed["query"]?.jsonPrimitive?.content shouldBe "old.jar -> new.jar"
        // The text rendering is the other form of the same facts; --json must not
        // print both (an agent parsing the envelope would see stray prose).
        run.output shouldNotContain "api diff old.jar"
        run.output shouldContain "\"command\":\"diff\""
    }

    @Test
    fun `json before the subcommand flows to diff`() {
        // The dual-position `--json` is a JdxCli promise; a new command must honour
        // it or `jdx --json diff a b` silently answers in text.
        val output = captureStdout {
            JdxCli().subcommands(
                DiffCommand(
                    query = { _, _, _ -> diffOutcome() },
                    terminate = noExit,
                ),
            ).parse(listOf("--json", "diff", "old.jar", "new.jar"))
        }
        Json.parseToJsonElement(output.trim()).jsonObject["command"]
            ?.jsonPrimitive?.content shouldBe "diff"
    }

    private fun captureStdout(block: () -> Unit): String {
        val original = System.out
        val buffer = ByteArrayOutputStream()
        System.setOut(PrintStream(buffer))
        try {
            block()
        } finally {
            System.setOut(original)
        }
        return buffer.toString(Charsets.UTF_8)
    }

    // -- exit codes -------------------------------------------------------------------

    @Test
    fun `a negative limit exits 3 with the usage message and never queries`() {
        val run = run(listOf("old.jar", "new.jar", "--limit", "-1"))
        run.exit shouldBe 3
        run.call shouldBe null
        run.output shouldContain "usage error: --limit must be >= 0, got -1"
    }

    @Test
    fun `a tripped fail-on breaking gate exits 1`() {
        val run = run(
            listOf("old.jar", "new.jar", "--fail-on", "breaking"),
            diffOutcome(failOn = FailOn.BREAKING, findings = listOf(breaking, informational)),
        )
        run.exit shouldBe 1
        run.output shouldContain "fail-on breaking: 1 breaking change (exit 1)"
    }

    @Test
    fun `a clean fail-on breaking gate exits 0`() {
        val run = run(
            listOf("old.jar", "new.jar", "--fail-on", "breaking"),
            diffOutcome(failOn = FailOn.BREAKING, findings = listOf(informational)),
        )
        run.exit shouldBe 0
        run.output shouldContain "fail-on breaking: no breaking changes (exit 0)"
    }

    @Test
    fun `a service failure passes its exit code through untouched`() {
        val run = run(
            listOf("old.jar", "new.jar"),
            JdxService.ServiceOutcome.Failure(
                ErrorResult.generic(query = "old.jar -> new.jar", exitCode = 5, message = "artifact read error: nope"),
            ),
        )
        run.exit shouldBe 5
        run.output shouldContain "artifact read error: nope"
    }

    @Test
    fun `identical artifacts print the no-differences line and exit 0`() {
        val run = run(listOf("old.jar", "new.jar"), diffOutcome(findings = emptyList()))
        run.exit shouldBe 0
        run.output shouldContain "no differences (2 types compared)"
    }

    // -- help -------------------------------------------------------------------------

    @Test
    fun `help states the exit-code contract and the gate flag`() {
        // The one-line `jdx diff` sheet row is pinned by HelpCommandTest; the long
        // form below is the other half of that surface and an agent reads it.
        val help = requireNotNull(
            DiffCommand(query = { _, _, _ -> diffOutcome() }, terminate = noExit).getFormattedHelp(),
        )
        help.isNotEmpty() shouldBe true
        help shouldContain "--fail-on"
        help shouldContain "breaking"
        help shouldContain "Exits 0 whenever the comparison ran"
        help shouldContain "Exits 3 on a bad spec or flag, 5 when an artifact cannot be read"
    }
}
