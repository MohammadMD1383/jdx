package dev.jdx.core.render

import dev.jdx.core.gen.JDX_PROPERTY_ITERATIONS
import io.kotest.property.Arb
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/**
 * Generative laws for the samples renderer (T-034, TESTING.md §4):
 * determinism, the ranking law, truncation accounting and text⊆JSON over
 * generated hit lists.
 */
class SamplesPropertyTest {

    private fun arbSnippet(): Arb<SampleSnippet?> =
        Arb.bind(
            Arb.int(0..99).map { "s/App$it.java" },
            Arb.int(1..500),
            Arb.list(Arb.string(1, 40), 1..20),
            Arb.int(0..2),
        ) { file, start, lines, flag ->
            if (flag == 0) {
                null
            } else {
                SampleSnippet(
                    file = file,
                    startLine = start,
                    endLine = start + lines.size - 1,
                    lines = lines,
                    truncated = false,
                )
            }
        }

    private fun arbHit(): Arb<SampleHit> =
        Arb.bind(
            Arb.int(0..99999).map { "s.M$it#m$it()" },
            Arb.int(0..4).map { "artifact-$it.jar" },
            Arb.int(0..99).map { "s.Lib#m$it()" },
            arbSnippet(),
        ) { from, artifact, target, snippet ->
            SampleHit(fromRef = from, artifact = artifact, targetRef = target, snippet = snippet)
        }

    @Test
    fun `rendering is deterministic across runs`(): Unit = runBlocking {
        checkAll(JDX_PROPERTY_ITERATIONS, Arb.list(arbHit(), 0..8)) { hits ->
            val first = buildSampleListing("q", "t", hits).renderText()
            val second = buildSampleListing("q", "t", hits).renderText()
            assert(first == second) { "samples text not deterministic" }
            val firstJson = buildSampleListing("q", "t", hits).toJson("samples")
            val secondJson = buildSampleListing("q", "t", hits).toJson("samples")
            assert(firstJson == secondJson) { "samples json not deterministic" }
        }
    }

    @Test
    fun `truncation law holds on generated hit lists`(): Unit = runBlocking {
        checkAll(JDX_PROPERTY_ITERATIONS, Arb.list(arbHit(), 0..12)) { hits ->
            val full = buildSampleListing("q", "t", hits, limit = Int.MAX_VALUE)
            val total = full.rows.size
            val listing = buildSampleListing("q", "t", hits, limit = 3)
            assert(listing.rows.size <= 3) { "kept more than the limit" }
            if (total <= 3) {
                assert(listing.truncation == null) { "exact fit must not truncate" }
            } else {
                val truncation = listing.truncation ?: error("expected truncation")
                assert(truncation.shown == 3 && truncation.total == total) {
                    "shown/total wrong: $truncation for $total"
                }
                assert(truncation.hint == "--limit $total") { "hint names the total" }
            }
            // Ranked input renders in order: truncation keeps the prefix.
            assert(listing.rows == full.rows.take(listing.rows.size)) {
                "truncation must keep the ranking prefix"
            }
        }
    }

    @Test
    fun `every text row appears in json`(): Unit = runBlocking {
        checkAll(JDX_PROPERTY_ITERATIONS, Arb.list(arbHit(), 0..8)) { hits ->
            val listing = buildSampleListing("q", "t", hits)
            val json = listing.toJson("samples")
            for (row in listing.rows) {
                assert(json.contains(row.fromRef)) { "from missing from json: ${row.fromRef}" }
                assert(json.contains(row.artifact)) { "artifact missing from json: ${row.artifact}" }
                assert(json.contains(row.targetRef)) { "target missing from json: ${row.targetRef}" }
            }
        }
    }

    @Test
    fun `ranking law holds on generated refs`(): Unit = runBlocking {
        checkAll(
            JDX_PROPERTY_ITERATIONS,
            Arb.list(Arb.string(1, 24), 0..10),
            Arb.list(Arb.int(0..4), 0..10),
        ) { refs, params ->
            val keys = refs.zip(params).map { (ref, count) ->
                sampleOrderKey(ref, count, sourcedFirst = false, hasSources = false)
            }
            // Rank keys are totally ordered: sorting never throws and is stable.
            val sorted = keys.sorted()
            sorted.zipWithNext().forEach { (a, b) ->
                assert(a <= b) { "rank keys not totally ordered: $a then $b" }
            }
            // Fuller overloads sort before thinner ones for the same caller.
            val thin = sampleOrderKey("s.App#run()", 1, sourcedFirst = false, hasSources = false)
            val full = sampleOrderKey("s.App#run()", 3, sourcedFirst = false, hasSources = false)
            assert(full < thin) { "fuller overload must rank first" }
            // Non-test sorts before test for the same shape.
            val plain = sampleOrderKey("s.App#run()", 1, sourcedFirst = false, hasSources = false)
            val test = sampleOrderKey("s.AppTest#run()", 1, sourcedFirst = false, hasSources = false)
            assert(plain < test) { "plain caller must rank before test" }
        }
    }
}
