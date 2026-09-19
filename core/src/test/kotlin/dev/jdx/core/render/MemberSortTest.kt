package dev.jdx.core.render

import dev.jdx.core.model.Origin
import dev.jdx.core.model.Provenance
import dev.jdx.core.resolve.MemberResolver
import dev.jdx.core.resolve.mapLookup
import dev.jdx.core.resolve.publicField
import dev.jdx.core.resolve.publicMethod
import dev.jdx.core.resolve.testClass
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/**
 * Member sort orders (T-062, PROPOSAL.md §7.1).
 *
 * Three layouts over one result model: `kind` (the T-010 default, preserved
 * byte-identically), `name` (flat name-first across kinds and groups) and
 * `declaring` (alphabetical by declaring type). Text and JSON share the row
 * order in every layout (D-007); truncation keeps the order's prefix.
 */
class MemberSortTest {

    private val objectStub = testClass(binary = "java.lang.Object")

    private fun listingOf(
        target: dev.jdx.core.model.ClassInfo,
        lookup: (dev.jdx.core.model.TypeName) -> dev.jdx.core.model.ClassInfo?,
        sort: MemberSort,
        maxMembers: Int = DEFAULT_MEMBER_LIMIT,
    ): MemberListing = buildMemberListing(
        target = target,
        resolved = MemberResolver.resolve(target, lookup),
        provenance = listOf(Provenance(artifact = "app.jar", origin = Origin.BYTECODE)),
        options = MemberListingOptions(maxMembers = maxMembers, sort = sort),
    )

    private fun flatNames(listing: MemberListing): List<String> =
        listing.groups.flatMap { it.rows }.map { it.memberName }

    @Test
    fun `kind is the default and groups by linearisation then kind`() {
        MemberListingOptions().sort shouldBe MemberSort.KIND
        val target = testClass(
            binary = "com.example.Point",
            superclass = "java.lang.Object",
            fields = listOf(publicField("y", "I"), publicField("x", "I")),
            methods = listOf(publicMethod("move", "(II)V"), publicMethod("getX", "()I")),
        )
        val listing = listingOf(target, mapLookup(target, objectStub), MemberSort.KIND)
        listing.groups.map { it.kind } shouldBe listOf(MemberKind.METHOD, MemberKind.FIELD)
        flatNames(listing) shouldBe listOf("getX", "move", "x", "y")
    }

    @Test
    fun `name sorts flat across kinds with constructors by raw init name`() {
        val target = testClass(
            binary = "com.example.Point",
            superclass = "java.lang.Object",
            fields = listOf(publicField("y", "I"), publicField("mango", "I")),
            methods = listOf(
                publicMethod("<init>", "(II)V"),
                publicMethod("zebra", "()V"),
                publicMethod("apple", "()V"),
            ),
        )
        val listing = listingOf(target, mapLookup(target, objectStub), MemberSort.NAME)
        // `<` (60) sorts before letters: constructors lead, then apple, mango, y, zebra.
        flatNames(listing) shouldBe listOf("<init>", "apple", "mango", "y", "zebra")
        // Every row still carries its kind header; flattening is the global order.
        val flattened = listing.groups.flatMap { it.rows }.map { it.canonicalRef }
        val globallySorted = listing.groups.flatMap { it.rows }.sortedWith(
            compareBy(
                { it.memberName },
                { it.signature },
                { it.canonicalRef },
                { it.declaringType.binaryName },
                { it.depth },
            ),
        ).map { it.canonicalRef }
        flattened shouldBe globallySorted
    }

    @Test
    fun `declaring sorts groups alphabetically not by linearisation`() {
        // Linearisation: Child, Zebra, Alpha. Alphabetical: Alpha, Child, Zebra.
        val alpha = testClass(
            binary = "com.example.Alpha",
            superclass = "java.lang.Object",
            methods = listOf(publicMethod("alphaMethod", "()V")),
        )
        val zebra = testClass(
            binary = "com.example.Zebra",
            superclass = "com.example.Alpha",
            methods = listOf(publicMethod("zebraMethod", "()V")),
        )
        val child = testClass(
            binary = "com.example.Child",
            superclass = "com.example.Zebra",
            methods = listOf(publicMethod("childMethod", "()V")),
        )
        val lookup = mapLookup(child, zebra, alpha, objectStub)
        val kindListing = listingOf(child, lookup, MemberSort.KIND)
        kindListing.groups.map { it.declaringType.binaryName } shouldBe
            listOf("com.example.Child", "com.example.Zebra", "com.example.Alpha")
        val declaringListing = listingOf(child, lookup, MemberSort.DECLARING)
        declaringListing.groups.map { it.declaringType.binaryName } shouldBe
            listOf("com.example.Alpha", "com.example.Child", "com.example.Zebra")
        // Rows inside each group keep the kind layout (signature, then ref).
        for (group in declaringListing.groups) {
            val rows = group.rows
            rows shouldBe rows.sortedWith(compareBy({ it.signature }, { it.canonicalRef }))
        }
    }

    @Test
    fun `every sort renders the same entity set in text and json`() {
        val target = testClass(
            binary = "com.example.Point",
            superclass = "java.lang.Object",
            fields = listOf(publicField("y", "I")),
            methods = listOf(publicMethod("move", "(II)V"), publicMethod("getX", "()I")),
        )
        for (sort in MemberSort.entries) {
            val listing = listingOf(target, mapLookup(target, objectStub), sort)
            val text = listing.renderText()
            val json = listing.toJson(command = "members")
            for (row in listing.groups.flatMap { it.rows }) {
                json shouldContain row.signature
                json shouldContain row.canonicalRef
                text shouldContain row.signature
            }
            // Same rows in every order: sorting reorders, never hides.
            listing.groups.sumOf { it.rows.size } shouldBe 3
        }
    }

    @Test
    fun `truncation keeps the order prefix in every sort`() {
        val many = testClass(
            binary = "com.example.Many",
            superclass = "java.lang.Object",
            methods = (1..60).map { publicMethod("m$it", "()V") },
        )
        for (sort in MemberSort.entries) {
            val full = listingOf(many, mapLookup(many, objectStub), sort, maxMembers = Int.MAX_VALUE)
            val cut = listingOf(many, mapLookup(many, objectStub), sort, maxMembers = 5)
            val fullRefs = full.groups.flatMap { it.rows }.map { it.canonicalRef }
            val cutRefs = cut.groups.flatMap { it.rows }.map { it.canonicalRef }
            fullRefs.take(cutRefs.size) shouldBe cutRefs
            cut.truncation?.shown shouldBe 5
        }
    }

    @Test
    fun `fromFlag parses every choice case-insensitively and rejects the rest`() {
        MemberSort.fromFlag("kind") shouldBe MemberSort.KIND
        MemberSort.fromFlag("NAME") shouldBe MemberSort.NAME
        MemberSort.fromFlag("Declaring") shouldBe MemberSort.DECLARING
        MemberSort.fromFlag("bogus") shouldBe null
    }
}
