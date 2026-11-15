package dev.jdx.core.render

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

/**
 * Tier-1 coverage for the manifest listing (issue #25): empty/miss text,
 * artifact grouping, truncation footer, and text⊆JSON (D-007).
 */
class ManifestsTest {

    @Test
    fun `empty listing with a pattern renders the miss line`() {
        val listing = buildManifestListing("Main-Class", emptyList())
        listing.renderText() shouldBe "no manifest attributes matching 'Main-Class'"
        listing.toJson("manifest") shouldContain "\"ok\":true"
        listing.toJson("manifest") shouldContain "\"attributes\":[]"
    }

    @Test
    fun `empty listing with no pattern says no manifests found`() {
        val listing = buildManifestListing("", emptyList())
        listing.renderText() shouldBe "no manifests found"
    }

    @Test
    fun `hits group by artifact`() {
        val hits = listOf(
            ManifestHit("Implementation-Title", "Gson", "gson.jar"),
            ManifestHit("Main-Class", "com.foo.Main", "gson.jar"),
            ManifestHit("Multi-Release", "true", "other.jar"),
        )
        val listing = buildManifestListing("Main", hits)
        val text = listing.renderText()
        text shouldContain "manifests matching 'Main'"
        text shouldContain "gson.jar (2)"
        text shouldContain "other.jar (1)"
        text shouldContain "  Implementation-Title: Gson"
        text shouldContain "  Main-Class: com.foo.Main"
        text shouldContain "  Multi-Release: true"
        val json = listing.toJson("manifest")
        json shouldContain "\"command\":\"manifest\""
        json shouldContain "\"pattern\":\"Main\""
        json shouldContain "\"name\":\"Main-Class\""
        json shouldContain "\"value\":\"com.foo.Main\""
        for (hit in hits) {
            json shouldContain hit.name
            json shouldContain hit.value
            json shouldContain hit.artifact
        }
    }

    @Test
    fun `all-manifests header drops the matching clause`() {
        val hits = listOf(ManifestHit("Manifest-Version", "1.0", "lib.jar"))
        val text = buildManifestListing("", hits).renderText()
        text shouldContain "manifests"
        text shouldNotContain "matching"
    }

    @Test
    fun `truncation keeps the prefix and names --limit`() {
        val hits = (1..5).map { i ->
            ManifestHit("Attr-$i", "v$i", "lib.jar")
        }
        val listing = buildManifestListing("Attr", hits, limit = 2)
        listing.hits.size shouldBe 2
        listing.truncation shouldNotBe null
        val text = listing.renderText()
        text shouldContain "2 of 5 attributes shown (--limit 5 to see more)"
        text shouldContain "Attr-1"
        text shouldContain "Attr-2"
        text shouldNotContain "Attr-3"
        listing.toJson("manifest") shouldContain "\"shown\":2"
        listing.toJson("manifest") shouldContain "\"total\":5"
    }

    @Test
    fun `entry hits nest under the entry path and emit entry in json`() {
        val hits = listOf(
            ManifestHit("Content-Type", "application/java", "lib.jar", entry = "com/foo/Bar.class"),
            ManifestHit("SHA-256-Digest", "aaa", "lib.jar", entry = "com/foo/Bar.class"),
            ManifestHit("SHA-256-Digest", "bbb", "lib.jar", entry = "META-INF/services/Spi"),
        )
        val listing = buildManifestListing("Digest", hits)
        val text = listing.renderText()
        text shouldContain "lib.jar (3)"
        text shouldContain "  com/foo/Bar.class"
        text shouldContain "    Content-Type: application/java"
        text shouldContain "    SHA-256-Digest: aaa"
        text shouldContain "  META-INF/services/Spi"
        text shouldContain "    SHA-256-Digest: bbb"
        val json = listing.toJson("manifest")
        json shouldContain "\"entry\":\"com/foo/Bar.class\""
        json shouldContain "\"entry\":\"META-INF/services/Spi\""
        json shouldContain "\"name\":\"SHA-256-Digest\""
        // Main-section rows omit the entry key.
        ManifestHit("Main-Class", "com.foo.Main", "lib.jar").toJson() shouldNotContain "\"entry\""
    }
}
