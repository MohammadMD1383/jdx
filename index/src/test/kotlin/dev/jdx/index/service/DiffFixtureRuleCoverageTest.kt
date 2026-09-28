package dev.jdx.index.service

import dev.jdx.core.diff.ApiSurface
import dev.jdx.core.diff.CompatRule
import dev.jdx.core.diff.FailOn
import dev.jdx.core.diff.SeverityFilter
import dev.jdx.index.service.JdxService.ArtifactSpec
import dev.jdx.index.service.JdxService.DiffOptions
import dev.jdx.index.service.JdxService.ServiceOutcome
import dev.jdx.testsupport.fixtures.DiffFixtureJars
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Rule coverage over the **real** `javac` output of the diff fixture pair (issue #23,
 * `docs/TESTING.md` §11.2).
 *
 * The per-rule examples in `core/diff/ApiDifferTest.kt` pin each rule against a
 * hand-built snapshot, which is how you learn whether a rule is *implemented*. This
 * suite answers the different question that only real bytecode can answer: **does the
 * rule actually fire on what a compiler emits?** A rule can pass every hand-built
 * example and still never fire, because the reader never produces the shape it expects
 * — a bridge it drops, a Kotlin view it never decodes, a modifier bit it reads from the
 * wrong declaration kind. That is precisely the class of bug the whole corpus exists to
 * catch, and it is why the pair is a fixture at all.
 *
 * It is also the pair's `docs/TESTING.md` §11.1 stand-in: a `@ExpectedMembers`
 * annotation describes one type, while the property that matters here belongs to the
 * *pair*, so a new rule with no fixture shows up as this test failing.
 *
 * Two directions, both necessary:
 *
 * - **Every rule in [REQUIRED] fires.** A rule nobody can trigger is dead weight in the
 *   catalogue and in the output contract.
 * - **Every class that changed reports something, and nothing else does.** A silent class
 *   is a blind spot; a class reporting a difference the fixtures never made is a false
 *   alarm in the golden. [UNCHANGED] pins the latter.
 *
 * Tier 2: it needs the two fixture jars, which the build compiles on demand.
 */
@Tag("tier2")
class DiffFixtureRuleCoverageTest {

    /**
     * Every rule the pair must trigger, and where to find it in the sources. Keep this
     * list honest in both directions: a rule added to `CompatRule` belongs here or the
     * catalogue is claiming coverage it does not have.
     */
    private val required: Map<CompatRule, String> = mapOf(
        // -- types
        CompatRule.TYPE_REMOVED to "diffV1/…/Departed.java (gone in v2)",
        CompatRule.TYPE_ADDED to "diffV2/…/Extra.java (new in v2)",
        CompatRule.TYPE_KIND_CHANGED to "Convertible: class -> interface",
        CompatRule.TYPE_MADE_FINAL to "Tuned gains final",
        CompatRule.SUPERTYPE_REMOVED to "Api drops java.io.Serializable",
        CompatRule.SUPERTYPE_ADDED to "Api gains Extra, Relocated gains Base",
        // -- members
        CompatRule.MEMBER_REMOVED to "Api#legacy(), Convertible#<init>()",
        CompatRule.MEMBER_ADDED to "Api#born(), Api#alsoRequired()",
        CompatRule.MEMBER_MOVED_TO_SUPERTYPE to "Relocated#shared() moves to Base",
        CompatRule.MEMBER_VISIBILITY_NARROWED to "Api#narrowed(), Tuned#opened()",
        CompatRule.MEMBER_MADE_FINAL to "Api#sealable()",
        CompatRule.STATIC_TO_INSTANCE to "Api#shared()",
        CompatRule.ABSTRACT_ADDED to "Convertible becomes an interface",
        CompatRule.INTERFACE_METHOD_ADDED to "Contract#alsoRequired()",
        CompatRule.ENUM_CONSTANT_REMOVED to "Signal#RED",
        CompatRule.PARAMETER_TYPE_CHANGED to "Api#retyped, Api#spread",
        CompatRule.RETURN_TYPE_CHANGED to "Api#width()",
        CompatRule.FIELD_TYPE_CHANGED to "Api#counter",
        // -- Kotlin-visible: the only place these are proven against real `@Metadata`
        //    rather than a hand-built `KotlinMethodView`
        CompatRule.KOTLIN_NAME_CHANGED to "Kotlinish#greet loses @JvmName",
        CompatRule.KOTLIN_SUSPEND_CHANGED to "Kotlinish#fetch becomes suspend",
        CompatRule.KOTLIN_NULLABILITY_CHANGED to "Kotlinish#nullable(): String -> String?",
        CompatRule.KOTLIN_DEFAULT_ARG_REMOVED to "Kotlinish#repeat loses its default",
        CompatRule.KOTLIN_DEFAULT_ARG_ADDED to "Kotlinish#describe gains a default",
        CompatRule.KOTLIN_PARAMETER_NAME_CHANGED to "Kotlinish#measure: count -> total",
        CompatRule.KOTLIN_PROPERTY_BECAME_MUTABLE to "Kotlinish#current: val -> var",
        // -- source-visible
        CompatRule.FINAL_REMOVED to "Kotlinish#current: a val's field loses final",
        CompatRule.GENERIC_SIGNATURE_CHANGED to "Api#echoed()",
        CompatRule.CHECKED_EXCEPTION_ADDED to "Api#checked()",
        CompatRule.THROWS_REMOVED to "Api#stale()",
        CompatRule.DEPRECATED_ADDED to "Api#fresh(), Api#marked()",
        CompatRule.DEPRECATED_REMOVED to "Api#stale()",
        CompatRule.ANNOTATION_ADDED to "Api#fresh(), Kotlinish#label()",
        CompatRule.ANNOTATION_REMOVED to "Api#stale(), Kotlinish#label()",
        CompatRule.SYNCHRONIZED_ADDED to "Tuned#guarded()",
        CompatRule.TRANSIENT_CHANGED to "Tuned#scratch",
        CompatRule.FIELD_CONSTANT_VALUE_CHANGED to "Api#LIMIT",
    )

    /**
     * Rules that are correct but that **no compiler will ever produce input for**, so the
     * pair cannot reach them and demanding it would be claiming coverage that does not
     * exist. Each is pinned by a hand-built snapshot in `core/diff/ApiDifferTest.kt`
     * instead, which is the only way to state the rule at all.
     *
     * - `VARARGS_CHANGED` — `javac` gives `f(String)` and `f(String...)` *different*
     *   descriptors (`…Ljava/lang/String;` vs `…[Ljava/lang/String;`), so turning a
     *   fixed-arity method into a varargs one is a `PARAMETER_TYPE_CHANGED` (which is
     *   what `Api#spread()` produces). Flipping `ACC_VARARGS` on a method that already
     *   takes an array is not expressible in Java source, so the rule only fires on
     *   hand-modified bytecode.
     * - The remaining modifier and annotation rules — `MEMBER_VISIBILITY_NARROWED` on a
     *   *type*, `TYPE_VISIBILITY_NARROWED`, `ABSTRACT_REMOVED`, `FINAL_REMOVED`,
     *   `INSTANCE_TO_STATIC`, `NATIVE_ADDED`/`NATIVE_REMOVED`, `ANNOTATION_VALUES_CHANGED`,
     *   `ANNOTATION_DEFAULT_CHANGED`, `PARAMETER_NAME_CHANGED`, `PARAMETER_NAMES_LOST` —
     *   are all reachable and all covered by the per-rule examples. The pair is chosen for
     *   the *link-breaking* and *Kotlin-visible* rules, not for one instance of every
     *   modifier flag; `ANNOTATION_VALUES_CHANGED` in particular is deliberately
     *   unreachable here, because the pair's only annotation-value changes are on
     *   `kotlin.Metadata`, which the differ deliberately ignores.
     */
    private val unreachableFromJavac: Set<CompatRule> = setOf(CompatRule.VARARGS_CHANGED)

    /**
     * Types whose two versions are deliberately identical. A finding naming one of these
     * is a false alarm: the differ read something that did not change.
     */
    private val unchanged = listOf("dev.jdx.diffapi.Stable")

    private fun diff(v1: java.io.File, v2: java.io.File): ServiceOutcome.Diff {
        // The pair is a *second* corpus (TESTING.md §11.2); ask before resolving so a
        // machine without it reports a skip rather than a red that names the differ
        // (issue #66). Under `./gradlew` the build always builds it first.
        assumeTrue(
            DiffFixtureJars.available(),
            "no diff fixture pair: build :testfixtures:diffV1Jar :testfixtures:diffV2Jar",
        )
        val outcome = JdxService.diff(
            old = ArtifactSpec(spec = v1.absolutePath),
            new = ArtifactSpec(spec = v2.absolutePath),
            options = DiffOptions(
                // The public surface is the default a consumer sees; `all` is what lets a
                // fixture target a private or package-private member, which several do.
                visibility = ApiSurface.ALL,
                includeSynthetic = true,
                severityFilter = SeverityFilter.ALL,
                failOn = FailOn.NONE,
            ),
        )
        (outcome is ServiceOutcome.Diff) shouldBe true
        return outcome as ServiceOutcome.Diff
    }

    @Test
    fun `every rule in the catalogue fires against real javac output`() {
        val report = diff(DiffFixtureJars.v1Jar(), DiffFixtureJars.v2Jar()).report
        val fired = report.findings.map { it.rule }.toSet()
        val missing = required.keys - fired

        // A failure here names the rules and where a fixture for them would go, so the
        // next person does not have to read the whole pair to find the gap.
        missing shouldBe emptySet<CompatRule>().also {
            check(missing.isEmpty()) {
                "these rules never fired against the diff fixture pair, so either the " +
                    "rule is unreachable from real bytecode or the pair has no fixture " +
                    "for it:\n" + missing.joinToString("\n") { rule ->
                        "  $rule — expected from: ${required.getValue(rule)}"
                    } + "\nThe pair lives in testfixtures/src/diffV1 and src/diffV2; " +
                    "add the fixture and this test goes green."
            }
        }
        // And the reverse direction: a finding nobody declared is a surprise, so it has
        // to be added to the list above with its fixture, not left unexplained.
        val undeclared = fired - required.keys
        undeclared shouldBe emptySet<CompatRule>().also {
            check(undeclared.isEmpty()) {
                "these rules fired but are not declared above, so a fixture grew without " +
                    "its expectation: $undeclared"
            }
        }
        // A pass must not be a pass by emptiness, and a rule the pair cannot reach must
        // not be counted as covered by it.
        fired.size shouldBe required.size
        (required.keys intersect unreachableFromJavac) shouldBe emptySet()
    }

    @Test
    fun `a type that did not change is never named`() {
        val report = diff(DiffFixtureJars.v1Jar(), DiffFixtureJars.v2Jar()).report
        // `Departed` exists only in v1 and `Extra` only in v2, so neither can appear as a
        // *member-level* finding; the rest of the pair must stay silent about them.
        for (type in unchanged) {
            val spurious = report.findings.filter {
                it.type == type && it.rule != CompatRule.TYPE_REMOVED && it.rule != CompatRule.TYPE_ADDED
            }
            spurious shouldBe emptyList<dev.jdx.core.diff.DiffFinding>().also {
                check(spurious.isEmpty()) {
                    "these findings name $type, whose two versions differ only where the " +
                        "test expects: $spurious"
                }
            }
        }
    }

    @Test
    fun `the un-checkable supertype is named rather than assumed`() {
        val report = diff(DiffFixtureJars.v1Jar(), DiffFixtureJars.v2Jar()).report
        // `Orphaned` extends a type that is in neither jar. The removal must stay BREAKING
        // and say which supertype could not be read — never downgraded on a maybe.
        val removal = report.findings.single {
            it.type == "dev.jdx.diffapi.Orphaned" && it.rule == CompatRule.MEMBER_REMOVED
        }
        removal.severity shouldBe dev.jdx.core.diff.DiffSeverity.BREAKING
        removal.detail shouldBe
            "supertype dev.jdx.absent.Supertype is not in ${DiffFixtureJars.v2Jar().name}; " +
            "inheritance not checked"
    }

    @Test
    fun `a public-surface diff hides what a consumer cannot see`() {
        // The same pair on the default surface. `Tuned#opened()` goes public → package
        // private, and on the public surface that reads as the member *leaving* the
        // surface — a removal, not a narrowing. That is the honest reading: a consumer
        // compiling against the public surface can no longer see the member at all, and
        // `MEMBER_REMOVED` says so without mentioning a visibility level it cannot
        // observe. The public narrowing on `Api#narrowed()` stays a narrowing.
        val outcome = JdxService.diff(
            old = ArtifactSpec(spec = DiffFixtureJars.v1Jar().absolutePath),
            new = ArtifactSpec(spec = DiffFixtureJars.v2Jar().absolutePath),
            options = DiffOptions(visibility = ApiSurface.PUBLIC),
        )
        val report = (outcome as ServiceOutcome.Diff).report
        val byRef = report.findings.associate { it.ref to it.rule }
        byRef["dev.jdx.diffapi.Tuned#opened()"] shouldBe CompatRule.MEMBER_REMOVED
        byRef["dev.jdx.diffapi.Api#narrowed()"] shouldBe CompatRule.MEMBER_VISIBILITY_NARROWED
        // `Tuned#scratch` and `Tuned#guarded` are public on both sides, so they stay.
        (byRef.keys.any { it.startsWith("dev.jdx.diffapi.Stable#") }) shouldBe false
    }
}
