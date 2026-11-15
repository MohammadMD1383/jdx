package dev.jdx.core.render

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

/**
 * Tier-1 coverage for the resource listing (issue #25): empty/miss text,
 * artifact grouping, truncation footer, and text⊆JSON (D-007).
 */
class ResourcesTest {

    @Test
    fun `empty listing with a pattern renders the miss line`() {
        val listing = buildResourceListing("META-INF", emptyList())
        listing.renderText() shouldBe "no resources matching 'META-INF'"
        listing.toJson("resources") shouldContain "\"ok\":true"
        listing.toJson("resources") shouldContain "\"resources\":[]"
    }

    @Test
    fun `empty listing with no pattern says no resources found`() {
        val listing = buildResourceListing("", emptyList())
        listing.renderText() shouldBe "no resources found"
    }

    @Test
    fun `hits group by artifact`() {
        val hits = listOf(
            ResourceHit("META-INF/MANIFEST.MF", "gson.jar"),
            ResourceHit("META-INF/maven/g/a/pom.properties", "gson.jar"),
            ResourceHit("config.xml", "other.jar"),
        )
        val listing = buildResourceListing("META", hits)
        val text = listing.renderText()
        text shouldContain "resources matching 'META'"
        text shouldContain "gson.jar (2)"
        text shouldContain "other.jar (1)"
        text shouldContain "  META-INF/MANIFEST.MF"
        text shouldContain "  config.xml"
        val json = listing.toJson("resources")
        json shouldContain "\"command\":\"resources\""
        json shouldContain "\"pattern\":\"META\""
        for (hit in hits) {
            json shouldContain hit.path
            json shouldContain hit.artifact
        }
    }

    @Test
    fun `all-resources header drops the matching clause`() {
        val hits = listOf(ResourceHit("META-INF/MANIFEST.MF", "lib.jar"))
        val text = buildResourceListing("", hits).renderText()
        text shouldContain "resources"
        text shouldNotContain "matching"
    }

    @Test
    fun `truncation keeps the prefix and names --limit`() {
        val hits = (1..5).map { i ->
            ResourceHit("res-$i.txt", "lib.jar")
        }
        val listing = buildResourceListing("res", hits, limit = 2)
        listing.hits.size shouldBe 2
        listing.truncation shouldNotBe null
        val text = listing.renderText()
        text shouldContain "2 of 5 resources shown (--limit 5 to see more)"
        text shouldContain "res-1.txt"
        text shouldContain "res-2.txt"
        text shouldNotContain "res-3.txt"
        listing.toJson("resources") shouldContain "\"shown\":2"
        listing.toJson("resources") shouldContain "\"total\":5"
    }
}
