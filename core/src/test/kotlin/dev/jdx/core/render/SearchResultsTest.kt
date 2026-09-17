package dev.jdx.core.render

import dev.jdx.core.model.TypeKind
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

/**
 * Examples for the search/ls/tree renderers (T-017): layout, truncation and
 * the text⊆JSON contract (D-007). Generative laws live in
 * `SearchResultsPropertyTest`.
 */
class SearchResultsTest {

    private fun hit(kind: String = "class", ref: String, artifact: String = "gson-2.14.0.jar"): SearchHit =
        SearchHit(kind = kind, ref = ref, artifact = artifact, packageName = ref.substringBeforeLast('.', ""))

    // -- search listing -------------------------------------------------------

    @Test
    fun `search text names every hit canonically`() {
        val listing = buildSearchListing(
            query = "JsonAdapter",
            hits = listOf(
                hit(ref = "com.google.gson.annotations.JsonAdapter"),
                hit(ref = "com.google.gson.internal.bind.JsonAdapterAnnotationTypeAdapterFactory"),
            ),
        )
        val text = listing.renderText()
        text shouldContain "search results for 'JsonAdapter'"
        text shouldContain "  class com.google.gson.annotations.JsonAdapter  gson-2.14.0.jar"
        text shouldContain "  class com.google.gson.internal.bind.JsonAdapterAnnotationTypeAdapterFactory  gson-2.14.0.jar"
        text shouldNotContain "shown"
    }

    @Test
    fun `search truncates whole rows with a limit hint`() {
        val listing = buildSearchListing(
            query = "*",
            hits = (1..60).map { hit(ref = "com.example.Class$it") },
            limit = 50,
        )
        listing.renderText() shouldContain "50 of 60 results shown (--limit 60 to see more)"
        listing.hits.size shouldBe 50
    }

    @Test
    fun `search json carries every text row`() {
        val listing = buildSearchListing(
            query = "Gson",
            hits = listOf(
                hit(ref = "com.google.gson.Gson"),
                hit(kind = "method", ref = "com.google.gson.Gson#fromJson(java.lang.String)"),
            ),
        )
        val json = listing.toJson("search")
        for (row in listing.hits) {
            json shouldContain row.ref
            json shouldContain row.kind
            json shouldContain row.artifact
        }
    }

    @Test
    fun `search kind words cover every type kind`() {
        searchKindWord(TypeKind.CLASS) shouldBe "class"
        searchKindWord(TypeKind.INTERFACE) shouldBe "interface"
        searchKindWord(TypeKind.ENUM) shouldBe "enum"
        searchKindWord(TypeKind.RECORD) shouldBe "record"
        searchKindWord(TypeKind.ANNOTATION) shouldBe "annotation"
    }

    // -- ls listing -----------------------------------------------------------

    @Test
    fun `ls packages mode lists names with counts`() {
        val listing = buildLsListing(
            query = "com.google.*",
            packages = listOf(PackageEntry("com.google.gson", 12), PackageEntry("com.google.gson.annotations", 3)),
            types = emptyList(),
        )
        val text = listing.renderText()
        text shouldContain "packages matching 'com.google.*'"
        text shouldContain "  package com.google.gson (12 types)"
        text shouldContain "  package com.google.gson.annotations (3 types)"
    }

    @Test
    fun `ls exact package lists its types`() {
        val listing = buildLsListing(
            query = "com.google.gson",
            packages = emptyList(),
            types = listOf(LsTypeEntry("class", "com.google.gson.Gson", "gson-2.14.0.jar")),
        )
        val text = listing.renderText()
        text shouldContain "types in package 'com.google.gson'"
        text shouldContain "  class com.google.gson.Gson  gson-2.14.0.jar"
    }

    @Test
    fun `ls json carries packages and types`() {
        val listing = buildLsListing(
            query = "*",
            packages = listOf(PackageEntry("com.example", 2)),
            types = listOf(LsTypeEntry("class", "com.example.Point", "app.jar")),
        )
        val json = listing.toJson("ls")
        json shouldContain "com.example"
        json shouldContain "com.example.Point"
        json shouldContain "app.jar"
    }

    // -- tree listing ---------------------------------------------------------

    @Test
    fun `tree nests segments with two-space indent`() {
        val counts = mapOf(
            "com" to 13,
            "com.google" to 13,
            "com.google.gson" to 12,
            "com.google.gson.annotations" to 1,
        )
        val tree = buildArtifactTree(
            artifact = "gson-2.14.0.jar",
            packageNames = listOf("com.google.gson", "com.google.gson.annotations"),
            typeCountOf = { counts.getValue(it) },
        )
        val listing = buildTreeListing("gson-*", listOf(tree), withCounts = true, nodeTotal = countNodes(tree.roots))
        val text = listing.renderText()
        text shouldContain "package tree for 'gson-*'"
        text shouldContain "gson-2.14.0.jar"
        text shouldContain "  com (13 types)"
        text shouldContain "    com.google (13 types)"
        text shouldContain "      com.google.gson.annotations (1 type)"
    }

    @Test
    fun `tree depth zero shows top segments only`() {
        val tree = buildArtifactTree(
            artifact = "app.jar",
            packageNames = listOf("com.example.api", "com.example.impl"),
            typeCountOf = { 1 },
            depth = 0,
        )
        tree.roots.map { it.packageName } shouldBe listOf("com")
        tree.roots.single().children shouldBe emptyList()
    }

    @Test
    fun `tree json carries every node`() {
        val tree = buildArtifactTree(
            artifact = "app.jar",
            packageNames = listOf("com.example"),
            typeCountOf = { 2 },
        )
        val json = buildTreeListing("app*", listOf(tree), withCounts = true, nodeTotal = 2).toJson("tree")
        json shouldContain "app.jar"
        json shouldContain "com.example"
    }
}
