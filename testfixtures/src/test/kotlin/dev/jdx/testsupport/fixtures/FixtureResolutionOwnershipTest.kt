package dev.jdx.testsupport.fixtures

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Issue #66, step 3: the walk-up corpus resolver must exist in exactly **one** shared
 * place, plus core's one documented second copy — and the suites that used to carry
 * private copies must ask whether the corpus is there before resolving it.
 *
 * Why this needs a test rather than a code-review habit: the resolver is the seam the
 * *build wiring* feeds, so a copy that drifts from the wiring fails only on a machine where
 * the walk-up finds nothing — a clean CI runner, never the contributor's laptop. Four
 * copies is four chances to drift, and #66 is what that cost: three PIT modules whose
 * pre-scan could not go green, every week, until someone read the log.
 *
 * A repository-wide source scan is the honest way to pin "there is only one": a build-script
 * assertion could only check the wiring, and a behavioural test in one module cannot see a
 * copy in another. It is a text scan on purpose — the thing being forbidden is duplicated
 * *text*.
 *
 * The complementary check — that the build wiring really reaches a PIT minion — is the
 * `verifyPitestWiring` task in the root `build.gradle.kts`, which fails every `check`.
 */
@Tag("tier2")
class FixtureResolutionOwnershipTest {

    private val repoRoot: File = File(System.getProperty("user.dir")).parentFile

    /**
     * The marker is the resolver's *unique* fingerprint: walking up looking for
     * `testfixtures/build/libs`. Any copy of that string outside the two allowed files is a
     * resolver copy, by definition — a copy cannot resolve the corpus without it.
     */
    @Test
    fun `the walk-up corpus resolver lives in one shared helper and core's documented copy`() {
        inRepo {
            val copies = kotlinTestSources()
                .filter { it.repoPath() !in ALLOWED_RESOLVERS }
                .filter { file -> file.readText().contains(WALK_UP_MARKER) }
                .map { it.repoPath() }
                .sorted()

            assertEquals(
                emptyList<String>(),
                copies,
                "A private copy of the fixture-corpus resolver crept back in (#66). Call " +
                    "`dev.jdx.testsupport.fixtures.FixtureJars` from a module that already has " +
                    "`testImplementation(testFixtures(project(\":testfixtures\")))` instead — " +
                    "several copies of a seam the build feeds is what made the mutation tier " +
                    "fail only on clean checkouts.",
            )
        }
    }

    /**
     * Step 2 of #66: the three `sources` suites that used to resolve the corpus
     * optimistically are exactly the twenty tests that were red on a clean runner. They must
     * now gate on an availability probe, so a machine with no corpus reports a SKIP instead
     * of a red build that reads like a product defect. Named individually because a new
     * corpus-dependent suite is exactly the case that would regress this.
     */
    @Test
    fun `the suites that lost the red pre-scan gate on corpus availability`() {
        inRepo {
            SOURCES_SUITES_WITHOUT_CORPUS.forEach { path ->
                val file = File(repoRoot, path)
                // Red, not skipped, on a rename: a suite that moves out of the list must not
                // quietly take its regression pin with it. The message says what to do.
                assertTrue(
                    file.isFile,
                    "$path does not exist. If it moved or was renamed, update " +
                        "SOURCES_SUITES_WITHOUT_CORPUS — the pin travels with the suite.",
                )
                val text = file.readText()
                val gated = listOf("binaryCorpusAvailable", "sourcesCorpusAvailable")
                    .any { text.contains(it) }
                assertEquals(
                    true,
                    gated,
                    "$path resolves the fixture corpus with no availability check (#66). Gate " +
                        "it on `FixtureJars.sourcesCorpusAvailable()` + `assumeTrue`, so a " +
                        "machine with no corpus reports a SKIP.",
                )
            }
        }
    }

    /**
     * Every Kotlin test source in the repository, in both source sets.
     *
     * `src/testFixtures/kotlin` is scanned too, and deliberately: the canonical
     * `FixtureJars` lives *there*, so a source-set-shaped blind spot would let a copy hide
     * in the one module that owns the rule.
     */
    private fun kotlinTestSources(): List<File> =
        repoRoot.listFiles { file: File -> file.isDirectory && file.name != "build" }
            ?.flatMap { module ->
                TEST_SOURCE_DIRS.map { File(module, it) }
                    .filter { it.isDirectory }
                    .flatMap { root ->
                        root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
                    }
            }
            .orEmpty()

    /**
     * The repository-relative path with `/` separators.
     *
     * `File.path` uses `\` on Windows, so comparing it against a `/`-spelled constant
     * matches nothing there and the gate passes for the wrong reason. The `windows` CI job
     * caught exactly that: both *allowed* paths were reported as violations while the real
     * canonical one was invisible to the scan. `invariantSeparatorsPath` is the fix; a test
     * that means different things per OS is one nobody trusts.
     */
    private fun File.repoPath(): String = relativeTo(repoRoot).invariantSeparatorsPath

    /**
     * The scan reaches the whole repository, which only exists when the test runs from the
     * build (an IDE single-suite run has a different working directory) — a scan that found
     * nothing would be a silently vacuous pass.
     */
    private fun inRepo(block: () -> Unit) {
        assumeTrue(
            File(repoRoot, "settings.gradle.kts").isFile,
            "not running from the repository root: $repoRoot",
        )
        block()
    }

    private companion object {
        const val WALK_UP_MARKER = "testfixtures/build/libs"
        val TEST_SOURCE_DIRS = listOf("src/test/kotlin", "src/testFixtures/kotlin")
        val ALLOWED_RESOLVERS = setOf(
            "testfixtures/src/testFixtures/kotlin/dev/jdx/testsupport/fixtures/FixtureJars.kt",
            "core/src/test/kotlin/dev/jdx/core/fixtures/Fixtures.kt",
            // This file names the marker in order to search for it; it resolves nothing.
            "testfixtures/src/test/kotlin/dev/jdx/testsupport/fixtures/FixtureResolutionOwnershipTest.kt",
        )
        val SOURCES_SUITES_WITHOUT_CORPUS = listOf(
            "sources/src/test/kotlin/dev/jdx/sources/JavaBodiesSourcesTest.kt",
            "sources/src/test/kotlin/dev/jdx/sources/KotlinBodiesPsiTest.kt",
            "sources/src/test/kotlin/dev/jdx/sources/SourceRootTest.kt",
        )
    }
}
