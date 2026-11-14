package dev.jdx.core.render

import dev.jdx.core.gen.JDX_PROPERTY_ITERATIONS
import io.kotest.property.Arb
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.of
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/**
 * Generative laws for the usages renderer (T-030, TESTING.md §4):
 * determinism, truncation accounting and text⊆JSON over generated listings.
 */
class UsagesPropertyTest {

    private val kinds: List<String> = listOf("call", "read", "write", "ref", "new", "throw", "annotation")

    private fun arbHit(): Arb<UsageHit> = Arb.of(kinds).map { kind ->
        val withLocation = (0..1).random() == 0
        val line = if (withLocation) (1..200).random() else null
        val withSnippet = withLocation && (0..1).random() == 0
        UsageHit(
            fromRef = "com.example.App#m${(0..9999).random()}()",
            artifact = "artifact-${(0..9).random()}.jar",
            kind = kind,
            targetRef = "com.example.Lib#greet",
            file = if (withLocation) "App.java" else null,
            line = line,
            snippet = if (withSnippet) {
                val start = (line!! - 1).coerceAtLeast(1)
                SampleSnippet(
                    file = "com/example/App.java",
                    startLine = start,
                    endLine = line + 1,
                    lines = listOf("// ctx-${(0..99).random()}", "callSite();", "// after"),
                    truncated = false,
                )
            } else {
                null
            },
        )
    }

    private fun sorted(hits: List<UsageHit>): List<UsageHit> =
        hits.sortedWith(compareBy({ it.artifact }, { it.fromRef }, { it.kind }, { it.targetRef }))

    @Test
    fun `rendering is deterministic across runs`(): Unit = runBlocking {
        checkAll(JDX_PROPERTY_ITERATIONS, Arb.list(arbHit(), 0..20)) { hits ->
            val ordered = sorted(hits)
            val first = buildUsageListing("q", "t", ordered).renderText()
            val second = buildUsageListing("q", "t", ordered).renderText()
            assert(first == second) { "usages text not deterministic" }
            val firstJson = buildUsageListing("q", "t", ordered).toJson("usages")
            val secondJson = buildUsageListing("q", "t", ordered).toJson("usages")
            assert(firstJson == secondJson) { "usages json not deterministic" }
        }
    }

    @Test
    fun `truncation law holds on generated listings`(): Unit = runBlocking {
        checkAll(JDX_PROPERTY_ITERATIONS, Arb.list(arbHit(), 0..120)) { hits ->
            val ordered = sorted(hits)
            val listing = buildUsageListing("q", "t", ordered, limit = 50)
            assert(listing.hits.size <= 50) { "kept more than the limit" }
            if (ordered.size <= 50) {
                assert(listing.truncation == null) { "exact fit must not truncate" }
            } else {
                val truncation = listing.truncation ?: error("expected truncation")
                assert(truncation.shown == 50 && truncation.total == ordered.size) {
                    "shown/total wrong: $truncation for ${ordered.size}"
                }
                assert(truncation.hint == "--limit ${ordered.size}") { "hint names the total" }
            }
        }
    }

    @Test
    fun `every text hit appears in json`(): Unit = runBlocking {
        checkAll(JDX_PROPERTY_ITERATIONS, Arb.list(arbHit(), 1..20)) { hits ->
            val ordered = sorted(hits).distinct()
            val listing = buildUsageListing("q", "t", ordered)
            val json = listing.toJson("usages")
            val text = listing.renderText()
            for (hit in listing.hits) {
                assert(json.contains(hit.fromRef)) { "${hit.fromRef} missing from json" }
                assert(json.contains(hit.artifact)) { "${hit.artifact} missing from json" }
                assert(json.contains(hit.kind)) { "${hit.kind} missing from json" }
                hit.location()?.let { location ->
                    assert(json.contains("\"file\":\"${hit.file}\"")) { "$location file missing from json" }
                    assert(json.contains("\"line\":${hit.line}")) { "$location line missing from json" }
                    assert(text.contains(location)) { "$location missing from text" }
                }
                hit.snippet?.let { snippet ->
                    assert(json.contains("\"snippet\":")) { "snippet missing from json for ${hit.fromRef}" }
                    assert(json.contains(snippet.file)) { "${snippet.file} missing from json" }
                    assert(text.contains("${snippet.file}:${snippet.startLine}-${snippet.endLine}")) {
                        "snippet range missing from text"
                    }
                    val shown = snippet.lines.take(MAX_SAMPLE_SNIPPET_LINES)
                    for (line in shown) {
                        assert(text.contains(line)) { "snippet line missing from text: $line" }
                    }
                    assert(json.contains(JsonEscape.quote(shown.joinToString("\n")))) {
                        "snippet text missing from json for ${hit.fromRef}"
                    }
                }
            }
        }
    }
}
