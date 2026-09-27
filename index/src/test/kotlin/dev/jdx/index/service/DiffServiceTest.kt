package dev.jdx.index.service

import dev.jdx.core.diff.ApiSurface
import dev.jdx.core.diff.CompatRule
import dev.jdx.core.diff.FailOn
import dev.jdx.core.diff.SeverityFilter
import dev.jdx.core.model.Origin
import dev.jdx.index.service.JdxService.ArtifactSpec
import dev.jdx.index.service.JdxService.DiffOptions
import dev.jdx.index.service.JdxService.ServiceOutcome
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.io.File
import java.nio.file.Path
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.Opcodes

/**
 * `jdx diff <old> <new>` at the service layer (issue #23, tier 2).
 *
 * Both sides are crafted jars built at test time, so every expected rule is
 * known rather than sampled. What is pinned here is the *service's* half: which
 * artifact each spec resolves to, what a bad spec exits with, and the fact that
 * the findings really describe the bytes those two jars hold. The taxonomy and
 * the report layout are `core`'s and are tested there.
 */
@Tag("tier2")
class DiffServiceTest {

    // -- fixtures -----------------------------------------------------------------

    /** `d.Greeter` declaring one public method per descriptor in [methods]. */
    private fun greeter(vararg methods: String): List<DiffType> = listOf(
        DiffType("d.Greeter", methods.map { DiffMember(it, "()V") }),
    )

    /** The same, but extending a superclass that lives in neither jar. */
    private fun greeterOverMissingBase(vararg methods: String): List<DiffType> = listOf(
        DiffType("d.Greeter", methods.map { DiffMember(it, "()V") }, superName = "d/Base"),
    )

    private fun side(jar: Path): ArtifactSpec = ArtifactSpec(spec = jar.toString())

    /**
     * A glob spec inside [dir], built by **string concatenation**, never
     * `dir.resolve(pattern)`.
     *
     * A spec is a plain string to the service — it is matched, not opened — but
     * `Path.resolve("*.jar")` *parses* the pattern, and on Windows `*` is an illegal
     * character in a path: the test would die with `InvalidPathException` before reaching
     * any assertion, on a runner that shares nothing with the developer. The `File`
     * separator keeps the joined spec valid on both.
     */
    private fun globIn(dir: Path, pattern: String): String =
        dir.toString() + File.separator + pattern

    /** [globIn], wrapped as the spec for the `new` side. */
    private fun globSpec(dir: Path, pattern: String): ArtifactSpec =
        ArtifactSpec(spec = globIn(dir, pattern))

    private fun reportOf(outcome: ServiceOutcome): ServiceOutcome.Diff {
        (outcome is ServiceOutcome.Diff) shouldBe true
        return outcome as ServiceOutcome.Diff
    }

    // -- the happy path ------------------------------------------------------------

    @Test
    fun `two artifacts with the same classes report no differences and exit 0`(@TempDir temp: Path) {
        val old = diffJar(temp.resolve("v1.jar"), greeter("greet", "count"))
        val new = diffJar(temp.resolve("v2.jar"), greeter("greet", "count"))

        val outcome = JdxService.diff(side(old), side(new))

        outcome.exitCode shouldBe 0
        val report = reportOf(outcome).report
        report.identical shouldBe true
        report.findings shouldBe emptyList()
        report.counts.typesOld shouldBe 1
        report.counts.typesNew shouldBe 1
        outcome.renderText(false) shouldContain "no differences (1 types compared)"
    }

    @Test
    fun `a member only the new jar declares is reported as MEMBER_ADDED`(@TempDir temp: Path) {
        // The differ-level proof that the two snapshots are the two jars: if the
        // service had read anything else, `fresh` could not appear at all.
        val old = diffJar(temp.resolve("v1.jar"), greeter("greet"))
        val new = diffJar(temp.resolve("v2.jar"), greeter("greet", "fresh"))

        val report = reportOf(JdxService.diff(side(old), side(new))).report

        report.findings.map { it.rule } shouldBe listOf(CompatRule.MEMBER_ADDED)
        report.findings.single().ref shouldBe "d.Greeter#fresh()"
        report.findings.single().type shouldBe "d.Greeter"
        report.counts.informational shouldBe 1
        report.counts.breaking shouldBe 0
    }

    @Test
    fun `a removed public method is a breaking MEMBER_REMOVED and still exits 0`(@TempDir temp: Path) {
        val old = diffJar(temp.resolve("v1.jar"), greeter("greet", "legacy"))
        val new = diffJar(temp.resolve("v2.jar"), greeter("greet"))

        val outcome = JdxService.diff(side(old), side(new))

        // Found something is an answer, not a failure: without `--fail-on` a
        // completed comparison exits 0 whatever it found (D-015).
        outcome.exitCode shouldBe 0
        val report = reportOf(outcome).report
        val finding = report.findings.single()
        finding.rule shouldBe CompatRule.MEMBER_REMOVED
        finding.ref shouldBe "d.Greeter#legacy()"
        report.counts.breaking shouldBe 1
    }

    @Test
    fun `a removal the supertype check could not settle says so and names the new artifact`(
        @TempDir temp: Path,
    ) {
        // `d.Base` is named as the superclass and is in neither jar, so the differ
        // cannot prove the member is not inherited: it reports the removal and names the
        // witness rather than downgrading a breaking change on a maybe (AGENTS.md §2.2).
        // A *universal* root would not do: `java.lang.Object` is in every jar's world
        // and is deliberately not treated as a missing supertype, so the "could not
        // check" wording stays reserved for a genuinely unknown supertype.
        val old = diffJar(
            temp.resolve("v1.jar"),
            greeterOverMissingBase("greet", "legacy"),
        )
        val new = diffJar(
            temp.resolve("v2.jar"),
            greeterOverMissingBase("greet"),
        )

        val finding = reportOf(JdxService.diff(side(old), side(new))).report.findings.single()

        finding.rule shouldBe CompatRule.MEMBER_REMOVED
        finding.detail shouldContain "d.Base is not in v2.jar"
        finding.detail shouldContain "inheritance not checked"
    }

    @Test
    fun `a removal under a universal supertype carries no inheritance caveat at all`(
        @TempDir temp: Path,
    ) {
        // Every ordinary class extends `java.lang.Object`, and that type is never inside
        // a third-party jar. Counting it as "missing" would print the caveat on every
        // removal in every report, which is noise exactly where the answer must be
        // trusted (AGENTS.md §2.1).
        val old = diffJar(temp.resolve("v1.jar"), greeter("greet", "legacy"))
        val new = diffJar(temp.resolve("v2.jar"), greeter("greet"))

        val finding = reportOf(JdxService.diff(side(old), side(new))).report.findings.single()

        finding.rule shouldBe CompatRule.MEMBER_REMOVED
        finding.detail shouldBe ""
    }

    @Test
    fun `a removed field is reported like a removed method`(@TempDir temp: Path) {
        val old = diffJar(
            temp.resolve("v1.jar"),
            listOf(DiffType("d.Box", fields = listOf(DiffMember("width", "I")))),
        )
        val new = diffJar(temp.resolve("v2.jar"), listOf(DiffType("d.Box")))

        val report = reportOf(JdxService.diff(side(old), side(new))).report

        report.findings.map { it.rule } shouldBe listOf(CompatRule.MEMBER_REMOVED)
        report.findings.single().ref shouldBe "d.Box#width"
    }

    @Test
    fun `the report names both sides and claims bytecode provenance`(@TempDir temp: Path) {
        val old = diffJar(temp.resolve("v1.jar"), greeter("greet"))
        val new = diffJar(temp.resolve("v2.jar"), greeter("greet", "fresh"))

        val outcome = JdxService.diff(side(old), side(new))
        val report = reportOf(outcome).report

        report.oldArtifact shouldBe "v1.jar"
        report.newArtifact shouldBe "v2.jar"
        report.query shouldBe "v1.jar -> v2.jar"
        report.provenance.map { it.artifact to it.origin } shouldBe listOf(
            "v1.jar" to Origin.BYTECODE,
            "v2.jar" to Origin.BYTECODE,
        )
        val text = outcome.renderText(false)
        text shouldContain "api diff v1.jar -> v2.jar (public surface)"
        text shouldContain "source: v1.jar (bytecode)"
        text shouldContain "source: v2.jar (bytecode)"
        // A diff never reads sources, so it must not claim to.
        text shouldNotContain "decompiled"
        outcome.toJson("diff") shouldContain "\"provenance\":[{\"artifact\":\"v1.jar\",\"origin\":\"bytecode\"}"
    }

    // -- the CI gate ----------------------------------------------------------------

    @Test
    fun `fail-on breaking exits 1 while a breaking change is present`(@TempDir temp: Path) {
        val old = diffJar(temp.resolve("v1.jar"), greeter("greet", "legacy"))
        val new = diffJar(temp.resolve("v2.jar"), greeter("greet"))

        val outcome = JdxService.diff(side(old), side(new), DiffOptions(failOn = FailOn.BREAKING))

        outcome.exitCode shouldBe 1
        val report = reportOf(outcome).report
        report.gate.tripped shouldBe true
        report.gate.breakingCount shouldBe 1
        outcome.renderText(false) shouldContain "fail-on breaking: 1 breaking change (exit 1)"
    }

    @Test
    fun `fail-on breaking exits 0 on a purely additive change`(@TempDir temp: Path) {
        val old = diffJar(temp.resolve("v1.jar"), greeter("greet"))
        val new = diffJar(temp.resolve("v2.jar"), greeter("greet", "fresh"))

        val outcome = JdxService.diff(side(old), side(new), DiffOptions(failOn = FailOn.BREAKING))

        outcome.exitCode shouldBe 0
        val report = reportOf(outcome).report
        report.gate.tripped shouldBe false
        report.findings.map { it.rule } shouldBe listOf(CompatRule.MEMBER_ADDED)
    }

    @Test
    fun `fail-on any exits 1 on a purely additive change`(@TempDir temp: Path) {
        val old = diffJar(temp.resolve("v1.jar"), greeter("greet"))
        val new = diffJar(temp.resolve("v2.jar"), greeter("greet", "fresh"))

        val outcome = JdxService.diff(side(old), side(new), DiffOptions(failOn = FailOn.ANY))

        outcome.exitCode shouldBe 1
        val report = reportOf(outcome).report
        report.gate.tripped shouldBe true
        report.gate.findingCount shouldBe 1
        report.gate.breakingCount shouldBe 0
    }

    @Test
    fun `fail-on any exits 0 when the artifacts agree`(@TempDir temp: Path) {
        val old = diffJar(temp.resolve("v1.jar"), greeter("greet"))
        val new = diffJar(temp.resolve("v2.jar"), greeter("greet"))

        val outcome = JdxService.diff(side(old), side(new), DiffOptions(failOn = FailOn.ANY))

        outcome.exitCode shouldBe 0
        reportOf(outcome).report.gate.tripped shouldBe false
    }

    // -- scope, filtering and limits ---------------------------------------------------

    @Test
    fun `a private member change is invisible by default and visible with visibility all`(
        @TempDir temp: Path,
    ) {
        val old = diffJar(
            temp.resolve("v1.jar"),
            listOf(DiffType("d.Greeter", methods = listOf(DiffMember("hidden", "()V", Opcodes.ACC_PRIVATE)))),
        )
        val new = diffJar(temp.resolve("v2.jar"), listOf(DiffType("d.Greeter")))

        val byDefault = reportOf(JdxService.diff(side(old), side(new))).report
        byDefault.findings shouldBe emptyList()

        val everything = reportOf(
            JdxService.diff(side(old), side(new), DiffOptions(visibility = ApiSurface.ALL)),
        ).report
        everything.findings.map { it.rule } shouldBe listOf(CompatRule.MEMBER_REMOVED)
        everything.findings.single().ref shouldBe "d.Greeter#hidden()"
        everything.surface shouldBe ApiSurface.ALL
    }

    @Test
    fun `the severity filter hides findings without changing the counts`(@TempDir temp: Path) {
        // One breaking removal plus one additive method: filtering out the
        // informational finding must not make the report claim there was nothing
        // to see.
        val old = diffJar(temp.resolve("v1.jar"), greeter("greet", "legacy"))
        val new = diffJar(temp.resolve("v2.jar"), greeter("greet", "fresh"))

        val outcome = JdxService.diff(
            side(old),
            side(new),
            DiffOptions(severityFilter = SeverityFilter.BREAKING),
        )
        val report = reportOf(outcome).report

        report.findings.map { it.rule } shouldBe listOf(CompatRule.MEMBER_REMOVED)
        report.hiddenBySeverity shouldBe 1
        report.counts.breaking shouldBe 1
        report.counts.informational shouldBe 1
        outcome.renderText(false) shouldContain "1 findings below --severity breaking"
    }

    @Test
    fun `maxFindings cuts the report at a whole finding and still counts every one`(
        @TempDir temp: Path,
    ) {
        val old = diffJar(temp.resolve("v1.jar"), greeter("a", "b", "c", "d"))
        val new = diffJar(temp.resolve("v2.jar"), greeter("a"))

        val outcome = JdxService.diff(side(old), side(new), DiffOptions(maxFindings = 2))
        val report = reportOf(outcome).report

        report.findings.size shouldBe 2
        report.counts.breaking shouldBe 3
        report.truncation?.shown shouldBe 2
        report.truncation?.total shouldBe 3
        outcome.renderText(false) shouldContain "2 of 3 findings shown (--limit 3 to see more)"
    }

    @Test
    fun `a negative maxFindings is a usage error`(@TempDir temp: Path) {
        val old = diffJar(temp.resolve("v1.jar"), greeter("greet"))
        val new = diffJar(temp.resolve("v2.jar"), greeter("greet"))

        val outcome = JdxService.diff(side(old), side(new), DiffOptions(maxFindings = -1))

        outcome.exitCode shouldBe 3
        outcome.renderText(false) shouldContain "usage error: --limit must be >= 0, got -1"
    }

    // -- degrading, not failing ----------------------------------------------------------

    @Test
    fun `a corrupt class is skipped, the rest of the diff answers, and the class is named`(
        @TempDir temp: Path,
    ) {
        val old = diffJar(
            temp.resolve("v1.jar"),
            greeter("greet", "legacy"),
            corruptEntries = listOf("d/Broken.class"),
        )
        val new = diffJar(
            temp.resolve("v2.jar"),
            greeter("greet"),
            corruptEntries = listOf("d/Broken.class"),
        )

        val outcome = JdxService.diff(side(old), side(new))

        // Degrade, don't fail (AGENTS.md §2.8): the readable classes still diff.
        outcome.exitCode shouldBe 0
        val report = reportOf(outcome).report
        report.findings.map { it.rule } shouldBe listOf(CompatRule.MEMBER_REMOVED)
        // The skipped class is invisible as a type difference, and says so.
        report.counts.typesAdded shouldBe 0
        report.counts.typesRemoved shouldBe 0
        val corrupt = report.warnings.filter { it.code.name == "CORRUPT_CLASS" }
        // Both sides hold the broken class and both trip over it: each side's
        // warning is attributed (`in <label>:`), so the same unreadable class
        // in two jars is two facts and reports once per side.
        corrupt.size shouldBe 2
        corrupt.forEach { it.subject shouldBe "d.Broken" }
        corrupt[0].message shouldContain "in v1.jar:"
        corrupt[1].message shouldContain "in v2.jar:"
        outcome.renderText(false) shouldContain "warning CORRUPT_CLASS: in v1.jar:"
        outcome.renderText(false) shouldContain "warning CORRUPT_CLASS: in v2.jar:"
        outcome.toJson("diff") shouldContain "\"code\":\"CORRUPT_CLASS\""
        outcome.toJson("diff") shouldContain "\"subject\":\"d.Broken\""
    }

    @Test
    fun `a corrupt class in one jar only does not fake a type difference`(@TempDir temp: Path) {
        // The dangerous shape: if the reader dropped the entry silently, the new
        // side would look like it lost a type and the report would invent a
        // `TYPE_REMOVED` nobody made.
        val old = diffJar(temp.resolve("v1.jar"), greeter("greet"))
        val new = diffJar(
            temp.resolve("v2.jar"),
            greeter("greet"),
            corruptEntries = listOf("d/Broken.class"),
        )

        val report = reportOf(JdxService.diff(side(old), side(new))).report

        report.findings shouldBe emptyList()
        report.identical shouldBe true
        report.warnings.map { it.code.name } shouldBe listOf("CORRUPT_CLASS")
        // The warning names the side that produced it, so an agent can tell
        // the class was dropped from the new jar and not the old one.
        report.warnings.single().message shouldContain "in v2.jar:"
        report.warnings.single().subject shouldBe "d.Broken"
    }

    @Test
    fun `a corrupt class in the old jar only is attributed to the old side`(@TempDir temp: Path) {
        val old = diffJar(
            temp.resolve("v1.jar"),
            greeter("greet"),
            corruptEntries = listOf("d/Broken.class"),
        )
        val new = diffJar(temp.resolve("v2.jar"), greeter("greet"))

        val outcome = JdxService.diff(side(old), side(new))
        val report = reportOf(outcome).report

        report.findings shouldBe emptyList()
        report.identical shouldBe true
        report.warnings.map { it.code.name } shouldBe listOf("CORRUPT_CLASS")
        report.warnings.single().message shouldContain "in v1.jar:"
        report.warnings.single().subject shouldBe "d.Broken"
        outcome.renderText(false) shouldContain "warning CORRUPT_CLASS: in v1.jar:"
    }

    @Test
    fun `a self-diff with a corrupt class still reports the warning once`(@TempDir temp: Path) {
        // Same label on both sides: attribution produces identical messages,
        // so distinct() still collapses the union to one entry.
        val jar = diffJar(
            temp.resolve("v1.jar"),
            greeter("greet"),
            corruptEntries = listOf("d/Broken.class"),
        )

        val report = reportOf(JdxService.diff(side(jar), side(jar))).report

        report.warnings.map { it.code.name } shouldBe listOf("CORRUPT_CLASS")
        report.warnings.single().message shouldContain "in v1.jar:"
    }

    @Test
    fun `an empty jar is compared, not refused`(@TempDir temp: Path) {
        // A jar with no classes is a legal input: the caller decides whether an
        // empty comparison is meaningful, and the snapshot layer never invents a
        // warning for it.
        val old = diffJar(temp.resolve("v1.jar"), greeter("greet"))
        val new = diffJar(temp.resolve("v2.jar"))

        val outcome = JdxService.diff(side(old), side(new))
        val report = reportOf(outcome).report

        outcome.exitCode shouldBe 0
        report.counts.typesOld shouldBe 1
        report.counts.typesNew shouldBe 0
        report.findings.map { it.rule } shouldBe listOf(CompatRule.TYPE_REMOVED)
        report.warnings shouldBe emptyList()
    }

    // -- bad input -----------------------------------------------------------------------

    @Test
    fun `a file that does not exist is an artifact read error`(@TempDir temp: Path) {
        val new = diffJar(temp.resolve("v2.jar"), greeter("greet"))

        val outcome = JdxService.diff(side(temp.resolve("absent.jar")), side(new))

        outcome.exitCode shouldBe 5
        outcome.renderText(false) shouldContain "artifact read error: no such artifact"
        outcome.renderText(false) shouldContain "absent.jar"
    }

    @Test
    fun `a spec matching no artifact is a usage error naming the spec and the fix`(
        @TempDir temp: Path,
    ) {
        val old = diffJar(temp.resolve("v1.jar"), greeter("greet"))

        val outcome = JdxService.diff(side(old), globSpec(temp, "*.absent"))

        outcome.exitCode shouldBe 3
        val text = outcome.renderText(false)
        text shouldContain "usage error: diff new:"
        text shouldContain "matches no artifact"
        text shouldContain "group:artifact:version"
    }

    @Test
    fun `an empty spec is a usage error naming the side`(@TempDir temp: Path) {
        val old = diffJar(temp.resolve("v1.jar"), greeter("greet"))

        val outcome = JdxService.diff(ArtifactSpec(spec = "   "), side(old))

        outcome.exitCode shouldBe 3
        val text = outcome.renderText(false)
        text shouldContain "usage error: diff old: the argument is empty"
    }

    @Test
    fun `a spec matching several jars is a usage error`(@TempDir temp: Path) {
        val libs = temp.resolve("libs")
        diffJar(libs.resolve("one.jar"), greeter("greet"))
        diffJar(libs.resolve("two.jar"), greeter("greet", "fresh"))
        val other = diffJar(temp.resolve("v1.jar"), greeter("greet"))

        val outcome = JdxService.diff(side(other), globSpec(libs, "*.jar"))

        outcome.exitCode shouldBe 3
        val text = outcome.renderText(false)
        text shouldContain "usage error: diff compares two artifacts;"
        text shouldContain "resolved to 2 jars"
    }

    @Test
    fun `a malformed coordinate is a usage error naming the coordinate`(@TempDir temp: Path) {
        val new = diffJar(temp.resolve("v2.jar"), greeter("greet"))

        val outcome = JdxService.diff(ArtifactSpec(spec = "com.example:demo:1.0:extra"), side(new))

        outcome.exitCode shouldBe 3
        val text = outcome.renderText(false)
        text shouldContain "usage error: invalid Maven coordinate 'com.example:demo:1.0:extra'"
        text shouldContain "expected group:artifact:version"
    }

    @Test
    fun `a coordinate that is not in the local caches is an artifact read error`(
        @TempDir temp: Path,
    ) {
        val new = diffJar(temp.resolve("v2.jar"), greeter("greet"))

        val outcome = JdxService.diff(
            ArtifactSpec(spec = "com.example.does-not-exist:demo:9.9.9-jdx-diff"),
            side(new),
        )

        // Local caches only, and nothing was fetched (D-006): the same exit and
        // message `--coord` gives for an artifact that is not there, and the
        // message names `--fetch` as the opt-in.
        outcome.exitCode shouldBe 5
        val text = outcome.renderText(false)
        text shouldContain "artifact read error:"
        text shouldContain "is not in the local caches"
        text shouldContain "--fetch"
    }

    @Test
    fun `the old side is resolved before the new one`(@TempDir temp: Path) {
        // The error must name the side that is actually wrong, whichever order the
        // two are given in.
        val good = diffJar(temp.resolve("v1.jar"), greeter("greet"))

        val oldFirst = JdxService.diff(side(temp.resolve("absent.jar")), side(good))
        oldFirst.exitCode shouldBe 5
        oldFirst.renderText(false) shouldContain "absent.jar"

        val newFirst = JdxService.diff(side(good), side(temp.resolve("absent.jar")))
        newFirst.exitCode shouldBe 5
        newFirst.renderText(false) shouldContain "absent.jar"
    }
}
