package dev.jdx.site

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class LinkCheckerTest {
    private val files = mapOf(
        "index.html" to """<a href="/jdx/docs/">d</a> <a href="/jdx/docs/#intro">i</a> <a href="https://example.com/x">e</a> <a href="#top">t</a><h1 id="top">x</h1>""",
        "docs/index.html" to """<h2 id="intro">Intro</h2><img src="/jdx/assets/a.gif">""",
        "assets/a.gif" to null,
    )

    @Test
    fun `valid internal links, anchors and external links pass`() {
        LinkChecker.check("/jdx/", files).shouldBeEmpty()
    }

    @Test
    fun `missing pages and anchors are reported`() {
        val broken = files + ("docs/x/index.html" to """<a href="/jdx/nope/">n</a><a href="/jdx/docs/#gone">g</a><a href="#self">s</a>""")
        LinkChecker.check("/jdx/", broken) shouldBe listOf(
            "docs/x/index.html: link to missing anchor `#self`",
            "docs/x/index.html: link to missing anchor `/jdx/docs/#gone`",
            "docs/x/index.html: link to missing page `/jdx/nope/`",
        )
    }
}
