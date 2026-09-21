package dev.jdx.core.render

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

/**
 * Examples for the samples renderer (T-034, PROPOSAL.md §7.3): ranked usage
 * examples with enclosing-method source snippets when paired sources exist.
 */
class SamplesTest {

    private fun hit(
        fromRef: String,
        artifact: String = "samples-case.jar",
        targetRef: String = "s.Lib#greet(java.lang.String)",
        snippet: SampleSnippet? = null,
    ): SampleHit = SampleHit(fromRef = fromRef, artifact = artifact, targetRef = targetRef, snippet = snippet)

    private fun snippet(
        file: String = "s/App.java",
        startLine: Int = 10,
        lines: List<String> = listOf("public void run() {", "  lib.greet(\"hi\");", "}"),
    ): SampleSnippet = SampleSnippet(
        file = file,
        startLine = startLine,
        endLine = startLine + lines.size - 1,
        lines = lines,
        truncated = false,
    )

    @Test
    fun `samples text names the query and each example`() {
        val listing = buildSampleListing(
            query = "s.Lib#greet(java.lang.String)",
            targetRef = "s.Lib#greet",
            hits = listOf(hit("s.App#run()"), hit("s.Other#work()")),
        )
        listing.renderText() shouldBe (
            "samples of 's.Lib#greet(java.lang.String)'\n" +
                "  example s.App#run()  samples-case.jar\n" +
                "  example s.Other#work()  samples-case.jar"
            )
    }

    @Test
    fun `snippet rows render the file range and indented code`() {
        val listing = buildSampleListing(
            query = "s.Lib#greet",
            targetRef = "s.Lib#greet",
            hits = listOf(hit("s.App#run()", snippet = snippet())),
        )
        listing.renderText() shouldBe (
            "samples of 's.Lib#greet'\n" +
                "  example s.App#run()  samples-case.jar\n" +
                "    s/App.java:10-12\n" +
                "    public void run() {\n" +
                "      lib.greet(\"hi\");\n" +
                "    }"
            )
    }

    @Test
    fun `long snippets cap to fifteen lines with a marker`() {
        val long = (1..30).map { "line $it" }
        val listing = buildSampleListing(
            query = "q",
            targetRef = "t",
            hits = listOf(hit("s.App#run()", snippet = snippet(lines = long))),
        )
        val text = listing.renderText()
        text shouldContain "    s/App.java:10-39"
        text shouldContain "    line 15"
        text shouldNotContain "    line 16"
        text shouldContain "      … (15 of 30 lines shown)"
    }

    @Test
    fun `exemplariness ranks plain callers before tests and generated code`() {
        // Structural rank (sourced ranks ignored here — all unsourced): plain
        // first, then generated ($ nesting), then tests, fuller overloads first.
        val keys = listOf(
            sampleOrderKey("s.App#run()", targetParamCount = 1, sourcedFirst = false, hasSources = false),
            sampleOrderKey("s.AppTest#run()", targetParamCount = 1, sourcedFirst = false, hasSources = false),
            sampleOrderKey("s.App\$Inner#run()", targetParamCount = 1, sourcedFirst = false, hasSources = false),
            sampleOrderKey("s.Other#work()", targetParamCount = 2, sourcedFirst = false, hasSources = false),
        )
        keys.sorted() shouldBe listOf(keys[3], keys[0], keys[2], keys[1])
    }

    @Test
    fun `prefer-sources ranks sourced artifacts first`() {
        val sourced = sampleOrderKey("s.B#run()", 0, sourcedFirst = true, hasSources = true)
        val unsourced = sampleOrderKey("s.A#run()", 0, sourcedFirst = true, hasSources = false)
        (sourced < unsourced) shouldBe true
        val off1 = sampleOrderKey("s.B#run()", 0, sourcedFirst = false, hasSources = true)
        val off2 = sampleOrderKey("s.A#run()", 0, sourcedFirst = false, hasSources = false)
        (off1 < off2) shouldBe false
    }

    @Test
    fun `truncation footer names the total`() {
        val hits = (1..7).map { hit("s.App#m$it()") }
        val listing = buildSampleListing("q", "t", hits, limit = 3)
        val text = listing.renderText()
        text shouldContain "3 of 7 samples shown (--limit 7 to see more)"
        text shouldNotContain "s.App#m4()"
        buildSampleListing("q", "t", hits.take(3), limit = 3).truncation shouldBe null
    }

    @Test
    fun `empty listings name the query`() {
        buildSampleListing("q", "t", emptyList()).renderText() shouldBe "no samples of 'q'"
    }

    @Test
    fun `json carries every text fact`() {
        val listing = buildSampleListing(
            query = "s.Lib#greet",
            targetRef = "s.Lib#greet",
            hits = listOf(
                hit("s.App#run()", snippet = snippet()),
                hit("s.Other#work()"),
            ),
        )
        val json = listing.toJson("samples")
        json shouldContain "\"target\":\"s.Lib#greet\""
        json shouldContain "\"from\":\"s.App#run()\""
        json shouldContain "\"artifact\":\"samples-case.jar\""
        json shouldContain "\"target\":\"s.Lib#greet(java.lang.String)\""
        json shouldContain "\"file\":\"s/App.java\""
        json shouldContain "lib.greet"
        // The snippet-less row omits the key rather than printing null.
        json shouldNotContain "\"snippet\":null"
    }
}
