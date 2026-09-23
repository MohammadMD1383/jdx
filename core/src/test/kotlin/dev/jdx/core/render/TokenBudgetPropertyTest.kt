package dev.jdx.core.render

import dev.jdx.core.gen.JDX_PROPERTY_ITERATIONS
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.of
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/**
 * The generating family behind the text line cap (T-047, TESTING.md §4): the
 * cap never splits a line, never throws on hostile input, and is
 * deterministic. The TESTING.md §4 laws in miniature: "truncated output never
 * splits an entity" and "shown <= total, and hint is present iff shown <
 * total".
 */
class TokenBudgetPropertyTest {

    // Single-line content: newlines and carriage returns would add lines of
    // their own, so they are folded away — the hostile test below covers them.
    private fun arbLine(): Arb<String> =
        Arb.string(0..24).map { it.replace("\r", "").replace("\n", " ") }

    private fun arbText(): Arb<String> =
        Arb.list(arbLine(), 0..12).map { lines -> lines.joinToString("\n") }

    @Test
    fun `capped lines are always a whole-line prefix of the input`() = runBlocking<Unit> {
        checkAll(JDX_PROPERTY_ITERATIONS, arbText(), Arb.int(0..20)) { text, max ->
            val capped = TokenBudget.capLines(text, max)
            if (text.isEmpty()) {
                // Empty text has no lines to cut — never a footer.
                capped shouldBe ""
            } else {
                val effective = max.coerceAtLeast(0)
                // POSIX line counting (the TokenBudget contract): one trailing
                // newline terminates the last line instead of opening a phantom
                // one — `Arb.list` can emit a trailing empty line, which joins
                // into exactly that shape.
                val rawLines = text.split("\n")
                val inputLines =
                    if (rawLines.size > 1 && rawLines.last().isEmpty()) rawLines.dropLast(1)
                    else rawLines
                if (inputLines.size <= effective) {
                    // Everything fit: an exact fit is not a cut.
                    capped shouldBe text
                } else {
                    // Cut: a whole-line prefix plus the explicit continuation footer.
                    val kept = inputLines.take(effective)
                    val omitted = inputLines.size - kept.size
                    (omitted > 0) shouldBe true
                    val expected = (if (kept.isEmpty()) "" else kept.joinToString("\n") + "\n") +
                        "… $omitted more lines (--max-lines ${inputLines.size} to see more)"
                    capped shouldBe expected
                }
            }
        }
    }

    @Test
    fun `never throws on hostile input and stays deterministic`() = runBlocking<Unit> {
        val hostile = listOf('"', '\\', '\n', '\r', '\t', '\u0000', '\u001F', ' ', 'λ', '中', ' ', ' ', '…')
        val arbHostile = Arb.list(Arb.of(hostile), 0..40).map { chars -> chars.joinToString("") }
        checkAll(JDX_PROPERTY_ITERATIONS, arbHostile, Arb.int(-5..40)) { text, max ->
            val first = TokenBudget.capLines(text, max)
            val second = TokenBudget.capLines(text, max)
            first shouldBe second
            // Universal newlines: no raw carriage return may leak into the output.
            (first.contains('\r')) shouldBe false
        }
    }
}
