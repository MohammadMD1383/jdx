package dev.jdx.core.render

import dev.jdx.core.gen.JDX_PROPERTY_ITERATIONS
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
 * Generative laws for the hierarchy renderer (T-032, TESTING.md §4):
 * determinism, truncation accounting and text⊆JSON over generated listings.
 */
class HierarchyPropertyTest {

    private fun arbBinary(): Arb<String> =
        Arb.string(1..12).map { s ->
            "h." + s.filter { it.isLetterOrDigit() }.ifEmpty { "x" }
        }

    private fun arbSupertype(): Arb<SupertypeEntry> = Arb.of("extends", "implements").map { relation ->
        SupertypeEntry(
            binary = "h.Super${(0..9999).random()}",
            relation = relation,
            artifact = "artifact-${(0..9).random()}.jar",
            depth = (1..4).random(),
        )
    }

    private fun arbSubtype(): Arb<SubtypeEntry> = arbBinary().map { binary ->
        SubtypeEntry(
            binary = binary,
            artifact = "artifact-${(0..9).random()}.jar",
            via = if ((0..1).random() == 0) "extends h.Base" else null,
        )
    }

    @Test
    fun `rendering is deterministic across runs`(): Unit = runBlocking {
        checkAll(
            JDX_PROPERTY_ITERATIONS,
            Arb.list(arbSupertype(), 0..10),
            Arb.list(arbSubtype(), 0..20),
        ) { supertypes, subtypes ->
            val ordered = subtypes.sortedBy { it.binary }.distinctBy { it.binary }
            val first = buildHierarchyListing("q", "t", supertypes, ordered).renderText()
            val second = buildHierarchyListing("q", "t", supertypes, ordered).renderText()
            assert(first == second) { "hierarchy text not deterministic" }
            val firstJson = buildHierarchyListing("q", "t", supertypes, ordered).toJson("hierarchy")
            val secondJson = buildHierarchyListing("q", "t", supertypes, ordered).toJson("hierarchy")
            assert(firstJson == secondJson) { "hierarchy json not deterministic" }
        }
    }

    @Test
    fun `truncation law holds on generated listings`(): Unit = runBlocking {
        checkAll(JDX_PROPERTY_ITERATIONS, Arb.list(arbSubtype(), 0..120), Arb.int(0..120)) { subtypes, limit ->
            val ordered = subtypes.sortedBy { it.binary }.distinctBy { it.binary }
            val listing = buildHierarchyListing("q", "t", emptyList(), ordered, limit = limit)
            assert(listing.subtypes.size <= limit) { "kept more than the limit" }
            if (ordered.size <= limit) {
                assert(listing.truncation == null) { "exact fit must not truncate" }
            } else {
                val truncation = listing.truncation ?: error("expected truncation")
                assert(truncation.shown == limit && truncation.total == ordered.size) {
                    "shown/total wrong: $truncation for ${ordered.size}"
                }
                assert(truncation.hint == "--limit ${ordered.size}") { "hint names the total" }
            }
        }
    }

    @Test
    fun `every text subtype appears in json`(): Unit = runBlocking {
        checkAll(JDX_PROPERTY_ITERATIONS, Arb.list(arbSubtype(), 1..20)) { subtypes ->
            val ordered = subtypes.sortedBy { it.binary }.distinctBy { it.binary }
            val listing = buildHierarchyListing("q", "t", emptyList(), ordered)
            val json = listing.toJson("hierarchy")
            for (entry in listing.subtypes) {
                assert(json.contains(entry.binary)) { "${entry.binary} missing from json" }
                assert(json.contains(entry.artifact)) { "${entry.artifact} missing from json" }
            }
        }
    }
}
