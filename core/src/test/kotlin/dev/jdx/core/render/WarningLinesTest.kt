package dev.jdx.core.render

import dev.jdx.core.model.Warning
import dev.jdx.core.model.WarningCode
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Text-mode warning peel + cap (issue #86): adapters move these lines to
 * stderr so a flood of `CORRUPT_CLASS` cannot bury the answer on stdout.
 */
class WarningLinesTest {

    @Test
    fun `line matches the historical renderer layout`() {
        WarningLines.line(Warning(WarningCode.CORRUPT_CLASS, "bad bytes")) shouldBe
            "warning CORRUPT_CLASS: bad bytes"
    }

    @Test
    fun `partition peels warning lines and keeps the answer`() {
        val rendered = listOf(
            "implementors of com.example.Service",
            "  class com.example.Impl",
            "warning CORRUPT_CLASS: skipped Foo",
            "warning UNRESOLVED_SUPERTYPE: missing Bar",
            "source: lib.jar (bytecode)",
        ).joinToString("\n")
        val part = WarningLines.partition(rendered)
        part.answer shouldBe listOf(
            "implementors of com.example.Service",
            "  class com.example.Impl",
            "source: lib.jar (bytecode)",
        ).joinToString("\n")
        part.warningLines shouldBe listOf(
            "warning CORRUPT_CLASS: skipped Foo",
            "warning UNRESOLVED_SUPERTYPE: missing Bar",
        )
    }

    @Test
    fun `partition strips ANSI from warning lines but keeps it on the answer`() {
        val bold = "\u001B[1m"
        val reset = "\u001B[0m"
        val rendered = "${bold}members of Foo$reset\n${bold}warning DUPLICATE_FQN: two jars$reset"
        val part = WarningLines.partition(rendered)
        part.answer shouldBe "${bold}members of Foo$reset"
        part.warningLines shouldBe listOf("warning DUPLICATE_FQN: two jars")
    }

    @Test
    fun `cap keeps the first N per code and summarises the rest`() {
        val lines = (1..7).map { "warning CORRUPT_CLASS: class$it" } +
            listOf("warning DUPLICATE_FQN: once")
        WarningLines.cap(lines, maxPerCode = 3) shouldBe listOf(
            "warning CORRUPT_CLASS: class1",
            "warning CORRUPT_CLASS: class2",
            "warning CORRUPT_CLASS: class3",
            "warning DUPLICATE_FQN: once",
            "warning CORRUPT_CLASS: … and 4 more",
        )
    }

    @Test
    fun `cap with zero keeps only the per-code summaries`() {
        val lines = listOf(
            "warning CORRUPT_CLASS: a",
            "warning CORRUPT_CLASS: b",
            "warning DUPLICATE_FQN: c",
        )
        WarningLines.cap(lines, maxPerCode = 0) shouldBe listOf(
            "warning CORRUPT_CLASS: … and 2 more",
            "warning DUPLICATE_FQN: … and 1 more",
        )
    }

    @Test
    fun `empty input stays empty`() {
        WarningLines.partition("") shouldBe WarningLines.Partition("", emptyList())
        WarningLines.cap(emptyList()) shouldBe emptyList()
    }

    @Test
    fun `write emits each line with a trailing newline`() {
        val sink = StringBuilder()
        WarningLines.write(
            listOf("warning CORRUPT_CLASS: a", "warning DUPLICATE_FQN: b"),
            sink,
        )
        sink.toString() shouldBe "warning CORRUPT_CLASS: a\nwarning DUPLICATE_FQN: b\n"
    }

    @Test
    fun `write is a no-op for an empty list`() {
        val sink = StringBuilder()
        WarningLines.write(emptyList(), sink)
        sink.toString() shouldBe ""
    }
}
