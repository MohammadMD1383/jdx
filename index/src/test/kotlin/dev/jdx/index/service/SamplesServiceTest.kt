package dev.jdx.index.service

import dev.jdx.index.service.JdxService.RootsSpec
import dev.jdx.index.service.JdxService.SampleOptions
import dev.jdx.index.service.JdxService.ServiceOutcome
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.nio.file.Files
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Behaviour of `samples` against a crafted corpus (T-034, tier 2): ASM-built
 * classes whose every call edge is placed by hand ([buildSamplesCaseJar]), so
 * the expected ranking is exact. The sibling `-sources.jar` holds `s/App.java`
 * only, pinning both the sourced snippet and the snippet-less degrade path.
 *
 * Rendering itself is pinned by `SamplesGoldenTest` in the render package;
 * here the assertions are structural — resolution, overload narrowing,
 * exemplariness order, snippets, filters, exit codes, determinism and
 * text⊆JSON.
 */
@Tag("tier2")
class SamplesServiceTest {

    private val tempDir: java.nio.file.Path = Files.createTempDirectory("samples-service-test")

    private fun caseRoots(): RootsSpec =
        RootsSpec(jarSpecs = listOf(buildSamplesCaseJar(tempDir).toString()), includeJdk = false)

    private fun textOf(outcome: ServiceOutcome): String = outcome.renderText(false)

    private fun sampleListOf(outcome: ServiceOutcome): dev.jdx.core.render.SampleListing =
        (outcome as? ServiceOutcome.SampleList)?.listing
            ?: error("expected SampleList, got $outcome")

    private fun samples(query: String, options: SampleOptions = SampleOptions(limit = 100)): ServiceOutcome =
        JdxService.samples(query, caseRoots(), options)

    // -- ranking ---------------------------------------------------------------

    @Test
    fun `member query ranks fullest overload first then plain generated test`() {
        val listing = sampleListOf(samples("s.Lib#greet"))
        listing.rows.map { it.fromRef } shouldBe listOf(
            "s.Other#work()",
            "s.App#run()",
            "s.Util#help()",
            "s.Gen\$Inner#run()",
            "s.AppTest#runTest()",
        )
        textOf(samples("s.Lib#greet")).lines().first() shouldBe "samples of 's.Lib#greet'"
    }

    @Test
    fun `parameterised ref narrows to one overload`() {
        val listing = sampleListOf(samples("s.Lib#greet(java.lang.String,int)"))
        listing.rows.map { it.fromRef } shouldBe listOf("s.Other#work()")
    }

    @Test
    fun `type query matches calls to every member`() {
        val listing = sampleListOf(samples("s.Lib"))
        listing.rows.map { it.fromRef } shouldBe listOf(
            "s.Other#work()",
            "s.App#run()",
            "s.Util#help()",
            "s.Gen\$Inner#run()",
            "s.AppTest#runTest()",
        )
        // Type hits name the touched overload each (the usages convention).
        listing.rows.map { it.targetRef }.toSet() shouldBe setOf(
            "s.Lib#greet(java.lang.String)",
            "s.Lib#greet(java.lang.String, int)",
        )
    }

    // -- snippets ----------------------------------------------------------------

    @Test
    fun `sourced caller renders a snippet while others degrade snippet-less`() {
        val listing = sampleListOf(samples("s.Lib#greet(java.lang.String)"))
        val app = listing.rows.single { it.fromRef == "s.App#run()" }
        val snippet = app.snippet ?: error("expected a snippet for s.App#run()")
        snippet.file shouldBe "s/App.java"
        snippet.startLine shouldBe 3
        snippet.endLine shouldBe 6
        snippet.lines.joinToString("\n") shouldContain "lib.greet"
        listing.rows.filter { it.fromRef != "s.App#run()" }.all { it.snippet == null } shouldBe true
        val text = textOf(samples("s.Lib#greet(java.lang.String)"))
        text shouldContain "    s/App.java:3-6"
        text shouldContain "    lib.greet"
    }

    @Test
    fun `prefer-sources keeps the sourced example first`() {
        val listing = sampleListOf(
            samples("s.Lib#greet(java.lang.String)", SampleOptions(limit = 100, preferSources = true)),
        )
        listing.rows.first().fromRef shouldBe "s.App#run()"
        listing.rows.first().snippet shouldNotBe null
    }

    // -- truncation, filters, exit codes ------------------------------------------

    @Test
    fun `default limit truncates to three with a hint`() {
        val outcome = JdxService.samples("s.Lib#greet", caseRoots())
        outcome.exitCode shouldBe 0
        val listing = sampleListOf(outcome)
        listing.rows.map { it.fromRef } shouldBe listOf("s.Other#work()", "s.App#run()", "s.Util#help()")
        textOf(outcome) shouldContain "3 of 5 samples shown (--limit 5 to see more)"
    }

    @Test
    fun `constructor samples resolve through invokespecial`() {
        val listing = sampleListOf(samples("s.Bean#<init>()"))
        listing.rows.map { it.fromRef } shouldBe listOf("s.Factory#make()")
    }

    @Test
    fun `unused method exits 1 naming no samples`() {
        val outcome = samples("s.Factory#make()")
        outcome.exitCode shouldBe 1
        textOf(outcome) shouldContain "no samples of 's.Factory#make'"
    }

    @Test
    fun `field refs redirect to usages and static initialisers fail`() {
        val field = samples("s.Lib#count")
        field.exitCode shouldBe 3
        textOf(field) shouldContain "jdx usages"
        val clinit = samples("s.Lib#<clinit>()")
        clinit.exitCode shouldBe 3
        textOf(clinit) shouldContain "never invoked"
    }

    @Test
    fun `unknown ambiguous packages and bad flags fail distinctly`() {
        samples("s.Missing#nope()").exitCode shouldBe 1
        samples("Lib#greet").exitCode shouldBe 2
        val pkg = samples("s.*")
        pkg.exitCode shouldBe 3
        textOf(pkg) shouldContain "takes a type or member reference"
        JdxService.samples("s.Lib#greet", caseRoots(), SampleOptions(limit = -1)).exitCode shouldBe 3
    }

    @Test
    fun `in and exclude scope rows by artifact label`() {
        val kept = sampleListOf(
            JdxService.samples("s.Lib#greet", caseRoots(), SampleOptions(limit = 100, inArtifact = "samples-case.jar")),
        )
        kept.rows.size shouldBe 5
        JdxService.samples("s.Lib#greet", caseRoots(), SampleOptions(inArtifact = "nope*")).exitCode shouldBe 1
        JdxService.samples(
            "s.Lib#greet",
            caseRoots(),
            SampleOptions(limit = 100, exclude = "samples-case*"),
        ).exitCode shouldBe 1
    }

    // -- determinism and parity -----------------------------------------------------

    @Test
    fun `run twice yields identical bytes`() {
        val first = samples("s.Lib#greet")
        val second = samples("s.Lib#greet")
        first.renderText(false) shouldBe second.renderText(false)
        first.toJson("samples") shouldBe second.toJson("samples")
    }

    @Test
    fun `every text row appears in json`() {
        val listing = sampleListOf(samples("s.Lib#greet"))
        val json = listing.toJson("samples")
        json shouldContain "\"target\":\"s.Lib#greet\""
        for (row in listing.rows) {
            json shouldContain row.fromRef
            json shouldContain row.artifact
            json shouldContain row.targetRef
        }
        // The sourced row's snippet text is covered structurally too.
        json shouldContain "lib.greet"
        json shouldNotContain "\"snippet\":null"
    }
}
