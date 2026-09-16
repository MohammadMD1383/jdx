package dev.jdx.core.render

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Entity-preserving truncation (T-010, PROPOSAL.md §8.3).
 *
 * Truncation operates on whole member rows, never mid-entity, and always reports
 * `shown`/`total`/`hint` so an agent knows exactly how to see the rest.
 */
class TruncationTest {

    @Test
    fun `no truncation when everything fits`() {
        val rows = listOf("a", "b", "c")
        val (shown, truncation) = truncateEntities(rows, limit = 50) { "--limit $it" }
        shown shouldBe rows
        truncation shouldBe null
    }

    @Test
    fun `exact fit is not truncation`() {
        val rows = listOf("a", "b")
        val (shown, truncation) = truncateEntities(rows, limit = 2) { "--limit $it" }
        shown shouldBe rows
        truncation shouldBe null
    }

    @Test
    fun `overflowing rows are cut at the entity boundary with shown total hint`() {
        val rows = (1..97).map { "row-$it" }
        val (shown, truncation) = truncateEntities(rows, limit = 50) { total -> "--limit $total" }
        shown shouldBe rows.take(50)
        truncation shouldBe Truncation(shown = 50, total = 97, hint = "--limit 97")
    }

    @Test
    fun `a single row over the limit still reports honestly`() {
        val (shown, truncation) = truncateEntities(listOf("only"), limit = 0) { "--limit $it" }
        shown shouldBe emptyList()
        truncation shouldBe Truncation(shown = 0, total = 1, hint = "--limit 1")
    }

    @Test
    fun `truncation invariant holds shown below total`() {
        val rows = (1..10).map { "row-$it" }
        val (shown, truncation) = truncateEntities(rows, limit = 3) { "--limit $it" }
        shown.size shouldBe 3
        (truncation!!.shown <= truncation.total) shouldBe true
    }
}
