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
 * Generative laws for the search/ls/tree renderers (T-017, TESTING.md §4):
 * determinism, truncation accounting and text⊆JSON over generated listings.
 */
class SearchResultsPropertyTest {

    private val words: List<String> = listOf("class", "method", "field", "package", "module", "interface", "enum")

    private fun arbHit(): Arb<SearchHit> = Arb.of(words).map { kind ->
        SearchHit(
            kind = kind,
            ref = "com.example.Class${(0..9999).random()}",
            artifact = "artifact-${(0..9).random()}.jar",
            packageName = "com.example",
        )
    }

    @Test
    fun `rendering is deterministic across runs`(): Unit = runBlocking {
        checkAll(JDX_PROPERTY_ITERATIONS, Arb.list(arbHit(), 0..20)) { hits ->
            val sorted = hits.sortedWith(compareBy({ it.ref }, { it.artifact }))
            val first = buildSearchListing("q", sorted).renderText()
            val second = buildSearchListing("q", sorted).renderText()
            assert(first == second) { "search text not deterministic" }
            val firstJson = buildSearchListing("q", sorted).toJson("search")
            val secondJson = buildSearchListing("q", sorted).toJson("search")
            assert(firstJson == secondJson) { "search json not deterministic" }
        }
    }

    @Test
    fun `truncation law holds on generated listings`(): Unit = runBlocking {
        checkAll(JDX_PROPERTY_ITERATIONS, Arb.list(arbHit(), 0..120)) { hits ->
            val sorted = hits.sortedWith(compareBy({ it.ref }, { it.artifact }))
            val listing = buildSearchListing("q", sorted, limit = 50)
            assert(listing.hits.size <= 50) { "kept more than the limit" }
            if (sorted.size <= 50) {
                assert(listing.truncation == null) { "exact fit must not truncate" }
            } else {
                val truncation = listing.truncation ?: error("expected truncation")
                assert(truncation.shown == 50 && truncation.total == sorted.size) {
                    "shown/total wrong: $truncation for ${sorted.size}"
                }
                assert(truncation.hint == "--limit ${sorted.size}") { "hint names the total" }
            }
        }
    }

    @Test
    fun `every text hit appears in json`(): Unit = runBlocking {
        checkAll(JDX_PROPERTY_ITERATIONS, Arb.list(arbHit(), 1..20)) { hits ->
            val sorted = hits.sortedWith(compareBy({ it.ref }, { it.artifact })).distinct()
            val listing = buildSearchListing("q", sorted)
            val json = listing.toJson("search")
            for (hit in listing.hits) {
                assert(json.contains(hit.ref)) { "${hit.ref} missing from json" }
                assert(json.contains(hit.artifact)) { "${hit.artifact} missing from json" }
            }
        }
    }

    @Test
    fun `tree node counts equal the forest size`(): Unit = runBlocking {
        checkAll(JDX_PROPERTY_ITERATIONS, Arb.list(Arb.of("com.a", "com.b", "org.c.d", "org.c.e"), 1..4)) { names ->
            val distinct = names.distinct()
            val subtree = mutableMapOf<String, Int>()
            for (name in distinct) {
                val segments = name.split('.')
                for (end in 1..segments.size) {
                    val path = segments.take(end).joinToString(".")
                    subtree[path] = (subtree[path] ?: 0) + 1
                }
            }
            val tree = buildArtifactTree("a.jar", distinct, typeCountOf = { subtree.getValue(it) })
            val counted = countNodes(tree.roots)
            // Every distinct full path plus every intermediate prefix is one node.
            val expected = subtree.size
            assert(counted == expected) { "counted $counted, expected $expected" }
        }
    }
}
