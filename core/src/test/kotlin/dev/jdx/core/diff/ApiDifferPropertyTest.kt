package dev.jdx.core.diff

import dev.jdx.core.gen.JDX_PROPERTY_ITERATIONS
import dev.jdx.core.gen.arbClassMutation
import dev.jdx.core.gen.arbHostileName
import dev.jdx.core.gen.arbSnapshot
import dev.jdx.core.diff.ApiSurface
import dev.jdx.core.model.Access
import dev.jdx.core.model.AnnotationInfo
import dev.jdx.core.model.ClassInfo
import dev.jdx.core.model.FieldInfo
import dev.jdx.core.model.JvmDescriptor
import dev.jdx.core.model.JvmPrimitive
import dev.jdx.core.model.MethodInfo
import dev.jdx.core.model.TypeKind
import dev.jdx.core.model.typeNameFromBinaryName
import dev.jdx.core.model.TypeName

import dev.jdx.core.render.DiffReport
import dev.jdx.core.render.Truncation
import dev.jdx.core.render.buildDiffReport
import dev.jdx.core.render.wireName
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.of
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Generative invariants for the diff engine and its report (issue #23,
 * docs/TESTING.md §4 — the standing bar demands a generating family, not only the
 * per-rule examples in [ApiDifferTest]).
 *
 * The properties are the ones a hand-written test cannot reach: self-identity over
 * thousands of generated artifact pairs, byte-determinism, the truncation law, and
 * text⊆JSON over a report nobody wrote by hand.
 */
@Tag("integration")
class ApiDifferPropertyTest {

    private fun reportFor(
        old: ApiSnapshot,
        new: ApiSnapshot,
        filter: SeverityFilter = SeverityFilter.ALL,
        limit: Int = Int.MAX_VALUE,
        failOn: FailOn = FailOn.NONE,
    ): DiffReport = buildDiffReport(
        diff = ApiDiffer.diff(old, new),
        severityFilter = filter,
        failOn = failOn,
        maxFindings = limit,
    )

    @Test
    fun `an artifact never differs from itself`() = runBlocking<Unit> {
        checkAll(JDX_PROPERTY_ITERATIONS, arbSnapshot("a.jar")) { snapshot ->
            val diff = ApiDiffer.diff(snapshot, snapshot)
            diff.findings shouldBe emptyList()
            diff.identical shouldBe true
            DiffCounts.of(diff).total shouldBe 0
        }
    }

    @Test
    fun `the ordered findings are a total order, so sorting twice changes nothing`() = runBlocking<Unit> {
        checkAll(JDX_PROPERTY_ITERATIONS, arbSnapshot("a.jar"), arbSnapshot("b.jar")) { old, new ->
            val first = ApiDiffer.diff(old, new).ordered
            // Sorting is idempotent and total: sorting a sorted list changes nothing, and
            // every consecutive pair is non-decreasing.
            first.sortedWith(DIFF_FINDING_ORDER) shouldBe first
            first.zipWithNext().all { (a, b) -> DIFF_FINDING_ORDER.compare(a, b) <= 0 } shouldBe true
        }
    }

    @Test
    fun `every finding names a rule, a type and a ref, and its severity is the rule's`() =
        runBlocking<Unit> {
            checkAll(JDX_PROPERTY_ITERATIONS, arbSnapshot("a.jar"), arbSnapshot("b.jar")) { old, new ->
                for (finding in ApiDiffer.diff(old, new).findings) {
                    finding.severity shouldBe finding.rule.severity
                    finding.ref.isNotEmpty() shouldBe true
                    finding.type.isNotEmpty() shouldBe true
                    finding.type shouldBe finding.ref.substringBefore("#")
                }
            }
        }

    @Test
    fun `every finding names a type that one of the two artifacts really has`() = runBlocking<Unit> {
        checkAll(JDX_PROPERTY_ITERATIONS, arbSnapshot("a.jar"), arbSnapshot("b.jar")) { old, new ->
            val known = old.types.keys + new.types.keys
            for (finding in ApiDiffer.diff(old, new).findings) {
                (finding.type in known) shouldBe true
            }
        }
    }

    @Test
    fun `a structural change is always reported, and a no-op mutation never is`() = runBlocking<Unit> {
        checkAll(JDX_PROPERTY_ITERATIONS, arbClassMutation()) { mutation ->
            val before = mutation.before
            val after = mutation.after
            val old = ApiSnapshot.of("a.jar", listOf(before), ApiSurface.ALL, includeSynthetic = true)
            val new = ApiSnapshot.of("b.jar", listOf(after), ApiSurface.ALL, includeSynthetic = true)
            val findings = ApiDiffer.diff(old, new).findings
            if (before == after) {
                findings shouldBe emptyList()
                return@checkAll
            }
            // A *structural* change — a type appeared or vanished, a kind flipped, or a
            // member key appeared or vanished — must never be silent. A difference that
            // is none of those (an access bit the diff deliberately ignores, a generic
            // signature, a parameter name) is not required to produce a finding, and
            // inventing one for it would be noise.
            val structural = old.types.keys != new.types.keys ||
                old.types.any { (name, type) ->
                    val other = new.types[name]
                    other != null && (type.kind != other.kind || type.members.keys != other.members.keys)
                }
            if (structural) {
                (findings.isNotEmpty()) shouldBe true
            }
        }
    }

    @Test
    fun `the report is byte-identical across runs, in text and in JSON`() = runBlocking<Unit> {
        checkAll(JDX_PROPERTY_ITERATIONS, arbSnapshot("a.jar"), arbSnapshot("b.jar"), arbFilter(), arbLimit()) { old, new, filter, limit ->
            val first = reportFor(old, new, filter, limit)
            val second = reportFor(old, new, filter, limit)
            first.renderText() shouldBe second.renderText()
            first.toJson(command = "diff") shouldBe second.toJson(command = "diff")
        }
    }

    @Test
    fun `the counts always describe the whole comparison, however the report is filtered`() =
        runBlocking<Unit> {
            checkAll(JDX_PROPERTY_ITERATIONS, arbSnapshot("a.jar"), arbSnapshot("b.jar"), arbFilter(), arbLimit()) { old, new, filter, limit ->
                val full = reportFor(old, new)
                val cut = reportFor(old, new, filter, limit)
                cut.counts shouldBe full.counts
                cut.identical shouldBe full.identical
                cut.identical shouldBe (full.counts.total == 0)
            }
        }

    @Test
    fun `truncation law holds — a cut reports shown below total with a limit hint`() = runBlocking<Unit> {
        checkAll(JDX_PROPERTY_ITERATIONS, arbSnapshot("a.jar"), arbSnapshot("b.jar"), arbFilter(), arbLimit()) { old, new, filter, limit ->
            val shown = ApiDiffer.diff(old, new).ordered.count { it.severity <= filter.threshold }
            val report = reportFor(old, new, filter, limit)
            val truncation = report.truncation
            if (shown <= limit) {
                truncation shouldBe null
                report.findings.size shouldBe shown
            } else {
                truncation shouldBe Truncation(
                    shown = limit.coerceAtLeast(0),
                    total = shown,
                    hint = "--limit $shown",
                )
                report.findings.size shouldBe limit.coerceAtLeast(0)
            }
        }
    }

    @Test
    fun `the gate is consistent with the findings the report prints`() = runBlocking<Unit> {
        checkAll(JDX_PROPERTY_ITERATIONS, arbSnapshot("a.jar"), arbSnapshot("b.jar"), arbFailOn()) { old, new, failOn ->
            val report = reportFor(old, new, failOn = failOn)
            val breaking = report.counts.breaking
            report.gate.tripped shouldBe when (failOn) {
                FailOn.NONE -> false
                FailOn.BREAKING -> breaking > 0
                FailOn.ANY -> report.counts.total > 0
            }
            report.exitCode shouldBe if (report.gate.tripped) 1 else 0
            // A gate can only fail on something the report counted, and the counted
            // breaking findings are never fewer than the ones printed as breaking.
            val printedBreaking = report.findings.count { it.severity == DiffSeverity.BREAKING }
            (printedBreaking <= breaking) shouldBe true
        }
    }

    @Test
    fun `everything the text prints is present in the JSON envelope`() = runBlocking<Unit> {
        checkAll(JDX_PROPERTY_ITERATIONS, arbSnapshot("a.jar"), arbSnapshot("b.jar"), arbFilter(), arbFailOn()) { old, new, filter, failOn ->
            val report = reportFor(old, new, filter, failOn = failOn)
            val json = report.toJson(command = "diff")
            for (entity in report.findings.flatMap { listOf(it.ref, it.detail, it.rule.name, it.severity.wireName) }) {
                if (entity.isNotEmpty()) json.contains(entity) shouldBe true
            }
            json.contains(report.oldArtifact) shouldBe true
            json.contains(report.newArtifact) shouldBe true
            json.contains(report.counts.total.toString()) shouldBe true
        }
    }

    @Test
    fun `hostile member names never throw and never produce a blank ref`() = runBlocking<Unit> {
        checkAll(200, arbHostileName()) { hostile ->
            val info = classWith(hostile)
            val snapshot = ApiSnapshot.of("a.jar", listOf(info), ApiSurface.ALL, includeSynthetic = true)
            val diff = ApiDiffer.diff(snapshot, snapshot)
            diff.findings shouldBe emptyList()
            for (member in snapshot.types.values.first().members.values) {
                member.canonicalRef.isNotEmpty() shouldBe true
            }
        }
    }

    @Test
    fun `an empty artifact and a populated one differ, and neither side throws`() = runBlocking<Unit> {
        checkAll(JDX_PROPERTY_ITERATIONS, arbSnapshot("b.jar")) { populated ->
            val empty = ApiSnapshot("empty.jar", emptyMap())
            val removed = ApiDiffer.diff(populated, empty).findings.map { it.rule }
            removed.size shouldBe populated.typeCount
            removed.all { it == CompatRule.TYPE_REMOVED } shouldBe true
            val added = ApiDiffer.diff(empty, populated).findings.map { it.rule }
            added.size shouldBe populated.typeCount
            added.all { it == CompatRule.TYPE_ADDED } shouldBe true
        }
    }

    // -- helpers --------------------------------------------------------------------

    private fun arbFilter(): Arb<SeverityFilter> = Arb.of(SeverityFilter.entries)
    private fun arbFailOn(): Arb<FailOn> = Arb.of(FailOn.entries)
    private fun arbLimit(): Arb<Int> = Arb.int(0, 6)

    private fun classWith(name: String): ClassInfo = ClassInfo(
        name = TypeName.ClassType("com.example", listOf("Holder")),
        kind = TypeKind.CLASS,
        access = Access(0x0001),
        methods = listOf(
            MethodInfo(
                name = name,
                descriptor = JvmDescriptor.Method(emptyList(), TypeName.PrimitiveType(JvmPrimitive.VOID)),
                access = Access(0x0001),
            ),
        ),
        annotations = listOf(AnnotationInfo(typeNameFromBinaryName("com.example.Marker"))),
        fields = listOf(
            FieldInfo(name = name, type = TypeName.PrimitiveType(JvmPrimitive.INT), access = Access(0x0001)),
        ),
    )
}
