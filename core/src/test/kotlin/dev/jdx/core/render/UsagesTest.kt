package dev.jdx.core.render

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/**
 * Examples for the `usages` renderer (T-030, PROPOSAL.md §7.3): one row per
 * referencing method, grouped by artifact, kind-led lines (§8.1).
 */
class UsagesTest {

    private fun hit(from: String, artifact: String, kind: String, target: String) =
        UsageHit(fromRef = from, artifact = artifact, kind = kind, targetRef = target)

    @Test
    fun `text groups hits by artifact with kind-led lines`() {
        val listing = buildUsageListing(
            query = "com.example.Lib#greet",
            targetRef = "com.example.Lib#greet",
            hits = listOf(
                hit("com.example.App#run()", "lib.jar", "call", "com.example.Lib#greet(java.lang.String)"),
                hit("com.example.App#main(java.lang.String[])", "lib.jar", "call", "com.example.Lib#greet(java.lang.String)"),
                hit("com.example.Tool#work()", "other.jar", "call", "com.example.Lib#greet(java.lang.String)"),
            ),
        )
        listing.renderText() shouldBe (
            "usages of 'com.example.Lib#greet'\n" +
                "lib.jar (2)\n" +
                "  call com.example.App#run()\n" +
                "  call com.example.App#main(java.lang.String[])\n" +
                "other.jar (1)\n" +
                "  call com.example.Tool#work()"
            )
    }

    @Test
    fun `type queries name the touched member per row`() {
        val listing = buildUsageListing(
            query = "com.example.Lib",
            targetRef = "com.example.Lib",
            hits = listOf(
                hit("com.example.App#run()", "lib.jar", "call", "com.example.Lib#greet(java.lang.String)"),
                hit("com.example.App#run()", "lib.jar", "read", "com.example.Lib#count"),
                hit("com.example.App#run()", "lib.jar", "ref", "com.example.Lib"),
            ),
            showTargets = true,
        )
        listing.renderText() shouldBe (
            "usages of 'com.example.Lib'\n" +
                "lib.jar (3)\n" +
                "  call com.example.App#run() -> #greet(java.lang.String)\n" +
                "  read com.example.App#run() -> #count\n" +
                "  ref com.example.App#run() -> com.example.Lib"
            )
    }

    @Test
    fun `truncation footer names the total`() {
        val hits = (1..7).map { hit("com.example.App#m$it()", "lib.jar", "call", "com.example.Lib#greet") }
        val listing = buildUsageListing("q", "com.example.Lib#greet", hits, limit = 5)
        val text = listing.renderText()
        text shouldContain "5 of 7 usages shown (--limit 7 to see more)"
        buildUsageListing("q", "t", hits.take(5), limit = 5).truncation shouldBe null
    }

    @Test
    fun `empty listing reads as no usages`() {
        val listing = buildUsageListing("q", "com.example.Lib#greet", emptyList())
        listing.renderText() shouldBe "no usages of 'q'"
    }

    @Test
    fun `json carries every text fact`() {
        val listing = buildUsageListing(
            query = "com.example.Lib#greet",
            targetRef = "com.example.Lib#greet",
            hits = listOf(hit("com.example.App#run()", "lib.jar", "call", "com.example.Lib#greet")),
        )
        val json = listing.toJson("usages")
        json shouldContain "\"from\":\"com.example.App#run()\""
        json shouldContain "\"artifact\":\"lib.jar\""
        json shouldContain "\"kind\":\"call\""
        json shouldContain "\"target\":\"com.example.Lib#greet\""
    }
}
