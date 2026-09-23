package dev.jdx.core.render

import dev.jdx.core.gen.JDX_PROPERTY_ITERATIONS
import dev.jdx.core.gen.arbClassGraph
import dev.jdx.core.gen.arbSyntheticToggle
import dev.jdx.core.gen.graphLookup
import dev.jdx.core.gen.graphTarget
import dev.jdx.core.model.Origin
import dev.jdx.core.model.Provenance
import dev.jdx.core.model.Warning
import dev.jdx.core.model.WarningCode
import dev.jdx.core.resolve.MemberResolutionOptions
import dev.jdx.core.resolve.MemberResolver
import dev.jdx.core.resolve.mapLookup
import dev.jdx.core.resolve.publicField
import dev.jdx.core.resolve.publicMethod
import dev.jdx.core.resolve.testClass
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/**
 * The minimal listing behind `members --brief` / `outline --brief` (T-047,
 * PROPOSAL.md §3.1): signatures only, no group headers, no ` — doc`
 * suffixes, no provenance block. Warnings, the collapsed-`Object` summary
 * and the truncation footer stay — a brief answer must still be honest (G6).
 */
class MemberListingBriefTest {

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
    fun `brief prints bare rows with no headers and no provenance`() {
        listingOf().renderBriefText() shouldBe """
            members of com.example.Point
              constructor public Point(int arg0, int arg1)
              method public int getX()
              method public void move(int arg0, int arg1)
              field public int x
              field public int y
        """.trimIndent()
    }

    @Test
    fun `brief keeps warnings footers and the object summary`() {
        val warning = Warning(
            code = WarningCode.UNRESOLVED_SUPERTYPE,
            message = "supertype q.Missing is not in the workspace — members inherited from it are missing",
            subject = "com.example.Point",
        )
        val listing = listingOf().copy(
            warnings = listOf(warning),
            truncation = Truncation(shown = 5, total = 9, hint = "--limit 9"),
            objectSummary = ObjectSummary(count = 11),
        )
        val brief = listing.renderBriefText()
        brief shouldContain "members of com.example.Point"
        brief shouldContain warning.message
        brief shouldContain "5 of 9 members shown (--limit 9 to see more)"
        brief shouldContain "+ 11 from java.lang.Object (--from java.lang.Object to expand)"
        brief shouldNotContain "declared on"
        brief shouldNotContain "source:"
    }

    @Test
    fun `brief is deterministic`() {
        listingOf().renderBriefText() shouldBe listingOf().renderBriefText()
    }

    @Test
    fun `brief drops doc suffixes even when rows carry them`() {
        // The CLI rejects --brief --with-doc, so this path is defensive: the
        // renderer honours "signatures only" whatever the rows carry.
        val first = listingOf().groups.first()
        val docRow = first.rows.first().copy(doc = "Does things.")
        val groups = listOf(first.copy(rows = listOf(docRow) + first.rows.drop(1))) +
            listingOf().groups.drop(1)
        val brief = listingOf().copy(groups = groups).renderBriefText()
        brief shouldNotContain " — "
        brief shouldContain docRow.briefLine()
    }

    private fun generatedListingFor(
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
    fun `brief rows are a whole-row subset of the full text`() = runBlocking<Unit> {
        checkAll(JDX_PROPERTY_ITERATIONS, arbClassGraph(), arbSyntheticToggle(), Arb.int(0..10)) { graph, synthetic, max ->
            val listing = generatedListingFor(graph, synthetic, max)
            val brief = listing.renderBriefText()
            val full = listing.renderText()
            // Every member row survives, byte-identical; group headers do not.
            for (row in listing.groups.flatMap { it.rows }) {
                brief shouldContain row.textLine()
            }
            for (group in listing.groups) {
                if (group.rows.isNotEmpty()) brief shouldNotContain group.headerLine()
            }
            // Provenance is out; warnings and footers stay.
            brief shouldNotContain "source:"
            for (warning in listing.warnings) brief shouldContain warning.message
            listing.truncation?.let { brief shouldContain it.hint }
            listing.objectSummary?.let { brief shouldContain it.textLine() }
            // Brief is never longer than full.
            (brief.lines().size <= full.lines().size) shouldBe true
        }
    }
}
