package dev.jdx.core.render

import dev.jdx.core.diff.ApiDiffer
import dev.jdx.core.diff.ApiSnapshot
import dev.jdx.core.diff.ApiSurface
import dev.jdx.core.diff.CompatRule
import dev.jdx.core.diff.DiffFinding
import dev.jdx.core.diff.DiffSeverity
import dev.jdx.core.diff.FailOn
import dev.jdx.core.diff.SeverityFilter
import dev.jdx.core.model.Access
import dev.jdx.core.model.AccessFlag
import dev.jdx.core.model.JvmDescriptor
import dev.jdx.core.model.JvmPrimitive
import dev.jdx.core.model.MethodInfo
import dev.jdx.core.model.Origin
import dev.jdx.core.model.Provenance
import dev.jdx.core.model.TypeName
import dev.jdx.core.model.Warning
import dev.jdx.core.model.WarningCode
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

/**
 * The `jdx diff` report (issue #23): a header naming both sides, one line per finding
 * in severity order, the per-severity tally, the gate verdict, provenance, the next
 * step — and the same facts structurally in the JSON envelope.
 *
 * The text is pinned **line by line**, not with `shouldContain`, because the whole
 * point of this command is that an agent can read twenty lines and stop: an extra
 * blank line or a reordered column is a regression in the answer, not in the spelling.
 */
class DiffReportTest {

    private val string = TypeName.PrimitiveType(JvmPrimitive.INT)
    private val long = TypeName.PrimitiveType(JvmPrimitive.LONG)

    private fun method(name: String, parameters: List<TypeName> = emptyList()) = MethodInfo(
        name = name,
        descriptor = JvmDescriptor.Method(parameters, TypeName.PrimitiveType(JvmPrimitive.VOID)),
        access = Access(AccessFlag.PUBLIC.mask),
    )

    private fun snapshot(label: String, methods: List<MethodInfo>): ApiSnapshot =
        ApiSnapshot.of(label, listOf(classInfo(methods)), ApiSurface.ALL, includeSynthetic = false)

    private fun classInfo(methods: List<MethodInfo>) = dev.jdx.core.model.ClassInfo(
        name = TypeName.ClassType("com.example", listOf("Foo")),
        kind = dev.jdx.core.model.TypeKind.CLASS,
        access = Access(AccessFlag.PUBLIC.mask),
        methods = methods,
    )

    private fun report(
        old: ApiSnapshot,
        new: ApiSnapshot,
        filter: SeverityFilter = SeverityFilter.ALL,
        failOn: FailOn = FailOn.NONE,
        limit: Int = Int.MAX_VALUE,
        surface: ApiSurface = ApiSurface.PUBLIC,
        includeSynthetic: Boolean = false,
        warnings: List<Warning> = emptyList(),
        provenance: List<Provenance> = listOf(
            Provenance(artifact = "a.jar", origin = Origin.BYTECODE),
            Provenance(artifact = "b.jar", origin = Origin.BYTECODE),
        ),
    ) = buildDiffReport(
        diff = ApiDiffer.diff(old, new),
        surface = surface,
        includeSynthetic = includeSynthetic,
        severityFilter = filter,
        failOn = failOn,
        maxFindings = limit,
        provenance = provenance,
        warnings = warnings,
    )

    @Test
    fun `two artifacts with one removed method render in five lines`() {
        val text = report(
            snapshot("a.jar", listOf(method("kept"), method("gone", listOf(string)))),
            snapshot("b.jar", listOf(method("kept"))),
        ).renderText()
        text shouldBe """
            api diff a.jar -> b.jar (public surface)
            breaking  MEMBER_REMOVED  com.example.Foo#gone(int)
            1 breaking · 0 suspicious · 0 informational · 0 types added · 0 types removed
            source: a.jar (bytecode)
            source: b.jar (bytecode)
            next: jdx members com.example.Foo
        """.trimIndent()
    }

    @Test
    fun `a finding with a detail prints it after the ref`() {
        val old = snapshot("a.jar", listOf(method("f", listOf(string))))
        val new = snapshot("b.jar", listOf(method("f", listOf(long))))
        report(old, new).renderText() shouldContain "breaking  PARAMETER_TYPE_CHANGED  com.example.Foo#f(int): now: com.example.Foo#f(long)"
    }

    @Test
    fun `findings print most severe first, whatever order the differ produced them`() {
        val findings = listOf(
            DiffFinding(CompatRule.MEMBER_ADDED, "com.example.Foo", "com.example.Foo#added"),
            DiffFinding(CompatRule.CHECKED_EXCEPTION_ADDED, "com.example.Foo", "com.example.Foo#f()", "java.io.IOException"),
            DiffFinding(CompatRule.MEMBER_REMOVED, "com.example.Foo", "com.example.Foo#gone"),
        )
        val block = buildDiffReport(
            diff = dev.jdx.core.diff.ApiDiff("a.jar", "b.jar", 1, 1, findings),
        ).renderText()
        val lines = block.lines()
        lines[1] shouldBe "breaking  MEMBER_REMOVED  com.example.Foo#gone"
        lines[2] shouldBe "suspicious  CHECKED_EXCEPTION_ADDED  com.example.Foo#f(): java.io.IOException"
        lines[3] shouldBe "info  MEMBER_ADDED  com.example.Foo#added"
    }

    @Test
    fun `identical artifacts answer in three lines and exit 0`() {
        val same = snapshot("a.jar", listOf(method("f")))
        val identical = report(same, same)
        identical.identical shouldBe true
        identical.exitCode shouldBe 0
        identical.renderText() shouldBe """
            api diff a.jar -> a.jar (public surface)
            no differences (1 types compared)
            source: a.jar (bytecode)
            source: b.jar (bytecode)
        """.trimIndent()
    }

    @Test
    fun `a type that only one side has is named in the tally`() {
        val old = snapshot("a.jar", listOf(method("f")))
        val new = dev.jdx.core.diff.ApiSnapshot.of(
            "b.jar",
            listOf(
                classInfo(listOf(method("f"))),
                dev.jdx.core.model.ClassInfo(
                    name = TypeName.ClassType("com.example", listOf("Fresh")),
                    kind = dev.jdx.core.model.TypeKind.CLASS,
                    access = Access(AccessFlag.PUBLIC.mask),
                ),
            ),
            ApiSurface.ALL,
        )
        report(old, new).renderText() shouldContain "0 breaking · 0 suspicious · 1 informational · 1 type added · 0 types removed"
    }

    @Test
    fun `truncation cuts whole findings and names the limit`() {
        val old = snapshot("a.jar", listOf(method("a"), method("b"), method("c")))
        val new = snapshot("b.jar", emptyList())
        val text = report(old, new, limit = 2).renderText()
        text shouldContain "breaking  MEMBER_REMOVED  com.example.Foo#a()"
        text shouldContain "breaking  MEMBER_REMOVED  com.example.Foo#b()"
        text shouldNotContain "#c()"
        text shouldContain "2 of 3 findings shown (--limit 3 to see more)"
    }

    @Test
    fun `an exact fit is not truncation`() {
        val old = snapshot("a.jar", listOf(method("a"), method("b")))
        val new = snapshot("b.jar", emptyList())
        report(old, new, limit = 2).truncation shouldBe null
    }

    @Test
    fun `the severity filter hides findings and says how many`() {
        val findings = listOf(
            DiffFinding(CompatRule.MEMBER_REMOVED, "com.example.Foo", "com.example.Foo#gone"),
            DiffFinding(CompatRule.CHECKED_EXCEPTION_ADDED, "com.example.Foo", "com.example.Foo#f()", "java.io.IOException"),
            DiffFinding(CompatRule.MEMBER_ADDED, "com.example.Foo", "com.example.Foo#added"),
        )
        val breaking = buildDiffReport(
            diff = dev.jdx.core.diff.ApiDiff("a.jar", "b.jar", 1, 1, findings),
            severityFilter = SeverityFilter.BREAKING,
        )
        breaking.findings.map { it.rule } shouldBe listOf(CompatRule.MEMBER_REMOVED)
        breaking.hiddenBySeverity shouldBe 2
        breaking.renderText() shouldContain "2 findings below --severity breaking"
        breaking.renderText() shouldContain "1 breaking · 1 suspicious · 1 informational"
    }

    @Test
    fun `the all surface and the synthetic flag are named in the header and in JSON`() {
        val report = report(
            snapshot("a.jar", listOf(method("gone"))),
            snapshot("b.jar", emptyList()),
            surface = ApiSurface.ALL,
            includeSynthetic = true,
        )
        report.renderText() shouldContain "api diff a.jar -> b.jar (all surface)"
        report.toJson("diff") shouldContain "\"surface\":\"all\""
        report.toJson("diff") shouldContain "\"includeSynthetic\":true"
    }

    // -- the gate -------------------------------------------------------------------

    @Test
    fun `the gate is silent unless one was asked for`() {
        val old = snapshot("a.jar", listOf(method("gone")))
        val new = snapshot("b.jar", emptyList())
        report(old, new).renderText() shouldNotContain "fail-on"
        report(old, new).exitCode shouldBe 0
    }

    @Test
    fun `fail-on breaking exits 1 on a breaking change and 0 without`() {
        val breaking = report(
            snapshot("a.jar", listOf(method("gone"))),
            snapshot("b.jar", emptyList()),
            failOn = FailOn.BREAKING,
        )
        breaking.exitCode shouldBe 1
        breaking.renderText() shouldContain "fail-on breaking: 1 breaking change (exit 1)"
        val clean = report(
            snapshot("a.jar", listOf()),
            snapshot("b.jar", listOf()),
            failOn = FailOn.BREAKING,
        )
        clean.exitCode shouldBe 0
        clean.renderText() shouldContain "fail-on breaking: no breaking changes (exit 0)"
    }

    @Test
    fun `fail-on any trips on a purely additive change`() {
        val additive = report(
            snapshot("a.jar", emptyList()),
            snapshot("b.jar", listOf(method("fresh"))),
            failOn = FailOn.ANY,
        )
        additive.exitCode shouldBe 1
        additive.renderText() shouldContain "fail-on any: 1 difference (exit 1)"
        val none = report(
            snapshot("a.jar", emptyList()),
            snapshot("b.jar", emptyList()),
            failOn = FailOn.ANY,
        )
        none.renderText() shouldContain "fail-on any: no differences (exit 0)"
    }

    @Test
    fun `a filter cannot hide a breaking change from the gate`() {
        val report = report(
            snapshot("a.jar", listOf(method("gone"))),
            snapshot("b.jar", emptyList()),
            filter = SeverityFilter.BREAKING,
            failOn = FailOn.BREAKING,
        )
        report.exitCode shouldBe 1
        report.hiddenBySeverity shouldBe 0
    }

    // -- warnings, provenance, JSON ---------------------------------------------------

    @Test
    fun `a warning is printed and carried in the envelope`() {
        val warning = Warning(WarningCode.CORRUPT_CLASS, "class file is not parseable", "com.example.Broken")
        val report = report(
            snapshot("a.jar", listOf(method("gone"))),
            snapshot("b.jar", emptyList()),
            warnings = listOf(warning),
        )
        report.renderText() shouldContain "warning CORRUPT_CLASS: class file is not parseable"
        report.toJson("diff") shouldContain "\"code\":\"CORRUPT_CLASS\""
    }

    @Test
    fun `both sides are named as provenance, and a missing label is said plainly`() {
        val report = report(
            snapshot("a.jar", listOf(method("gone"))),
            snapshot("b.jar", emptyList()),
        )
        report.renderText() shouldContain "source: a.jar (bytecode)\nsource: b.jar (bytecode)"
        val unprovenanced = buildDiffReport(
            diff = ApiDiffer.diff(
                snapshot("a.jar", listOf(method("gone"))),
                snapshot("b.jar", emptyList()),
            ),
        )
        unprovenanced.renderText() shouldContain "source: no provenance recorded"
    }

    @Test
    fun `the envelope carries both sides, the surface, the counts, the gate and every finding`() {
        val json = report(
            snapshot("a.jar", listOf(method("gone"))),
            snapshot("b.jar", emptyList()),
            failOn = FailOn.BREAKING,
        ).toJson(command = "diff")
        json shouldContain "\"jdx\":1"
        json shouldContain "\"ok\":true"
        json shouldContain "\"command\":\"diff\""
        json shouldContain "\"query\":\"a.jar -> b.jar\""
        json shouldContain "\"old\":\"a.jar\""
        json shouldContain "\"new\":\"b.jar\""
        json shouldContain "\"identical\":false"
        json shouldContain "\"typesOld\":1"
        json shouldContain "\"typesNew\":1"
        json shouldContain "\"breaking\":1"
        json shouldContain "\"total\":1"
        json shouldContain "\"mode\":\"breaking\""
        json shouldContain "\"tripped\":true"
        json shouldContain "\"exitCode\":1"
        json shouldContain "\"severity\":\"breaking\""
        json shouldContain "\"rule\":\"MEMBER_REMOVED\""
        json shouldContain "\"ref\":\"com.example.Foo#gone()\""
        json shouldContain "\"detail\":\"\""
    }

    @Test
    fun `the envelope is one line, so a stream can carry it`() {
        val report = report(
            snapshot("a.jar", listOf(method("gone", listOf(string)))),
            snapshot("b.jar", emptyList()),
        )
        report.toJson("diff").contains("\n") shouldBe false
    }

    @Test
    fun `a detail with a quote or a newline is escaped, not dropped`() {
        val findings = listOf(
            DiffFinding(CompatRule.ANNOTATION_ADDED, "com.example.Foo", "com.example.Foo#f()", "a\"b\\c\nd"),
        )
        val json = buildDiffReport(dev.jdx.core.diff.ApiDiff("a.jar", "b.jar", 1, 1, findings)).toJson("diff")
        json shouldContain "\"detail\":\"a\\\"b\\\\c\\nd\""
    }

    // -- the next step -----------------------------------------------------------------

    @Test
    fun `the next step names the first breaking type, or the first type when nothing broke`() {
        val breaking = report(
            snapshot("a.jar", listOf(method("gone"))),
            snapshot("b.jar", listOf(method("fresh"))),
        )
        breaking.renderText() shouldContain "next: jdx members com.example.Foo"

        val informational = buildDiffReport(
            diff = dev.jdx.core.diff.ApiDiff(
                "a.jar",
                "b.jar",
                1,
                1,
                listOf(DiffFinding(CompatRule.MEMBER_ADDED, "com.example.Other", "com.example.Other#x")),
            ),
        )
        informational.renderText() shouldContain "next: jdx members com.example.Other"
    }

    @Test
    fun `identical artifacts get no next step, because there is nothing to look at`() {
        val same = snapshot("a.jar", listOf(method("f")))
        report(same, same).renderText() shouldNotContain "next:"
    }

    @Test
    fun `the wire spelling of a severity is lowercase, and the filter names print that way`() {
        DiffSeverity.BREAKING.wireName shouldBe "breaking"
        DiffSeverity.SUSPICIOUS.wireName shouldBe "suspicious"
        DiffSeverity.INFO.wireName shouldBe "info"
        ApiSurface.ALL.wireName shouldBe "all"
        ApiSurface.PUBLIC.wireName shouldBe "public"
        SeverityFilter.SUSPICIOUS.wireName shouldBe "suspicious"
    }
}
