package dev.jdx.core.render

import dev.jdx.core.model.Origin
import dev.jdx.core.model.Provenance
import dev.jdx.core.model.Warning
import dev.jdx.core.model.WarningCode
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

/**
 * The body block behind `jdx body` (T-022): canonical ref header, source line,
 * verbatim body text, truncation footer, warnings and a `next:` hint — in text
 * and in JSON alike.
 */
class BodyBlockTest {

    private val fileLines = listOf(
        "package com.example;",
        "",
        "public class Point {",
        "    private final int x;",
        "",
        "    public int getX() {",
        "        return x;",
        "    }",
        "}",
    )

    private fun blockOf(
        context: Int = 0,
        numbers: Boolean = false,
        max: Int = Int.MAX_VALUE,
        warnings: List<Warning> = emptyList(),
    ) = buildBodyBlock(
        canonicalRef = "com.example.Point#getX()",
        declaringType = "com.example.Point",
        file = "com/example/Point.java",
        fileLines = fileLines,
        startLine = 6,
        endLine = 8,
        provenance = listOf(
            Provenance(
                artifact = "app-sources.jar",
                origin = Origin.SOURCES,
                file = "com/example/Point.java",
                lineRange = 6..8,
            ),
        ),
        warnings = warnings,
        contextLines = context,
        lineNumbers = numbers,
        maxLines = max,
    )

    @Test
    fun `header names the canonical ref and the source line names artifact file and range`() {
        val text = blockOf().renderText()
        text.lines().first() shouldBe "com.example.Point#getX()"
        text shouldContain "source: app-sources.jar · com/example/Point.java:6-8"
    }

    @Test
    fun `body text is verbatim without indentation`() {
        val text = blockOf().renderText()
        text shouldContain "    public int getX() {"
        text shouldContain "        return x;"
    }

    @Test
    fun `context expands both sides clamped to the file`() {
        val text = blockOf(context = 2).renderText()
        text shouldContain "    private final int x;"
        text shouldContain "}"
        // Two lines above the body only: line 4 exists, line 3 does not appear.
        text shouldNotContain "public class Point {"
    }

    @Test
    fun `line numbers prefix the display start`() {
        val text = blockOf(numbers = true).renderText()
        text shouldContain "6 |     public int getX() {"
        text shouldContain "8 |     }"
    }

    @Test
    fun `max lines cuts from the bottom with an explicit footer`() {
        val text = blockOf(max = 2).renderText()
        text shouldContain "2 of 3 lines shown (--max-lines 3 to see more)"
        text shouldNotContain "    }"
    }

    @Test
    fun `body ends with a copy-pasteable next hint`() {
        blockOf().renderText() shouldContain "next: jdx show com.example.Point"
    }

    @Test
    fun `warnings render after the body`() {
        val text = blockOf(
            warnings = listOf(Warning(WarningCode.DUPLICATE_FQN, "same FQN in two artifacts")),
        ).renderText()
        text shouldContain "warning DUPLICATE_FQN"
    }

    @Test
    fun `rendering is deterministic`() {
        blockOf(context = 2, numbers = true, max = 10).renderText() shouldBe
            blockOf(context = 2, numbers = true, max = 10).renderText()
    }

    @Test
    fun `json carries every text fact`() {
        val block = blockOf(context = 1, numbers = true)
        val json = block.toJson(command = "body")
        json shouldContain "\"command\":\"body\""
        json shouldContain "\"query\":\"com.example.Point#getX()\""
        json shouldContain "\"ref\":\"com.example.Point#getX()\""
        json shouldContain "\"file\":\"com/example/Point.java\""
        json shouldContain "\"lines\":[6,8]"
        json shouldContain "public int getX() {"
        json shouldContain "return x;"
        json shouldContain "\"artifact\":\"app-sources.jar\""
        json shouldContain "\"origin\":\"sources\""
    }

    @Test
    fun `json truncation matches the text footer`() {
        val block = blockOf(max = 1)
        val json = block.toJson(command = "body")
        json shouldContain "\"shown\":1"
        json shouldContain "\"total\":3"
        json shouldContain "--max-lines 3"
        block.renderText() shouldContain "1 of 3 lines shown"
    }

    @Test
    fun `slice clamps an overhanging context window to the file`() {
        val (start, lines, truncation) = sliceBodyLines(fileLines, 8, 8, 5, Int.MAX_VALUE)
        start shouldBe 3
        lines.size shouldBe 7
        truncation shouldBe null
    }

    @Test
    fun `slice on an empty file shows nothing`() {
        val (start, lines, truncation) = sliceBodyLines(emptyList(), 1, 1, 0, 10)
        start shouldBe 1
        lines shouldBe emptyList()
        truncation shouldBe null
    }

    @Test
    fun `with-signature header prints between the source line and the body`() {
        val text = blockOf().copy(signature = "public int getX()").renderText()
        val lines = text.lines()
        lines[1] shouldBe "  source: app-sources.jar · com/example/Point.java:6-8"
        lines[2] shouldBe "  signature: public int getX()"
        lines[3] shouldBe "    public int getX() {"
    }

    @Test
    fun `without the flag no signature line prints and json carries no signature key`() {
        val block = blockOf()
        block.renderText() shouldNotContain "signature:"
        block.toJson(command = "body") shouldNotContain "\"signature\""
    }

    @Test
    fun `json carries the signature header when set`() {
        val json = blockOf().copy(signature = "public int getX()").toJson(command = "body")
        json shouldContain "\"signature\":\"public int getX()\""
    }
}
