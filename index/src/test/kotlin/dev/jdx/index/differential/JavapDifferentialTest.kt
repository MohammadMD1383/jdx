package dev.jdx.index.differential

import dev.jdx.index.artifact.ArtifactLoader
import dev.jdx.index.artifact.ArtifactTestJars
import io.kotest.matchers.shouldBe
import java.io.File
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * The tier-2 `javap` differential harness (T-056, TESTING.md §5.1).
 *
 * For **every class** in the fixture corpus: parse `javap -p -s` into a
 * member set and diff it against
 * `jdx members --declared --access all --include-synthetic --json` for the
 * same class. Any disagreement is a bug in `jdx` (or a new `javap` quirk that
 * belongs in [JavapQuirks] with a comment — an unexplained allowlist entry is
 * a review blocker).
 *
 * Kotlin classes compare through `--view jvm` (T-037): by default they render
 * declarations, not `javap` names, by design — the JVM projection must still
 * equal `javap` member-for-member. Their JVM fidelity is also pinned by
 * `AsmClassReaderDifferentialTest`, and their command view by
 * `KotlinViewsServiceTest` plus goldens.
 *
 * Tagged `tier2` (docs/TESTING.md §2): every class costs a `javap` subprocess
 * plus a service query off disk. The seeded real-corpus sample lives in
 * [JavapCorpusSoakTest] (tier 3).
 */
@Tag("tier2")
class JavapDifferentialTest {

    private val binaryJar: File by lazy { ArtifactTestJars.binaryJar() }

    @Test
    fun `every fixture class agrees with javap member for member`() {
        val javap = assumeJavap()
        val failures = mutableListOf<String>()
        var compared = 0
        var jvmCompared = 0
        for (binaryName in fixtureClassNames()) {
            when (val result = ServiceDifferential.compare(binaryJar, binaryName, javap)) {
                is ServiceDifferential.Comparison.Compared -> {
                    compared++
                    if (result.jvmView) jvmCompared++
                    if (!result.agrees) failures.add(ServiceDifferential.formatMismatch(result))
                }
                is ServiceDifferential.Comparison.Skipped ->
                    failures.add("UNEXPECTED SKIP $binaryName: ${result.reason}")
                is ServiceDifferential.Comparison.ServiceError ->
                    failures.add("JDX ERROR $binaryName (exit ${result.exitCode}): ${result.message}")
            }
        }
        // The loop must have compared something: an empty corpus passing
        // vacuously would be a test that asserts nothing. The fixture ships
        // Kotlin classes, so the `--view jvm` path must have fired too.
        (compared > 0) shouldBe true
        (jvmCompared > 0) shouldBe true
        failures shouldBe emptyList<String>()
    }

    private fun fixtureClassNames(): List<String> {
        val names = mutableListOf<String>()
        ArtifactLoader.openJar(binaryJar.toPath()).use { root ->
            root.classEntryPaths().forEach { path ->
                names.add(path.removeSuffix(".class").replace('/', '.'))
            }
        }
        return names.sorted()
    }

    /** Locates `javap`, skipping gracefully with a clear message when absent. */
    private fun assumeJavap(): String {
        val javap = Javap.findJavap()
        assumeTrue(javap != null, "javap not found: skipping javap-agreement checks (TESTING.md §5.1)")
        return javap!!
    }
}
