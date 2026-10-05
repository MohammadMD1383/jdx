package dev.jdx.site

import io.kotest.matchers.collections.shouldBeUnique
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldMatch
import io.kotest.property.Arb
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/** Heading ids must match GitHub's, or `docs/X.md#anchor` links written for GitHub break on the site. */
class MarkdownTest {

    @Test
    fun `slugs follow GitHub's rules`() {
        val slugger = GithubSlugger()
        slugger.slug("What makes it an IDE, not a better javap") shouldBe "what-makes-it-an-ide-not-a-better-javap"
        slugger.slug("6. Symbol reference grammar") shouldBe "6-symbol-reference-grammar"
        slugger.slug("7.7 Public-API diff") shouldBe "77-public-api-diff"
        slugger.slug("MCP, daemon, HTTP & batch") shouldBe "mcp-daemon-http--batch"
        slugger.slug("Usage") shouldBe "usage"
        slugger.slug("Usage") shouldBe "usage-1"
    }

    @Test
    fun `slugs are unique and url-safe for any heading text`() = runBlocking<Unit> {
        checkAll(200, Arb.list(Arb.string(0..30), 1..12)) { headings ->
            val slugger = GithubSlugger()
            val ids = headings.map { slugger.slug(it) }
            ids.shouldBeUnique()
            ids.forEach { it shouldMatch Regex("[\\p{L}\\p{N}_-]*(-\\d+)?") }
        }
    }

    @Test
    fun `rendered headings carry the ids headings() reports`() {
        val document = Markdown.parse("# Title\n\n## A `code` heading\n\n## Again\n\n## Again\n")
        val ids = Markdown.headings(document).map { it.id }
        ids shouldBe listOf("title", "a-code-heading", "again", "again-1")
        val html = Markdown.renderHtml(document) { null }
        ids.forEach { html shouldContain "id=\"$it\"" }
    }

    @Test
    fun `missing quoted section fails loudly`() {
        val document = Markdown.parse("# Readme\n\n## Install\n\ntext\n")
        val failure = runCatching { Markdown.extractSection(document, "Quickstart", "README.md") }.exceptionOrNull()
        (failure is SiteBuildException) shouldBe true
        failure!!.message!! shouldContain "Quickstart"
    }

    @Test
    fun `extracted section stops at the next heading of the same level`() {
        val document = Markdown.parse("# R\n\n## A\n\none\n\n### A1\n\ntwo\n\n## B\n\nthree\n")
        val section = Markdown.extractSection(document, "A", "R")
        val text = Markdown.renderMarkdown(section)
        text shouldContain "one"
        text shouldContain "two"
        (text.contains("three")) shouldBe false
    }

    @Test
    fun `console blocks split prompt, command and output`() {
        val html = Html.codeBlock("$ jdx show Foo\nclass Foo\n[exit 2]", "console", executed = true)
        html shouldContain "<span class=\"cmd\">jdx show Foo</span>"
        html shouldContain "<span class=\"out\">class Foo</span>"
        html shouldContain "<span class=\"exit\">[exit 2]</span>"
        html shouldContain "Real output"
    }

    @Test
    fun `json writer escapes script-breaking characters`() {
        JsonWriter.encode(mapOf("a" to "</script>\"\n")) shouldBe "{\"a\":\"\\u003c/script>\\\"\\n\"}"
    }
}
