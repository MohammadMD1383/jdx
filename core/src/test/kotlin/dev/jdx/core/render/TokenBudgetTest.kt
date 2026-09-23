package dev.jdx.core.render

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Whole-line text caps for token budgets (T-047, PROPOSAL.md §3.1).
 *
 * `--limit` caps entities; `--max-lines` caps text lines. The cap keeps whole
 * lines only (never splits an entity mid-line) and always names the
 * continuation, so a cut is never silent (G6).
 */
class TokenBudgetTest {

    @Test
    fun `short text passes through untouched`() {
        TokenBudget.capLines("a\nb\nc", maxLines = 200) shouldBe "a\nb\nc"
    }

    @Test
    fun `exact fit is not a cut`() {
        TokenBudget.capLines("a\nb", maxLines = 2) shouldBe "a\nb"
    }

    @Test
    fun `overflow keeps the first lines and names the continuation`() {
        TokenBudget.capLines("a\nb\nc\nd", maxLines = 2) shouldBe
            "a\nb\n… 2 more lines (--max-lines 4 to see more)"
    }

    @Test
    fun `zero keeps only the footer`() {
        TokenBudget.capLines("a\nb", maxLines = 0) shouldBe
            "… 2 more lines (--max-lines 2 to see more)"
    }

    @Test
    fun `empty text stays empty`() {
        TokenBudget.capLines("", maxLines = 10) shouldBe ""
    }

    @Test
    fun `single trailing newline does not count a phantom line`() {
        TokenBudget.capLines("a\nb\n", maxLines = 2) shouldBe "a\nb\n"
    }
}
