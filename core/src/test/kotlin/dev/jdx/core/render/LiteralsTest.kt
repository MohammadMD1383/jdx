package dev.jdx.core.render

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

/**
 * Tier-1 coverage for the literals listing (issue #86): empty/miss text,
 * artifact grouping, truncation footer, and text⊆JSON (D-007).
 */
class LiteralsTest {

    @Test
    fun `empty listing renders the miss line`() {
        val listing = buildLiteralListing("missing.key", emptyList())
        listing.renderText() shouldBe "no literals matching 'missing.key'"
        listing.toJson("literals") shouldContain "\"ok\":true"
        listing.toJson("literals") shouldContain "\"literals\":[]"
    }

    @Test
    fun `hits group by artifact and quote the value`() {
        val hits = listOf(
            LiteralHit("a.App#run()", "lib.jar", "ldc", "notification.missing", "App.java", 12),
            LiteralHit("a.Keys#TITLE", "lib.jar", "const", "notification.missing"),
            LiteralHit("b.Other#work()", "other.jar", "ldc", "notification.missing"),
        )
        val listing = buildLiteralListing("notification", hits)
        val text = listing.renderText()
        text shouldContain "literals matching 'notification'"
        text shouldContain "lib.jar (2)"
        text shouldContain "other.jar (1)"
        text shouldContain "ldc a.App#run()"
        text shouldContain "\"notification.missing\""
        text shouldContain "App.java:12"
        text shouldContain "const a.Keys#TITLE"
        val json = listing.toJson("literals")
        json shouldContain "\"command\":\"literals\""
        json shouldContain "\"pattern\":\"notification\""
        json shouldContain "\"from\":\"a.App#run()\""
        json shouldContain "\"value\":\"notification.missing\""
        json shouldContain "\"file\":\"App.java\""
        json shouldContain "\"line\":12"
        // Every text entity appears in JSON (D-007).
        for (hit in hits) {
            json shouldContain hit.fromRef
            json shouldContain hit.value
        }
    }

    @Test
    fun `truncation keeps the prefix and names --limit`() {
        val hits = (1..5).map { i ->
            LiteralHit("a.App#m$i()", "lib.jar", "ldc", "key-$i")
        }
        val listing = buildLiteralListing("key", hits, limit = 2)
        listing.hits.size shouldBe 2
        listing.truncation shouldNotBe null
        val text = listing.renderText()
        // Hint names the total so the next paste raises the cap (same as usages).
        text shouldContain "2 of 5 literals shown (--limit 5 to see more)"
        text shouldContain "key-1"
        text shouldContain "key-2"
        text shouldNotContain "key-3"
        listing.toJson("literals") shouldContain "\"shown\":2"
        listing.toJson("literals") shouldContain "\"total\":5"
    }
}
