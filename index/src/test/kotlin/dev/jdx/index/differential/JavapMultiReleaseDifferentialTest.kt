package dev.jdx.index.differential

import dev.jdx.core.model.WarningCode
import dev.jdx.index.service.JdxService
import dev.jdx.testsupport.fixtures.MrFixtureJars
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Multi-release selection against `javap --multi-release` (issue #29).
 *
 * Plain `javap` reads the base entry. `javap --release` is not an option on
 * this JDK; the oracle flag is `--multi-release N`. `jdx` agrees with that
 * flag for every class the selected release can see, and both sides miss a
 * class that exists only in a higher version tree.
 *
 * Tagged `tier2`: one fixture jar, a handful of `javap` subprocesses.
 */
@Tag("tier2")
class JavapMultiReleaseDifferentialTest {

    private val api = "dev.jdx.fixtures.mr.Api"
    private val stable = "dev.jdx.fixtures.mr.Stable"
    private val since11 = "dev.jdx.fixtures.mr.Since11"
    private val only21 = "dev.jdx.fixtures.mr.Only21"

    @Test
    fun `each release agrees with javap --multi-release on the classes it can see`() {
        val javap = assumeJavap()
        val jar = MrFixtureJars.jar()
        val present = mapOf(
            8 to listOf(api, stable),
            11 to listOf(api, stable, since11),
            17 to listOf(api, stable, since11),
            21 to listOf(api, stable, since11, only21),
        )
        val absent = mapOf(
            8 to listOf(since11, only21),
            11 to listOf(only21),
            17 to listOf(only21),
            21 to emptyList(),
        )
        val failures = mutableListOf<String>()
        for ((release, classes) in present) {
            for (binary in classes) {
                when (val result = ServiceDifferential.compare(jar, binary, javap, release = release)) {
                    is ServiceDifferential.Comparison.Compared ->
                        if (!result.agrees) failures.add("release $release: " + ServiceDifferential.formatMismatch(result))
                    is ServiceDifferential.Comparison.Skipped ->
                        failures.add("release $release UNEXPECTED SKIP $binary: ${result.reason}")
                    is ServiceDifferential.Comparison.ServiceError ->
                        failures.add("release $release JDX ERROR $binary (exit ${result.exitCode}): ${result.message}")
                }
            }
            for (binary in absent.getValue(release)) {
                val jdx = members(jar, binary, release)
                if (jdx !is JdxService.ServiceOutcome.Failure || jdx.exitCode != 1) {
                    failures.add("release $release: expected $binary to be absent, got ${jdx.exitCode}")
                }
                val oracle = Javap.tryRun(javap, jar.absolutePath, binary, multiRelease = release)
                if (oracle !is Javap.RunOutcome.Unreadable) {
                    failures.add("release $release: javap --multi-release still read $binary ($oracle)")
                }
            }
        }
        failures shouldBe emptyList()
    }

    @Test
    fun `provenance names the versioned entry and base entries stay unlabelled`() {
        val jar = MrFixtureJars.jar()
        val running = Runtime.version().feature()
        assumeTrue(running >= 21, "this fixture's top variant is 21; the suite JDK is $running")

        val at17 = members(jar, api, 17)
        textOf(at17) shouldContain "addedIn17"
        textOf(at17) shouldNotContain "onlyBase"
        textOf(at17) shouldContain "release 17"
        jsonOf(at17) shouldContain "\"release\":17"
        warningOf(at17) shouldContain "for release 17"

        val since = members(jar, since11, 17)
        textOf(since) shouldContain "release 11"
        jsonOf(since) shouldContain "\"release\":11"

        val base = members(jar, stable, 17)
        textOf(base) shouldContain "marker"
        jsonOf(base) shouldNotContain "\"release\""

        val at8 = members(jar, api, 8)
        textOf(at8) shouldContain "onlyBase"
        textOf(at8) shouldNotContain "addedIn11"
        jsonOf(at8) shouldNotContain "\"release\""
        (at8 as JdxService.ServiceOutcome.MemberList).listing.warnings
            .none { it.code == WarningCode.MULTI_RELEASE_VARIANT } shouldBe true

        val implicit = members(jar, api, release = null)
        val explicit = members(jar, api, release = running)
        warningOf(implicit) shouldBe warningOf(explicit)
        warningOf(implicit) shouldContain "this JDK ($running)"
        jsonOf(implicit) shouldContain "\"release\":17"
    }

    @Test
    fun `a release below 8 exits 3`() {
        val jar = MrFixtureJars.jar()
        val outcome = members(jar, api, 7)
        outcome.exitCode shouldBe 3
        outcome.renderText(false) shouldContain "--release must be >= 8"
    }

    private fun members(jar: java.io.File, binary: String, release: Int?): JdxService.ServiceOutcome =
        JdxService.members(
            binary,
            JdxService.RootsSpec(
                jarSpecs = listOf(jar.absolutePath),
                includeJdk = false,
                release = release,
            ),
            declaredOnly = true,
        )

    private fun textOf(outcome: JdxService.ServiceOutcome): String = outcome.renderText(false)

    private fun jsonOf(outcome: JdxService.ServiceOutcome): String = outcome.toJson("members")

    private fun warningOf(outcome: JdxService.ServiceOutcome): String {
        val listing = (outcome as JdxService.ServiceOutcome.MemberList).listing
        return listing.warnings.single { it.code == WarningCode.MULTI_RELEASE_VARIANT }.message
    }

    private fun assumeJavap(): String {
        val javap = Javap.findJavap()
        assumeTrue(javap != null, "javap not on PATH")
        return javap!!
    }
}
