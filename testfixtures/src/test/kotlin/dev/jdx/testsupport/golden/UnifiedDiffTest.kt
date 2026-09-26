package dev.jdx.testsupport.golden

import io.kotest.property.Arb
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests for the shared unified-diff renderer (T-054).
 *
 * The diff is what a contributor reads when a golden test fails, so it is
 * pinned byte-for-byte on small cases and held to structural laws on
 * generated ones. Written test-first: this file landed before the helper.
 */
class UnifiedDiffTest {

    @Test
    fun `identical inputs produce an empty diff`() {
        assertEquals("", UnifiedDiff.diff("a\nb\nc", "a\nb\nc"))
    }

    @Test
    fun `CRLF normalises to LF before diffing (autocrlf checkout)`() {
        assertEquals("", UnifiedDiff.diff("a\r\nb\r\nc", "a\nb\nc"))
        assertEquals("", UnifiedDiff.diff("a\rb\rc", "a\nb\nc"))
    }

    @Test
    fun `a single changed line renders one hunk with context`() {
        val expected = listOf("a", "b", "c", "d", "e").joinToString("\n")
        val actual = listOf("a", "b", "X", "d", "e").joinToString("\n")
        assertEquals(
            """
            --- old.txt (expected)
            +++ old.txt (actual)
            @@ -1,5 +1,5 @@
             a
             b
            -c
            +X
             d
             e
            """.trimIndent(),
            UnifiedDiff.diff(expected, actual, "old.txt (expected)", "old.txt (actual)"),
        )
    }

    @Test
    fun `an appended line renders an append hunk`() {
        assertEquals(
            """
            --- expected
            +++ actual
            @@ -1,2 +1,3 @@
             a
             b
            +c
            """.trimIndent(),
            UnifiedDiff.diff("a\nb", "a\nb\nc"),
        )
    }

    @Test
    fun `a deleted line renders minus lines`() {
        assertEquals(
            """
            --- expected
            +++ actual
            @@ -1,3 +1,2 @@
             a
            -b
             c
            """.trimIndent(),
            UnifiedDiff.diff("a\nb\nc", "a\nc"),
        )
    }

    @Test
    fun `an empty expected side renders a pure-addition hunk`() {
        assertEquals(
            """
            --- expected
            +++ actual
            @@ -0,0 +1,2 @@
            +a
            +b
            """.trimIndent(),
            UnifiedDiff.diff("", "a\nb"),
        )
    }

    @Test
    fun `distant changes render separate hunks`() {
        val expected = (1..20).map { "l$it" }.joinToString("\n")
        val actual = (1..20).map { if (it == 2) "A" else if (it == 19) "B" else "l$it" }
            .joinToString("\n")
        assertEquals(
            """
            --- expected
            +++ actual
            @@ -1,5 +1,5 @@
             l1
            -l2
            +A
             l3
             l4
             l5
            @@ -16,5 +16,5 @@
             l16
             l17
             l18
            -l19
            +B
             l20
            """.trimIndent(),
            UnifiedDiff.diff(expected, actual),
        )
    }

    @Test
    fun `a huge diff is capped with an omission trailer`() {
        val expected = (1..500).map { "e$it" }.joinToString("\n")
        val actual = (1..500).map { "a$it" }.joinToString("\n")
        val lines = UnifiedDiff.diff(expected, actual).lines()
        // 2 file headers + 1 hunk header + 200 capped body lines + 1 trailer.
        assertEquals(203, lines.size)
        assertEquals("... (801 more diff lines omitted)", lines.last())
    }

    @Test
    fun `empty diff if and only if inputs are equal for generated text`() = runBlocking<Unit> {
        checkAll(1000, arbMultilineText()) { text ->
            assertEquals("", UnifiedDiff.diff(text, text))
        }
        checkAll(1000, arbMultilineText(), arbMultilineText()) { first, second ->
            assertEquals(first == second, UnifiedDiff.diff(first, second).isEmpty())
        }
    }

    @Test
    fun `a non-empty generated diff starts with headers and balances line counts`() =
        runBlocking<Unit> {
            checkAll(1000, arbMultilineText(), arbMultilineText()) { first, second ->
                val diff = UnifiedDiff.diff(first, second)
                if (first == second) return@checkAll
                assertTrue(diff.startsWith("--- expected\n+++ actual\n@@"), "diff:\n$diff")
                // Every added line beyond every removed line accounts for the
                // line-count delta — the diff neither invents nor loses lines.
                // (Inputs stay small, so the omission cap never engages.)
                val afterHeaders = diff.lines().drop(2)
                assertTrue(
                    afterHeaders.none { it.startsWith("...") },
                    "cap trailer must not appear here, diff:\n$diff",
                )
                val rendered = afterHeaders.filterNot { it.startsWith("@@") }
                val added = rendered.count { it.startsWith("+") }
                val removed = rendered.count { it.startsWith("-") }
                assertEquals(
                    lineCount(second) - lineCount(first),
                    added - removed,
                    "diff:\n$diff",
                )
            }
        }

    private fun arbMultilineText(): Arb<String> =
        Arb.list(Arb.string(0..12), 0..20).map { it.joinToString("\n") }

    /** Mirrors the helper's line model: empty text is zero lines, not one empty line. */
    private fun lineCount(text: String): Int =
        if (text.isEmpty()) 0 else text.split("\n").size
}
