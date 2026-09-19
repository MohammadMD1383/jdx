package dev.jdx.core.render

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

/**
 * The ANSI gate (T-010): colour only on headers, plain text everywhere else,
 * and `stripAnsi` recovers the input exactly. Each header shape is pinned
 * because PIT found every `isHeader` branch unasserted (T-060).
 */
class AnsiTest {

    @Test
    fun `a members header is bolded`() {
        val out = Ansi.colorizeListing("members of com.example.Point")
        out shouldContain "\u001B[1m"
        out.stripAnsi() shouldBe "members of com.example.Point"
    }

    @Test
    fun `a group header ending in a colon is bolded`() {
        val out = Ansi.colorizeListing("methods declared on com.example.Point:")
        out shouldContain "\u001B[1m"
        out.stripAnsi() shouldBe "methods declared on com.example.Point:"
    }

    @Test
    fun `an indented colon line is not a header`() {
        // `!line.startsWith(" ")`: the declaration line ends in no colon and is
        // indented — both conditions must hold for the pass-through.
        val out = Ansi.colorizeListing("  public class Point")
        out shouldNotContain "\u001B"
    }

    @Test
    fun `a warning line is bolded`() {
        val out = Ansi.colorizeListing("warning DUPLICATE_FQN: two jars provide Foo")
        out shouldContain "\u001B[1m"
        out.stripAnsi() shouldBe "warning DUPLICATE_FQN: two jars provide Foo"
    }

    @Test
    fun `a truncation footer is bolded`() {
        val out = Ansi.colorizeListing("10 of 25 members shown (--limit 25 to see more)")
        out shouldContain "\u001B[1m"
        out.stripAnsi() shouldBe "10 of 25 members shown (--limit 25 to see more)"
    }

    @Test
    fun `a member row passes through untouched`() {
        val out = Ansi.colorizeListing("  method public int getX()")
        out shouldBe "  method public int getX()"
    }

    @Test
    fun `multiline output bolds only headers`() {
        val out = Ansi.colorizeListing("members of Foo\n  method void bar()\nwarning W: m")
        val lines = out.lines()
        lines[0] shouldContain "\u001B[1m"
        lines[1] shouldBe "  method void bar()"
        lines[2] shouldContain "\u001B[1m"
    }
}
