package dev.jdx.core.render

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

/**
 * Examples for the hierarchy renderer (T-032, test-first): supertype chains,
 * subtype lists, direction selection, truncation and text⊆JSON parity.
 */
class HierarchyTest {

    private fun supertype(binary: String, relation: String = "extends", depth: Int = 1) =
        SupertypeEntry(binary = binary, relation = relation, artifact = "app.jar", depth = depth)

    private fun subtype(binary: String, via: String? = null) =
        SubtypeEntry(binary = binary, artifact = "app.jar", via = via)

    @Test
    fun `full listing names supertypes then subtypes`() {
        val listing = buildHierarchyListing(
            query = "h.Leaf",
            targetRef = "h.Leaf",
            supertypes = listOf(supertype("h.Middle"), supertype("h.Base", depth = 2)),
            subtypes = listOf(subtype("h.Sprout", via = "extends h.Leaf")),
        )
        val text = listing.renderText()
        text.lines().first() shouldBe "hierarchy of 'h.Leaf'"
        text shouldContain "  supertypes"
        text shouldContain "    extends h.Middle"
        text shouldContain "    extends h.Base"
        text shouldContain "  subtypes in workspace (1)"
        text shouldContain "    class h.Sprout"
        text shouldContain "app.jar"
        text shouldContain "(extends h.Leaf)"
    }

    @Test
    fun `empty sections read as none`() {
        val listing = buildHierarchyListing(
            query = "h.Leaf",
            targetRef = "h.Leaf",
            supertypes = emptyList(),
            subtypes = emptyList(),
        )
        val text = listing.renderText()
        text shouldContain "  supertypes: none"
        text shouldContain "  subtypes in workspace (0)"
    }

    @Test
    fun `disabled directions omit their section`() {
        val upOnly = buildHierarchyListing(
            query = "h.Leaf",
            targetRef = "h.Leaf",
            supertypes = listOf(supertype("h.Base")),
            subtypes = emptyList(),
            showDown = false,
        )
        upOnly.renderText() shouldNotContain "subtypes"
        val downOnly = buildHierarchyListing(
            query = "h.Leaf",
            targetRef = "h.Leaf",
            supertypes = emptyList(),
            subtypes = listOf(subtype("h.Sprout")),
            showUp = false,
        )
        downOnly.renderText() shouldNotContain "supertypes"
    }

    @Test
    fun `truncation keeps whole rows and names the limit flag`() {
        val listing = buildHierarchyListing(
            query = "h.Base",
            targetRef = "h.Base",
            supertypes = emptyList(),
            subtypes = (1..5).map { subtype("h.Child$it") },
            limit = 2,
        )
        listing.subtypes.size shouldBe 2
        val text = listing.renderText()
        text shouldContain "2 of 5 subtypes shown (--limit 5 to see more)"
        text shouldContain "  subtypes in workspace (2)"
    }

    @Test
    fun `supertype outside the workspace prints without an artifact`() {
        val listing = buildHierarchyListing(
            query = "h.Leaf",
            targetRef = "h.Leaf",
            supertypes = listOf(SupertypeEntry("java.lang.Object", "extends", null, 2)),
            subtypes = emptyList(),
        )
        val text = listing.renderText()
        text shouldContain "    extends java.lang.Object\n"
        val json = listing.toJson("hierarchy")
        json shouldContain "\"type\":\"java.lang.Object\""
        json shouldNotContain "\"artifact\":null"
    }

    @Test
    fun `every text row appears in json`() {
        val listing = buildHierarchyListing(
            query = "h.Leaf",
            targetRef = "h.Leaf",
            supertypes = listOf(supertype("h.Middle"), supertype("h.Iface", relation = "implements")),
            subtypes = listOf(subtype("h.Sprout", via = "extends h.Leaf")),
        )
        val json = listing.toJson("hierarchy")
        json shouldContain "\"command\":\"hierarchy\""
        json shouldContain "\"target\":\"h.Leaf\""
        for (entry in listing.supertypes) {
            json shouldContain entry.binary
            json shouldContain entry.relation
            if (entry.artifact != null) json shouldContain entry.artifact
        }
        for (entry in listing.subtypes) {
            json shouldContain entry.binary
            json shouldContain entry.artifact
        }
    }
}
