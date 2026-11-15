package dev.jdx.core.render

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

/**
 * Tier-1 coverage for the resource listing + `--cat` content model
 * (issue #25): empty/miss text, artifact grouping, truncation footer,
 * text⊆JSON (D-007), binary refusal, and line capping.
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

    @Test
    fun `cat candidate is path --in artifact`() {
        ResourceHit("a/b.txt", "lib.jar").catCandidate() shouldBe "a/b.txt --in lib.jar"
    }

    @Test
    fun `splitResourceLines normalises newlines and drops a trailing terminator`() {
        splitResourceLines("") shouldBe emptyList()
        splitResourceLines("one") shouldBe listOf("one")
        splitResourceLines("one\n") shouldBe listOf("one")
        splitResourceLines("one\r\ntwo\rthree") shouldBe listOf("one", "two", "three")
    }

    @Test
    fun `text content renders body size and next hint`() {
        val content = buildResourceContent(
            query = "messages.properties",
            path = "com/example/messages.properties",
            artifact = "lib.jar",
            sizeBytes = 12,
            fileLines = listOf("hello=world"),
        )
        val text = content.renderText()
        text shouldContain "com/example/messages.properties"
        text shouldContain "artifact: lib.jar"
        text shouldContain "size: 12 bytes"
        text shouldContain "hello=world"
        text shouldContain "next: jdx resources 'com/example/messages.properties' --in lib.jar"
        text shouldNotContain "binary:"
        val json = content.toJson("resources")
        json shouldContain "\"binary\":false"
        json shouldContain "\"text\":\"hello=world\""
        json shouldContain "\"sizeBytes\":12"
    }

    @Test
    fun `text content truncates with --max-lines footer`() {
        val lines = (1..5).map { "line-$it" }
        val content = buildResourceContent(
            query = "x",
            path = "x.txt",
            artifact = "a.jar",
            sizeBytes = 40,
            fileLines = lines,
            maxLines = 2,
        )
        content.lines shouldBe listOf("line-1", "line-2")
        content.renderText() shouldContain "2 of 5 lines shown (--max-lines 5 to see more)"
        content.toJson("resources") shouldContain "\"shown\":2"
    }

    @Test
    fun `binary content refuses to dump bytes`() {
        val content = buildBinaryResourceContent(
            query = "logo.png",
            path = "img/logo.png",
            artifact = "ui.jar",
            sizeBytes = 99,
        )
        val text = content.renderText()
        text shouldContain "binary: contents not shown (not UTF-8 text)"
        text shouldContain "size: 99 bytes"
        text shouldNotContain "PNG"
        val json = content.toJson("resources")
        json shouldContain "\"binary\":true"
        json shouldNotContain "\"text\""
    }
}
