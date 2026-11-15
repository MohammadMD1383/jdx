package dev.jdx.core.render

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

/**
 * Tier-1 coverage for the services listing (issue #25): empty/miss text,
 * artifact + service grouping, truncation footer, and text⊆JSON (D-007).
 */
class ServicesTest {

    @Test
    fun `empty listing with a pattern renders the miss line`() {
        val listing = buildServiceListing("com.foo.Spi", emptyList())
        listing.renderText() shouldBe "no services matching 'com.foo.Spi'"
        listing.toJson("services") shouldContain "\"ok\":true"
        listing.toJson("services") shouldContain "\"services\":[]"
    }

    @Test
    fun `empty listing with no pattern says no services found`() {
        val listing = buildServiceListing("", emptyList())
        listing.renderText() shouldBe "no services found"
    }

    @Test
    fun `hits group by artifact then service`() {
        val hits = listOf(
            ServiceHit("com.foo.Spi", "com.foo.A", "lib.jar"),
            ServiceHit("com.foo.Spi", "com.foo.B", "lib.jar"),
            ServiceHit("com.bar.Other", "com.bar.Impl", "other.jar"),
        )
        val listing = buildServiceListing("Spi", hits)
        val text = listing.renderText()
        text shouldContain "services matching 'Spi'"
        text shouldContain "lib.jar (2)"
        text shouldContain "other.jar (1)"
        text shouldContain "  service com.foo.Spi"
        text shouldContain "    provider com.foo.A"
        text shouldContain "    provider com.foo.B"
        text shouldContain "  service com.bar.Other"
        text shouldContain "    provider com.bar.Impl"
        val json = listing.toJson("services")
        json shouldContain "\"command\":\"services\""
        json shouldContain "\"pattern\":\"Spi\""
        json shouldContain "\"service\":\"com.foo.Spi\""
        json shouldContain "\"provider\":\"com.foo.A\""
        for (hit in hits) {
            json shouldContain hit.service
            json shouldContain hit.provider
            json shouldContain hit.artifact
        }
    }

    @Test
    fun `all-services header drops the matching clause`() {
        val hits = listOf(ServiceHit("com.foo.Spi", "com.foo.A", "lib.jar"))
        val text = buildServiceListing("", hits).renderText()
        text shouldContain "services"
        text shouldNotContain "matching"
    }

    @Test
    fun `truncation keeps the prefix and names --limit`() {
        val hits = (1..5).map { i ->
            ServiceHit("com.foo.Spi", "com.foo.P$i", "lib.jar")
        }
        val listing = buildServiceListing("Spi", hits, limit = 2)
        listing.hits.size shouldBe 2
        listing.truncation shouldNotBe null
        val text = listing.renderText()
        text shouldContain "2 of 5 providers shown (--limit 5 to see more)"
        text shouldContain "com.foo.P1"
        text shouldContain "com.foo.P2"
        text shouldNotContain "com.foo.P3"
        listing.toJson("services") shouldContain "\"shown\":2"
        listing.toJson("services") shouldContain "\"total\":5"
    }
}
