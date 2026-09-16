package dev.jdx.core.render

import dev.jdx.core.gen.arbClassGraph
import dev.jdx.core.gen.arbSyntheticToggle
import dev.jdx.core.gen.graphLookup
import dev.jdx.core.gen.graphTarget
import dev.jdx.core.model.Origin
import dev.jdx.core.model.Provenance
import dev.jdx.core.resolve.MemberResolutionOptions
import dev.jdx.core.resolve.MemberResolver
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/**
 * Generative invariants for the renderers (T-010, TESTING.md §4 — the standing
 * test bar demands a generating family, not only examples). Graphs come from the
 * shared T-009 generators: cyclic, colliding, dangling hierarchies.
 */
class MemberListingPropertyTest {

    private fun listingFor(
        graph: dev.jdx.core.gen.ClassGraph,
        includeSynthetic: Boolean,
        maxMembers: Int,
    ): MemberListing {
        val target = graphTarget(graph)
        return buildMemberListing(
            target = target,
            resolved = MemberResolver.resolve(
                target,
                graphLookup(graph),
                MemberResolutionOptions(includeSynthetic = includeSynthetic),
            ),
            provenance = listOf(Provenance(artifact = "test.jar", origin = Origin.BYTECODE)),
            options = MemberListingOptions(maxMembers = maxMembers),
        )
    }

    @Test
    fun `rendering is byte-identical across runs`() = runBlocking<Unit> {
        checkAll(1000, arbClassGraph(), arbSyntheticToggle(), Arb.int(0..10)) { graph, synthetic, max ->
            val first = listingFor(graph, synthetic, max)
            val second = listingFor(graph, synthetic, max)
            first.renderText() shouldBe second.renderText()
            first.toJson(command = "members") shouldBe second.toJson(command = "members")
        }
    }

    @Test
    fun `truncation law holds shown below total with hint exactly when cut`() = runBlocking<Unit> {
        checkAll(1000, arbClassGraph(), arbSyntheticToggle(), Arb.int(0..10)) { graph, synthetic, max ->
            val full = listingFor(graph, synthetic, Int.MAX_VALUE)
            val cut = listingFor(graph, synthetic, max)
            // Eligible rows: every shown row plus collapsed Object rows.
            val eligible = full.groups.sumOf { it.rows.size } + (full.objectSummary?.count ?: 0)
            if (cut.truncation == null) {
                // Nothing cut: the limited listing shows exactly what the full one shows.
                cut.groups.flatMap { it.rows }.map { it.canonicalRef } shouldBe
                    full.groups.flatMap { it.rows }.map { it.canonicalRef }
                cut.objectSummary?.count shouldBe full.objectSummary?.count
            } else {
                val truncation = cut.truncation!!
                (truncation.shown <= truncation.total) shouldBe true
                truncation.hint shouldContain "--limit"
                truncation.total shouldBe eligible
                truncation.shown shouldBe cut.groups.sumOf { it.rows.size }
            }
        }
    }

    @Test
    fun `every text row is covered by the json`() = runBlocking<Unit> {
        checkAll(500, arbClassGraph(), arbSyntheticToggle(), Arb.int(0..10)) { graph, synthetic, max ->
            val listing = listingFor(graph, synthetic, max)
            val json = listing.toJson(command = "members")
            for (row in listing.groups.flatMap { it.rows }) {
                json shouldContain row.signature
                json shouldContain row.canonicalRef
            }
        }
    }

    @Test
    fun `truncation keeps a prefix of the full row order`() = runBlocking<Unit> {
        checkAll(500, arbClassGraph(), arbSyntheticToggle(), Arb.int(0..10)) { graph, synthetic, max ->
            val full = listingFor(graph, synthetic, Int.MAX_VALUE)
            val cut = listingFor(graph, synthetic, max)
            val fullRefs = full.groups.flatMap { it.rows }.map { it.canonicalRef }
            val cutRefs = cut.groups.flatMap { it.rows }.map { it.canonicalRef }
            fullRefs.take(cutRefs.size) shouldBe cutRefs
        }
    }

    @Test
    fun `plain text never contains escapes or absolute paths`() = runBlocking<Unit> {
        checkAll(500, arbClassGraph(), arbSyntheticToggle()) { graph, synthetic ->
            val text = listingFor(graph, synthetic, 50).renderText()
            (text.contains("\u001B")) shouldBe false
            // Generated packages are `p`/`q`: no tmpdir, no home dir may leak through.
            (text.contains(System.getProperty("java.io.tmpdir").trimEnd('/'))) shouldBe false
        }
    }
}
