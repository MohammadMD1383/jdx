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
 * The source block behind `jdx source` (T-023): canonical type-ref header,
 * source line, verbatim file text, truncation footer, warnings and a `next:`
 * hint — in text and in JSON alike.
 */
class SourceBlockTest {

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
        start: Int = 1,
        end: Int = fileLines.size,
        context: Int = 0,
        numbers: Boolean = false,
        max: Int = Int.MAX_VALUE,
        warnings: List<Warning> = emptyList(),
    ) = buildSourceBlock(
        canonicalRef = "com.example.Point",
        declaringType = "com.example.Point",
        file = "com/example/Point.java",
        fileLines = fileLines,
        startLine = start,
        endLine = end,
        provenance = listOf(
            Provenance(
                artifact = "app-sources.jar",
                origin = Origin.SOURCES,
                file = "com/example/Point.java",
                lineRange = start..end,
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
        text.lines().first() shouldBe "com.example.Point"
        text shouldContain "source: app-sources.jar · com/example/Point.java:1-9"
    }

    @Test
    fun `source text is verbatim without indentation`() {
        val text = blockOf().renderText()
        text shouldContain "package com.example;"
        text shouldContain "    public int getX() {"
    }

    @Test
    fun `a lines window shows only that slice`() {
        val text = blockOf(start = 6, end = 8).renderText()
        text shouldContain "    public int getX() {"
        text shouldContain "source: app-sources.jar · com/example/Point.java:6-8"
        text shouldNotContain "package com.example;"
    }

    @Test
    fun `context expands both sides clamped to the file`() {
        val text = blockOf(start = 6, end = 8, context = 2).renderText()
        text shouldContain "    private final int x;"
        text shouldContain "}"
        // Two lines above the window only: line 4 exists, line 3 does not appear.
        text shouldNotContain "public class Point {"
    }

    @Test
    fun `line numbers prefix the display start`() {
        val text = blockOf(start = 6, end = 8, numbers = true).renderText()
        text shouldContain "6 |     public int getX() {"
        text shouldContain "8 |     }"
    }

    @Test
    fun `max lines cuts from the bottom with an explicit footer`() {
        val text = blockOf(max = 2).renderText()
        text shouldContain "2 of 9 lines shown (--max-lines 9 to see more)"
        text shouldNotContain "public class Point {"
    }

    @Test
    fun `source ends with a copy-pasteable next hint`() {
        blockOf().renderText() shouldContain "next: jdx show com.example.Point"
    }

    @Test
    fun `warnings render after the text`() {
        val text = blockOf(
            warnings = listOf(Warning(WarningCode.DUPLICATE_FQN, "same FQN in two artifacts")),
        ).renderText()
        text shouldContain "warning DUPLICATE_FQN"
    }

    @Test
    fun `rendering is deterministic`() {
        blockOf(start = 3, end = 8, context = 2, numbers = true, max = 10).renderText() shouldBe
            blockOf(start = 3, end = 8, context = 2, numbers = true, max = 10).renderText()
    }

    @Test
    fun `json carries every text fact`() {
        val block = blockOf(start = 6, end = 8, numbers = true)
        val json = block.toJson(command = "source")
        json shouldContain "\"command\":\"source\""
        json shouldContain "\"query\":\"com.example.Point\""
        json shouldContain "\"ref\":\"com.example.Point\""
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
        val json = block.toJson(command = "source")
        json shouldContain "\"shown\":1"
        json shouldContain "\"total\":9"
        json shouldContain "--max-lines 9"
        block.renderText() shouldContain "1 of 9 lines shown"
    }

    @Test
    fun `slice clamps an overhanging context window to the file`() {
        val (start, lines, truncation) = sliceSourceLines(fileLines, 8, 9, 5, Int.MAX_VALUE)
        start shouldBe 3
        lines.size shouldBe 7
        truncation shouldBe null
    }

    @Test
    fun `slice on an empty file shows nothing`() {
        val (start, lines, truncation) = sliceSourceLines(emptyList(), 1, 1, 0, 10)
        start shouldBe 1
        lines shouldBe emptyList()
        truncation shouldBe null
    }
}
