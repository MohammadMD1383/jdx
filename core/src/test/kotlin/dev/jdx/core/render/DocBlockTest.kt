package dev.jdx.core.render

import dev.jdx.core.model.Origin
import dev.jdx.core.model.Provenance
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

/**
 * Examples for the doc renderer (T-025, PROPOSAL.md §7.1): one symbol's
 * rendered javadoc with provenance — the one result model both renderers
 * read, mirroring [BodyBlock].
 */
class DocBlockTest {

    private fun provenance() = listOf(
        Provenance(
            artifact = "fixture-sources.jar",
            origin = Origin.SOURCES,
            file = "com/example/A.java",
            lineRange = 10..14,
        ),
    )

    private fun block(
        raw: Boolean = false,
        inheritedFrom: String? = null,
        maxLines: Int = Int.MAX_VALUE,
    ) = buildDocBlock(
        canonicalRef = "com.example.A#doIt()",
        declaringType = "com.example.A",
        subject = DocSubject.METHOD,
        file = "com/example/A.java",
        startLine = 10,
        endLine = 14,
        rendered = listOf("Does the thing.", "", "@param value the input", "@return the output"),
        provenance = provenance(),
        raw = raw,
        inheritedFrom = inheritedFrom,
        maxLines = maxLines,
    )

    @Test
    fun `text shows the ref, the source line and the doc`() {
        val text = block().renderText()
        val lines = text.lines()
        lines.first() shouldBe "com.example.A#doIt()"
        text shouldContain "source: fixture-sources.jar · com/example/A.java:10-14"
        text shouldContain "Does the thing."
        text shouldContain "@param value the input"
        text.lines().last() shouldBe "next: jdx show com.example.A"
    }

    @Test
    fun `inherited docs are labelled with the provider`() {
        val text = block(inheritedFrom = "com.example.Base").renderText()
        text shouldContain "(inherited from com.example.Base)"
        text shouldContain "next: jdx show com.example.A"
    }

    @Test
    fun `direct docs carry no inheritance label`() {
        block().renderText() shouldNotContain "inherited from"
    }

    @Test
    fun `max-lines truncates whole lines with a footer`() {
        val text = block(maxLines = 2).renderText()
        text shouldContain "Does the thing."
        text shouldNotContain "@return the output"
        text shouldContain "2 of 4 lines shown (--max-lines 4 to see more)"
    }

    @Test
    fun `exact fit is not truncation`() {
        block(maxLines = 4).renderText() shouldNotContain "shown"
    }

    @Test
    fun `json carries every text fact structurally`() {
        val json = block(inheritedFrom = "com.example.Base").toJson(command = "doc")
        json shouldContain "\"command\":\"doc\""
        json shouldContain "\"ok\":true"
        json shouldContain "\"ref\":\"com.example.A#doIt()\""
        json shouldContain "\"declaring\":\"com.example.A\""
        json shouldContain "\"kind\":\"method\""
        json shouldContain "\"inheritedFrom\":\"com.example.Base\""
        json shouldContain "\"file\":\"com/example/A.java\""
        json shouldContain "\"lines\":[10,14]"
        json shouldContain "Does the thing."
        json shouldContain "@param value the input"
        json shouldContain "\"origin\":\"sources\""
    }

    @Test
    fun `json omits inheritedFrom when direct`() {
        block().toJson(command = "doc") shouldNotContain "inheritedFrom"
    }

    @Test
    fun `type subjects render with the type kind`() {
        val typeBlock = buildDocBlock(
            canonicalRef = "com.example.A",
            declaringType = "com.example.A",
            subject = DocSubject.TYPE,
            file = "com/example/A.java",
            startLine = 3,
            endLine = 5,
            rendered = listOf("Main class."),
            provenance = provenance(),
        )
        typeBlock.renderText().lines().first() shouldBe "com.example.A"
        typeBlock.toJson(command = "doc") shouldContain "\"kind\":\"type\""
    }
}
