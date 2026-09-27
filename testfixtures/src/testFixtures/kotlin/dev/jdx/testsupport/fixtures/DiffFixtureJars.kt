package dev.jdx.testsupport.fixtures

import java.io.File

/**
 * The `jdx diff` fixture pair: two *versions* of the same package, built by
 * `:testfixtures:diffV1Jar` / `diffV2Jar` into `build/diff-fixtures/`
 * (issue #23, `docs/TESTING.md` §11.2).
 *
 * Two traps, both learned the hard way in the main corpus:
 *
 * - **Not `build/libs`, and not a `testfixtures-` name.** [FixtureJars.singleJar],
 *   core's `Fixtures.singleJar` and `ArtifactTestJars.binaryJar` all
 *   `require(jars.size == 1)` over that directory filtered by that prefix, so a second
 *   jar there turns three unrelated assertions into `IllegalArgumentException`. The pair
 *   has its own directory *and* its own base name, so a collision is impossible either
 *   way — the same trick `testFixturesJar.destinationDirectory` uses.
 * - **Both jars are byte-deterministic** (the module sets
 *   `isPreserveFileTimestamps = false` for every archive), or a golden over the pair goes
 *   flaky. No `-sources.jar`: a diff reads bytecode only.
 *
 * The pair self-describes through a **rule-coverage** test rather than
 * `@ExpectedMembers`: what these fixtures exist to prove is that every rule in
 * `core/diff/CompatRule.kt` fires against real `javac` output, and that is a property of
 * the *pair*, which no per-class annotation can express.
 */
object DiffFixtureJars {

    /** System property carrying the diff-fixture directory into the test JVM. */
    const val DIR_PROPERTY: String = "jdx.diffFixturesDir"

    /** The compiled v1 jar (the baseline of every comparison). */
    fun v1Jar(dir: File = dir()): File = singleJar(dir, "v1")

    /** The compiled v2 jar (the candidate of every comparison). */
    fun v2Jar(dir: File = dir()): File = singleJar(dir, "v2")

    private fun singleJar(dir: File, version: String): File {
        val jars = dir.listFiles { file ->
            file.isFile && file.extension == "jar" &&
                file.name.startsWith("diff-fixtures-$version-")
        }?.toList().orEmpty()
        require(jars.size == 1) {
            "expected exactly one diff-fixtures-$version jar in $dir, found: $jars " +
                "(build :testfixtures:diff${version.uppercase()}Jar)"
        }
        return jars.single()
    }

    /**
     * The directory, from the system property when the consuming module's build wired
     * it in, else by walking up from the working directory looking for
     * `testfixtures/build/diff-fixtures` — the same resolution and the same rationale as
     * [FixtureJars.fixturesDir], which exists so no test hard-codes an absolute path.
     */
    fun dir(): File {
        System.getProperty(DIR_PROPERTY)?.let { return File(it) }
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val candidate = File(dir, "testfixtures/build/diff-fixtures")
            if (candidate.isDirectory) return candidate
            dir = dir.parentFile
        }
        error(
            "diff fixture jars not found: set -D$DIR_PROPERTY=<dir> or build " +
                ":testfixtures:diffV1Jar :testfixtures:diffV2Jar first",
        )
    }
}
