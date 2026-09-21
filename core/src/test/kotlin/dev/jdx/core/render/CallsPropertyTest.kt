package dev.jdx.core.render

import dev.jdx.core.gen.JDX_PROPERTY_ITERATIONS
import io.kotest.property.Arb
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.map
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/**
 * Generative laws for the call-hierarchy renderer (T-033, TESTING.md §4):
 * determinism, truncation accounting and the pre-order prefix law over
 * generated trees.
 */
class CallsPropertyTest {

    private fun arbNode(depth: Int): Arb<CallNode> {
        // Alphanumeric names only: refs carrying quotes or backslashes are
        // JSON-escaped, which would fail a naive `json.contains(ref)` law
        // for escaping reasons rather than missing facts.
        val ref = Arb.int(0..99999).map { "c.M$it#m$it()" }
        val artifact = Arb.int(0..4).map { if (it == 0) null else "artifact-$it.jar" }
        if (depth <= 0) {
            return Arb.bind(ref, artifact) { r, a -> CallNode(ref = r, artifact = a) }
        }
        return Arb.bind(ref, artifact, Arb.list(arbNode(depth - 1), 0..3)) { r, a, kids ->
            // A cycle row is a leaf by construction — the marker names the
            // re-entry, descending would print the subtree twice.
            val cycle = kids.isNotEmpty() && (r.hashCode() % 5 == 0)
            CallNode(ref = r, artifact = a, cycle = cycle, children = if (cycle) emptyList() else kids)
        }
    }

    private fun arbForest(): Arb<List<CallNode>> = Arb.list(arbNode(2), 0..5)

    @Test
    fun `rendering is deterministic across runs`(): Unit = runBlocking {
        checkAll(JDX_PROPERTY_ITERATIONS, arbForest()) { roots ->
            val first = buildCallListing("q", "t", CallDirection.CALLERS, roots).renderText()
            val second = buildCallListing("q", "t", CallDirection.CALLERS, roots).renderText()
            assert(first == second) { "calls text not deterministic" }
            val firstJson = buildCallListing("q", "t", CallDirection.CALLERS, roots).toJson("callers")
            val secondJson = buildCallListing("q", "t", CallDirection.CALLERS, roots).toJson("callers")
            assert(firstJson == secondJson) { "calls json not deterministic" }
        }
    }

    @Test
    fun `truncation law holds on generated trees`(): Unit = runBlocking {
        checkAll(JDX_PROPERTY_ITERATIONS, arbForest()) { roots ->
            val full = buildCallListing("q", "t", CallDirection.CALLS, roots, limit = Int.MAX_VALUE)
            val total = full.rows.size
            val listing = buildCallListing("q", "t", CallDirection.CALLS, roots, limit = 50)
            assert(listing.rows.size <= 50) { "kept more than the limit" }
            if (total <= 50) {
                assert(listing.truncation == null) { "exact fit must not truncate" }
            } else {
                val truncation = listing.truncation ?: error("expected truncation")
                assert(truncation.shown == 50 && truncation.total == total) {
                    "shown/total wrong: $truncation for $total"
                }
                assert(truncation.hint == "--limit $total") { "hint names the total" }
            }
            // The pre-order prefix law: roots sit at depth 1 and no row jumps
            // more than one level deeper than its predecessor — truncation can
            // never orphan a child from its parent.
            val rows = listing.rows
            if (rows.isNotEmpty()) {
                assert(rows.first().depth == 1) { "first row must be a root" }
                rows.zipWithNext().forEach { (prev, next) ->
                    assert(next.depth <= prev.depth + 1) { "orphaned row: $prev then $next" }
                }
            }
        }
    }

    @Test
    fun `every text row appears in json`(): Unit = runBlocking {
        checkAll(JDX_PROPERTY_ITERATIONS, arbForest()) { roots ->
            val listing = buildCallListing("q", "t", CallDirection.CALLERS, roots)
            val json = listing.toJson("callers")
            for (row in listing.rows) {
                assert(json.contains(row.ref)) { "ref missing from json: ${row.ref}" }
                row.artifact?.let { assert(json.contains(it)) { "artifact missing from json: $it" } }
            }
        }
    }
}
