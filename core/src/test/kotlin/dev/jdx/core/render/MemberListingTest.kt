package dev.jdx.core.render

import dev.jdx.core.model.Access
import dev.jdx.core.model.AccessFlag
import dev.jdx.core.model.Origin
import dev.jdx.core.model.Provenance
import dev.jdx.core.model.Warning
import dev.jdx.core.model.WarningCode
import dev.jdx.core.model.typeNameFromBinaryName
import dev.jdx.core.resolve.MemberResolver
import dev.jdx.core.resolve.mapLookup
import dev.jdx.core.resolve.publicField
import dev.jdx.core.resolve.publicMethod
import dev.jdx.core.resolve.testClass
import dev.jdx.core.resolve.testClassType
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

/**
 * The member listing: one result model, two renderers (T-010, D-007).
 *
 * Text groups rows under `kind declared on|inherited from <type>` headers with
 * fully-qualified signature lines; JSON carries the same rows inside the shared
 * envelope plus machine-readable canonical refs.
 */
class MemberListingTest {

    private val point = testClass(
        binary = "com.example.Point",
        superclass = "java.lang.Object",
        fields = listOf(publicField("y", "I"), publicField("x", "I")),
        methods = listOf(
            publicMethod("<init>", "(II)V"),
            publicMethod("getX", "()I"),
            publicMethod("move", "(II)V"),
        ),
    )
    private val objectStub = testClass(binary = "java.lang.Object")

    private fun listingOf(
        options: MemberListingOptions = MemberListingOptions(),
    ) = buildMemberListing(
        target = point,
        resolved = MemberResolver.resolve(point, mapLookup(point, objectStub)),
        provenance = listOf(Provenance(artifact = "app.jar", origin = Origin.BYTECODE)),
        options = options,
    )

    @Test
    fun `text groups by kind and declaring type with sorted rows`() {
        listingOf().renderText() shouldBe """
            members of com.example.Point
            constructors declared on com.example.Point:
              constructor public Point(int arg0, int arg1)
            methods declared on com.example.Point:
              method public int getX()
              method public void move(int arg0, int arg1)
            fields declared on com.example.Point:
              field public int x
              field public int y
            source: app.jar (bytecode)
        """.trimIndent()
    }

    @Test
    fun `inherited members group under their declaring type`() {
        val shape = testClass(
            binary = "com.example.Shape",
            superclass = "java.lang.Object",
            methods = listOf(publicMethod("draw", "()V")),
        )
        val circle = testClass(binary = "com.example.Circle", superclass = "com.example.Shape")
        val listing = buildMemberListing(
            target = circle,
            resolved = MemberResolver.resolve(circle, mapLookup(circle, shape, objectStub)),
            provenance = emptyList(),
        )
        listing.renderText() shouldBe """
            members of com.example.Circle
            methods inherited from com.example.Shape:
              method public void draw()
            source: no provenance recorded
        """.trimIndent()
    }

    @Test
    fun `declared-only mode serves outline with no inherited groups`() {
        val shape = testClass(
            binary = "com.example.Shape",
            superclass = "java.lang.Object",
            methods = listOf(publicMethod("draw", "()V")),
        )
        val circle = testClass(
            binary = "com.example.Circle",
            superclass = "com.example.Shape",
            methods = listOf(publicMethod("radius", "()D")),
        )
        val listing = buildMemberListing(
            target = circle,
            resolved = MemberResolver.resolve(circle, mapLookup(circle, shape, objectStub)),
            provenance = emptyList(),
            options = MemberListingOptions(declaredOnly = true),
        )
        listing.renderText() shouldBe """
            members of com.example.Circle
            methods declared on com.example.Circle:
              method public double radius()
            source: no provenance recorded
        """.trimIndent()
    }

    @Test
    fun `object members collapse to one summary line by default`() {
        val objectWithMembers = testClass(
            binary = "java.lang.Object",
            methods = listOf(publicMethod("toString", "()Ljava/lang/String;")),
        )
        val listing = buildMemberListing(
            target = point,
            resolved = MemberResolver.resolve(point, mapLookup(point, objectWithMembers)),
            provenance = emptyList(),
        )
        listing.renderText() shouldContain "+ 1 from java.lang.Object (--from java.lang.Object to expand)"
        listing.renderText() shouldNotContain "toString"
    }

    @Test
    fun `object collapse can be disabled`() {
        val objectWithMembers = testClass(
            binary = "java.lang.Object",
            methods = listOf(publicMethod("toString", "()Ljava/lang/String;")),
        )
        val listing = buildMemberListing(
            target = point,
            resolved = MemberResolver.resolve(point, mapLookup(point, objectWithMembers)),
            provenance = emptyList(),
            options = MemberListingOptions(collapseObjectMembers = false),
        )
        listing.renderText() shouldContain "methods inherited from java.lang.Object:"
        listing.renderText() shouldContain "public java.lang.String toString()"
    }

    @Test
    fun `truncation footer names shown total and the revealing flag`() {
        val many = testClass(
            binary = "com.example.Many",
            superclass = "java.lang.Object",
            methods = (1..60).map { publicMethod("m$it", "()V") },
        )
        val listing = buildMemberListing(
            target = many,
            resolved = MemberResolver.resolve(many, mapLookup(many, objectStub)),
            provenance = emptyList(),
            options = MemberListingOptions(maxMembers = 50),
        )
        val text = listing.renderText()
        text shouldContain "50 of 60 members shown (--limit 60 to see more)"
        text.lines().count { it.startsWith("  method ") } shouldBe 50
    }

    @Test
    fun `missing supertypes become labelled warnings not silent holes`() {
        val orphan = testClass(binary = "com.example.Orphan", superclass = "com.example.Gone")
        val listing = buildMemberListing(
            target = orphan,
            resolved = MemberResolver.resolve(orphan, mapLookup(orphan)),
            provenance = emptyList(),
        )
        listing.warnings.map { it.code } shouldBe listOf(WarningCode.UNRESOLVED_SUPERTYPE)
        listing.renderText() shouldContain "warning UNRESOLVED_SUPERTYPE"
    }

    @Test
    fun `text carries no ANSI escapes by default`() {
        listingOf().renderText() shouldNotContain "\u001B"
    }

    @Test
    fun `color output strips back to the plain text`() {
        val plain = listingOf().renderText(color = false)
        val colored = listingOf().renderText(color = true)
        colored shouldContain "\u001B"
        colored.stripAnsi() shouldBe plain
    }

    @Test
    fun `json carries every text row plus canonical refs`() {
        val listing = listingOf()
        val json = listing.toJson(command = "members")
        val text = listing.renderText()
        // Every signature line in text appears in JSON: no text-only information (D-007).
        for (row in listing.groups.flatMap { it.rows }) {
            json shouldContain row.signature
            json shouldContain row.canonicalRef
        }
        json shouldContain "\"jdx\":1"
        json shouldContain "\"command\":\"members\""
        json shouldContain "\"query\":\"com.example.Point\""
        json shouldContain "\"ok\":true"
        text shouldNotContain "com.example.Point#move"
    }

    @Test
    fun `json truncation block mirrors the text footer`() {
        val many = testClass(
            binary = "com.example.Many",
            superclass = "java.lang.Object",
            methods = (1..60).map { publicMethod("m$it", "()V") },
        )
        val listing = buildMemberListing(
            target = many,
            resolved = MemberResolver.resolve(many, mapLookup(many, objectStub)),
            provenance = emptyList(),
            options = MemberListingOptions(maxMembers = 50),
        )
        listing.toJson(command = "members") shouldContain
            "\"truncated\":{\"shown\":50,\"total\":60,\"hint\":\"--limit 60\"}"
    }

    @Test
    fun `json warnings and provenance are structural`() {
        val orphan = testClass(binary = "com.example.Orphan", superclass = "com.example.Gone")
        val listing = buildMemberListing(
            target = orphan,
            resolved = MemberResolver.resolve(orphan, mapLookup(orphan)),
            provenance = listOf(Provenance(artifact = "app.jar", origin = Origin.BYTECODE)),
        )
        val json = listing.toJson(command = "members")
        json shouldContain "\"code\":\"UNRESOLVED_SUPERTYPE\""
        json shouldContain "\"artifact\":\"app.jar\""
        json shouldContain "\"origin\":\"bytecode\""
    }

    @Test
    fun `overridden bridge siblings disambiguate refs by return type`() {
        val holder = testClass(
            binary = "com.example.Holder",
            superclass = "java.lang.Object",
            methods = listOf(
                publicMethod("get", "()Ljava/lang/Object;"),
                publicMethod("get", "()Ljava/lang/String;").copy(
                    access = Access.of(AccessFlag.PUBLIC, AccessFlag.BRIDGE, AccessFlag.SYNTHETIC),
                ),
            ),
        )
        val listing = buildMemberListing(
            target = holder,
            resolved = MemberResolver.resolve(
                holder,
                mapLookup(holder, objectStub),
                dev.jdx.core.resolve.MemberResolutionOptions(includeSynthetic = true),
            ),
            provenance = emptyList(),
        )
        val refs = listing.groups.flatMap { it.rows }.map { it.canonicalRef }.sorted()
        refs shouldBe listOf(
            "com.example.Holder#get():java.lang.Object",
            "com.example.Holder#get():java.lang.String",
        )
    }

    @Test
    fun `deterministic bytes across runs`() {
        listingOf().renderText() shouldBe listingOf().renderText()
        listingOf().toJson(command = "members") shouldBe listingOf().toJson(command = "members")
    }

    @Test
    fun `private members render with their visibility intact`() {
        val holder = testClass(
            binary = "com.example.Holder",
            superclass = "java.lang.Object",
            fields = listOf(
                publicField("open", "I"),
                publicField("shut", "I").copy(access = Access.of(AccessFlag.PRIVATE)),
            ),
        )
        val text = buildMemberListing(
            target = holder,
            resolved = MemberResolver.resolve(holder, mapLookup(holder, objectStub)),
            provenance = emptyList(),
        ).renderText()
        text shouldContain "field public int open"
        text shouldContain "field private int shut"
    }

    @Test
    fun `query type helper accepts a binary name`() {
        testClassType("com.example.Point").binaryName shouldBe "com.example.Point"
        typeNameFromBinaryName("java.lang.Object").binaryName shouldBe "java.lang.Object"
    }

    @Test
    fun `warning model carries an optional subject`() {
        Warning(
            code = WarningCode.DUPLICATE_FQN,
            message = "same FQN in two artifacts",
            subject = "com.example.Point",
        ).toJson() shouldBe
            "{\"code\":\"DUPLICATE_FQN\",\"message\":\"same FQN in two artifacts\"," +
            "\"subject\":\"com.example.Point\"}"
    }

    // -- T-060: listing killers -------------------------------------------------
    //
    // Sibling disambiguation, counts, ordering and truncation edges were asserted
    // loosely or not at all; PIT's mutants on those branches survived.

    @Test
    fun `bridge siblings disambiguate refs with return types`() {
        // Same name and erased parameters, different returns: both refs carry
        // `:return`. Kills the sibling-key and `> 1` mutants.
        val holder = testClass(
            binary = "com.example.Holder",
            superclass = "java.lang.Object",
            methods = listOf(
                publicMethod("get", "()Ljava/lang/Object;"),
                publicMethod("get", "()Ljava/lang/String;"),
            ),
        )
        val listing = buildMemberListing(
            target = holder,
            resolved = MemberResolver.resolve(holder, mapLookup(holder, objectStub)),
            provenance = emptyList(),
        )
        val refs = listing.groups.flatMap { it.rows }.map { it.canonicalRef }.sorted()
        refs shouldBe listOf(
            "com.example.Holder#get():java.lang.Object",
            "com.example.Holder#get():java.lang.String",
        )
    }

    @Test
    fun `counts name every kind exactly`() {
        listingOf().counts shouldBe MemberCounts(constructors = 1, methods = 2, fields = 2)
    }

    @Test
    fun `rows sort by signature text inside a group`() {
        val holder = testClass(
            binary = "com.example.Holder",
            superclass = "java.lang.Object",
            methods = listOf(
                publicMethod("zebra", "()V"),
                publicMethod("apple", "()V"),
            ),
        )
        val text = buildMemberListing(
            target = holder,
            resolved = MemberResolver.resolve(holder, mapLookup(holder, objectStub)),
            provenance = emptyList(),
        ).renderText()
        val apple = text.indexOf("apple")
        val zebra = text.indexOf("zebra")
        (apple < zebra) shouldBe true
    }

    @Test
    fun `an exact-fit limit means no truncation`() {
        // `total <= limit`: five rows with maxMembers 5 keeps everything.
        val listing = listingOf(options = MemberListingOptions(maxMembers = 5))
        listing.truncation shouldBe null
        listing.groups.sumOf { it.rows.size } shouldBe 5
    }

    @Test
    fun `a limit ending exactly on a group drops the rest`() {
        // One constructor plus two methods fill maxMembers 3; fields are cut.
        val listing = listingOf(options = MemberListingOptions(maxMembers = 3))
        listing.truncation shouldBe Truncation(shown = 3, total = 5, hint = "--limit 5")
        listing.groups.map { it.kind } shouldBe listOf(MemberKind.CONSTRUCTOR, MemberKind.METHOD)
    }

    @Test
    fun `an overridden method records its overridden type in json`() {
        val shape = testClass(
            binary = "com.example.Shape",
            superclass = "java.lang.Object",
            methods = listOf(publicMethod("draw", "()V")),
        )
        val circle = testClass(
            binary = "com.example.Circle",
            superclass = "com.example.Shape",
            methods = listOf(publicMethod("draw", "()V")),
        )
        val listing = buildMemberListing(
            target = circle,
            resolved = MemberResolver.resolve(circle, mapLookup(circle, shape, objectStub)),
            provenance = emptyList(),
        )
        val json = listing.toJson(command = "members")
        json shouldContain "\"overridden\":[\"com.example.Shape\"]"
    }

    @Test
    fun `a deprecated row marks itself in json only`() {
        val holder = testClass(
            binary = "com.example.Holder",
            superclass = "java.lang.Object",
            methods = listOf(publicMethod("old", "()V").copy(deprecated = true)),
        )
        val listing = buildMemberListing(
            target = holder,
            resolved = MemberResolver.resolve(holder, mapLookup(holder, objectStub)),
            provenance = emptyList(),
        )
        val row = listing.groups.single().rows.single()
        row.deprecated shouldBe true
        row.toJson() shouldContain "\"deprecated\":true"
        listing.renderText() shouldNotContain "deprecated"
    }

    @Test
    fun `collapsed object members become one summary line`() {
        val withObject = testClass(
            binary = "java.lang.Object",
            methods = listOf(publicMethod("toString", "()Ljava/lang/String;")),
        )
        val listing = buildMemberListing(
            target = point,
            resolved = MemberResolver.resolve(point, mapLookup(point, withObject)),
            provenance = emptyList(),
        )
        listing.objectSummary shouldBe ObjectSummary(count = 1)
        listing.renderText() shouldContain "+ 1 from java.lang.Object (--from java.lang.Object to expand)"
    }

    @Test
    fun `provenance prints file and line ranges`() {
        val listing = buildMemberListing(
            target = point,
            resolved = MemberResolver.resolve(point, mapLookup(point, objectStub)),
            provenance = listOf(
                Provenance(
                    artifact = "app-sources.jar",
                    origin = Origin.SOURCES,
                    file = "com/example/Point.java",
                    lineRange = 3..42,
                ),
                Provenance(artifact = "app.jar", origin = Origin.BYTECODE),
            ),
        )
        val text = listing.renderText()
        text shouldContain "source: app-sources.jar (sources, com/example/Point.java:3-42)"
        text shouldContain "source: app.jar (bytecode)"
        listing.provenance.size shouldBe 2
        val json = listing.toJson(command = "members")
        json shouldContain "\"artifact\":\"app-sources.jar\",\"origin\":\"sources\"," +
            "\"file\":\"com/example/Point.java\",\"lines\":[3,42]"
        json shouldContain "\"artifact\":\"app.jar\",\"origin\":\"bytecode\""
    }

    @Test
    fun `a line range without a file prints no lines block`() {
        // The `file != null && range != null` guard: lines need a file.
        Provenance(
            artifact = "app.jar",
            origin = Origin.BYTECODE,
            lineRange = 3..42,
        ).toJson() shouldBe "{\"artifact\":\"app.jar\",\"origin\":\"bytecode\"}"
    }

    @Test
    fun `a hidden field records the hider in json`() {
        // JLS §8.3: the nearer declaration wins; the hidden type is recorded.
        val base = testClass(
            binary = "com.example.Base",
            superclass = "java.lang.Object",
            fields = listOf(publicField("x", "I")),
        )
        val child = testClass(
            binary = "com.example.Child",
            superclass = "com.example.Base",
            fields = listOf(publicField("x", "I")),
        )
        val listing = buildMemberListing(
            target = child,
            resolved = MemberResolver.resolve(child, mapLookup(child, base, objectStub)),
            provenance = emptyList(),
        )
        val row = listing.groups.single().rows.single()
        row.hiddenTypes.map { it.binaryName } shouldBe listOf("com.example.Base")
        row.toJson() shouldContain "\"hidden\":[\"com.example.Base\"]"
    }

    @Test
    fun `a row round-trips its model exactly in json`() {
        val holder = testClass(
            binary = "com.example.Holder",
            superclass = "java.lang.Object",
            methods = listOf(publicMethod("old", "()V").copy(deprecated = true)),
        )
        val listing = buildMemberListing(
            target = holder,
            resolved = MemberResolver.resolve(holder, mapLookup(holder, objectStub)),
            provenance = emptyList(),
        )
        val row = listing.groups.single().rows.single()
        row.kind shouldBe MemberKind.METHOD
        row.signature shouldBe "public void old()"
        row.depth shouldBe 0
        row.declaringType.binaryName shouldBe "com.example.Holder"
        row.toJson() shouldBe
            "{\"ref\":\"com.example.Holder#old()\",\"kind\":\"method\"," +
            "\"declaring\":\"com.example.Holder\",\"signature\":\"public void old()\"," +
            "\"depth\":0,\"deprecated\":true}"
    }
}
