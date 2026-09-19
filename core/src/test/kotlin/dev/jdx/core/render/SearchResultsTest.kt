package dev.jdx.core.render

import dev.jdx.core.model.TypeKind
import dev.jdx.core.model.Warning
import dev.jdx.core.model.WarningCode
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

    // -- T-060: search killers --------------------------------------------------
    //
    // Model getters were never read in assertions (every `getX` mutant survived),
    // truncation math was asserted loosely, and tree truncation had no core test
    // at all (only index goldens, which run in another module's JVM).

    @Test
    fun `a search hit carries kind ref artifact and package`() {
        val hit = hit(ref = "com.google.gson.Gson")
        hit.kind shouldBe "class"
        hit.ref shouldBe "com.google.gson.Gson"
        hit.artifact shouldBe "gson-2.14.0.jar"
        hit.packageName shouldBe "com.google.gson"
        hit.textLine() shouldBe "  class com.google.gson.Gson  gson-2.14.0.jar"
        hit.toJson() shouldBe
            "{\"kind\":\"class\",\"ref\":\"com.google.gson.Gson\"," +
            "\"artifact\":\"gson-2.14.0.jar\",\"package\":\"com.google.gson\"}"
    }

    @Test
    fun `search listing exposes its model`() {
        val listing = buildSearchListing(query = "Gson", hits = listOf(hit(ref = "com.google.gson.Gson")))
        listing.query shouldBe "Gson"
        listing.hits.size shouldBe 1
        listing.truncation shouldBe null
        listing.warnings shouldBe emptyList()
        listing.provenance shouldBe emptyList()
    }

    @Test
    fun `ls totals count packages and types together`() {
        // `packages.size + types.size`: the mutant subtracts.
        val listing = buildLsListing(
            query = "*",
            packages = listOf(PackageEntry("com.a", 1), PackageEntry("com.b", 1)),
            types = listOf(
                LsTypeEntry("class", "com.a.A", "app.jar"),
                LsTypeEntry("class", "com.b.B", "app.jar"),
            ),
            limit = 3,
        )
        listing.truncation?.total shouldBe 4
        listing.truncation?.shown shouldBe 3
        listing.types.size shouldBe 1
    }

    @Test
    fun `ls exact fit means no truncation`() {
        // `total <= limit`: equal must not truncate.
        val listing = buildLsListing(
            query = "*",
            packages = listOf(PackageEntry("com.a", 1)),
            types = listOf(LsTypeEntry("class", "com.a.A", "app.jar")),
            limit = 2,
        )
        listing.truncation shouldBe null
    }

    @Test
    fun `ls packages filling the limit drop every type`() {
        // `remaining <= 0`: packages take the whole budget.
        val listing = buildLsListing(
            query = "*",
            packages = listOf(
                PackageEntry("com.a", 1),
                PackageEntry("com.b", 1),
                PackageEntry("com.c", 1),
            ),
            types = listOf(LsTypeEntry("class", "com.a.A", "app.jar")),
            limit = 2,
        )
        listing.packages.size shouldBe 2
        listing.types shouldBe emptyList()
        listing.truncation?.total shouldBe 4
    }

    @Test
    fun `ls headers name the mode exactly`() {
        val packagesText = buildLsListing(
            query = "com.*",
            packages = listOf(PackageEntry("com.a", 1)),
            types = emptyList(),
        ).renderText()
        packagesText.lines().first() shouldBe "packages matching 'com.*'"

        val typesText = buildLsListing(
            query = "com.a",
            packages = emptyList(),
            types = listOf(LsTypeEntry("class", "com.a.A", "app.jar")),
        ).renderText()
        typesText.lines().first() shouldBe "types in package 'com.a'"
    }

    @Test
    fun `ls rows print exact lines`() {
        PackageEntry("com.example", 2).textLine() shouldBe "  package com.example (2 types)"
        PackageEntry("com.example", 1).textLine() shouldBe "  package com.example (1 type)"
        PackageEntry("com.example", 1).toJson() shouldBe "{\"name\":\"com.example\",\"types\":1}"
        LsTypeEntry("class", "com.example.Point", "app.jar").textLine() shouldBe
            "  class com.example.Point  app.jar"
        LsTypeEntry("class", "com.example.Point", "app.jar").toJson() shouldBe
            "{\"kind\":\"class\",\"ref\":\"com.example.Point\",\"artifact\":\"app.jar\"}"
    }

    @Test
    fun `ls listing exposes its model`() {
        val listing = buildLsListing(
            query = "*",
            packages = listOf(PackageEntry("com.a", 1)),
            types = listOf(LsTypeEntry("class", "com.a.A", "app.jar")),
        )
        listing.query shouldBe "*"
        listing.packages.single().name shouldBe "com.a"
        listing.types.single().ref shouldBe "com.a.A"
        listing.truncation shouldBe null
        listing.warnings shouldBe emptyList()
    }

    @Test
    fun `tree nodes print counts singular and plural`() {
        TreeNode("gson", "com.google.gson", 2).textLines("", withCounts = true) shouldBe
            listOf("com.google.gson (2 types)")
        TreeNode("annotations", "com.google.gson.annotations", 1).textLines("", withCounts = true) shouldBe
            listOf("com.google.gson.annotations (1 type)")
        TreeNode("gson", "com.google.gson", 2).textLines("", withCounts = false) shouldBe
            listOf("com.google.gson")
    }

    @Test
    fun `tree node json honours the counts flag`() {
        val node = TreeNode("gson", "com.google.gson", 2)
        node.toJson(withCounts = true) shouldBe
            "{\"package\":\"com.google.gson\",\"types\":2,\"children\":[]}"
        node.toJson(withCounts = false) shouldBe
            "{\"package\":\"com.google.gson\",\"children\":[]}"
        node.segment shouldBe "gson"
        node.typeCount shouldBe 2
    }

    @Test
    fun `tree listing exposes its model`() {
        val tree = buildArtifactTree("app.jar", listOf("com.example"), typeCountOf = { 2 })
        val listing = buildTreeListing("app*", listOf(tree), withCounts = true, nodeTotal = 2)
        listing.query shouldBe "app*"
        listing.artifacts.single().artifact shouldBe "app.jar"
        listing.withCounts shouldBe true
        listing.truncation shouldBe null
        listing.warnings shouldBe emptyList()
    }

    @Test
    fun `tree truncation keeps breadth-first nodes with the total`() {
        // Two artifacts share one node budget: the first keeps its roots, the
        // second is cut, and the footer names the whole forest.
        val first = buildArtifactTree("a.jar", listOf("com.a", "com.b"), typeCountOf = { 1 })
        val second = buildArtifactTree("b.jar", listOf("com.c", "com.d"), typeCountOf = { 1 })
        val listing = buildTreeListing("*", listOf(first, second), withCounts = false, nodeTotal = 6, limit = 4)
        listing.truncation?.total shouldBe 6
        listing.truncation?.shown shouldBe 4
        countNodes(listing.artifacts[0].roots) + countNodes(listing.artifacts[1].roots) shouldBe 4
        listing.renderText() shouldContain "4 of 6 package nodes shown (--limit 6 to see more)"
    }

    @Test
    fun `countNodes counts the whole forest`() {
        val tree = buildArtifactTree(
            artifact = "app.jar",
            packageNames = listOf("com.example.api", "com.example.impl"),
            typeCountOf = { 1 },
        )
        // com, com.example, com.example.api, com.example.impl.
        countNodes(tree.roots) shouldBe 4
        countNodes(emptyList()) shouldBe 0
    }

    @Test
    fun `search text bolds headers in color mode`() {
        // Only headers bold: hit lines pass through, warning lines bold.
        // (Footers here say "results shown", which the header regex does not
        // cover — only `members shown` footers and `warning` lines bold.)
        val listing = buildSearchListing(
            query = "Gson",
            hits = listOf(hit(ref = "com.google.gson.Gson")),
            warnings = listOf(
                Warning(
                    WarningCode.DUPLICATE_FQN,
                    "same FQN in two artifacts",
                    "com.google.gson.Gson",
                ),
            ),
        )
        val colored = listing.renderText(color = true)
        colored shouldContain "\u001B[1mwarning DUPLICATE_FQN"
        colored.stripAnsi() shouldBe listing.renderText(color = false)
    }
}
