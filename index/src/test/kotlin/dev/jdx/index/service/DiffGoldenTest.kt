package dev.jdx.index.service

import dev.jdx.core.diff.ApiSurface
import dev.jdx.core.diff.FailOn
import dev.jdx.core.diff.SeverityFilter
import dev.jdx.core.model.Origin
import dev.jdx.core.model.Provenance
import dev.jdx.index.service.JdxService.ArtifactSpec
import dev.jdx.index.service.JdxService.DiffOptions
import dev.jdx.index.service.JdxService.ServiceOutcome
import dev.jdx.testsupport.fixtures.DiffFixtureJars
import io.kotest.matchers.shouldBe
import java.io.File
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Goldens for the `jdx diff` report over the **real** diff fixture pair (issue #23).
 *
 * The adapter-level goldens in `cli` pin the command; these pin the *report* against
 * what `javac` and `kotlinc` actually emit, which is where a rendering change and a
 * reading change meet. Every one of these findings is read out of real class files, so a
 * golden diff here is a change in what `jdx` believes the two artifacts say.
 *
 * Two labels, one file per case, text and JSON. Hermetic: the pair is byte-deterministic
 * and nothing outside it is on the classpath, so these bytes are identical on every
 * machine. Rewrite with
 * `./gradlew :index:tier2Test -Pgolden.update=true` — then read every diff before
 * committing it. A golden updated without reading is a test deleted (TESTING.md §14).
 */
@Tag("tier2")
class DiffGoldenTest {

    private val v1: File get() = DiffFixtureJars.v1Jar()
    private val v2: File get() = DiffFixtureJars.v2Jar()

    /** The versioned jar names must never leak into a golden (the T-010 hermeticity rule). */
    private fun normalize(text: String): String = text
        .replace(v1.name, "diff-fixtures-v1.jar")
        .replace(v2.name, "diff-fixtures-v2.jar")

    private fun report(
        visibility: ApiSurface = ApiSurface.ALL,
        severityFilter: SeverityFilter = SeverityFilter.ALL,
        failOn: FailOn = FailOn.NONE,
        maxFindings: Int = Int.MAX_VALUE,
    ): dev.jdx.core.render.DiffReport {
        val outcome = JdxService.diff(
            old = ArtifactSpec(spec = v1.absolutePath),
            new = ArtifactSpec(spec = v2.absolutePath),
            options = DiffOptions(
                visibility = visibility,
                severityFilter = severityFilter,
                failOn = failOn,
                maxFindings = maxFindings,
            ),
        )
        (outcome is ServiceOutcome.Diff) shouldBe true
        val report = (outcome as ServiceOutcome.Diff).report
        // The pair is compiled with no debug-info differences and emits no warnings, so a
        // warning line here would be the reader degrading — worth seeing in a golden, not
        // worth pinning the real paths of.
        return report.copy(provenance = report.provenance)
    }

    @Test
    fun `the full report over the fixture pair is pinned, in text and in JSON`() {
        val cases = mapOf(
            "all" to report(),
            "all-severity-suspicious" to report(severityFilter = SeverityFilter.SUSPICIOUS),
            "all-severity-breaking" to report(severityFilter = SeverityFilter.BREAKING),
            "public-surface" to report(visibility = ApiSurface.PUBLIC),
            "limit-5" to report(maxFindings = 5),
            "fail-on-breaking" to report(failOn = FailOn.BREAKING),
        )
        val contents = buildMap {
            for ((name, report) in cases) {
                put("$name.txt", normalize(report.renderText()))
                put("$name.json", normalize(report.toJson(command = "diff")))
            }
        }
        dev.jdx.testsupport.golden.GoldenFiles.verifyAll(
            File("src/test/resources/golden/diff"),
            contents,
        )
    }

    @Test
    fun `a diff of the pair against itself is silent`() {
        val outcome = JdxService.diff(
            old = ArtifactSpec(spec = v1.absolutePath),
            new = ArtifactSpec(spec = v1.absolutePath),
            options = DiffOptions(visibility = ApiSurface.ALL),
        )
        val report = (outcome as ServiceOutcome.Diff).report
        report.identical shouldBe true
        report.exitCode shouldBe 0
        report.findings shouldBe emptyList()
        report.counts.total shouldBe 0
    }

    @Test
    fun `the pinned report names both artifacts as bytecode provenance`() {
        val report = report()
        // The labels are the *file names*, so the version string is scrubbed here too —
        // the same hermeticity rule the text and JSON cases follow.
        report.provenance.map { normalize(it.artifact) } shouldBe
            listOf("diff-fixtures-v1.jar", "diff-fixtures-v2.jar")
        report.provenance.map { it.origin } shouldBe listOf(Origin.BYTECODE, Origin.BYTECODE)
    }
}
