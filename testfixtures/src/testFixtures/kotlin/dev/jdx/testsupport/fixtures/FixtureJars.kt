package dev.jdx.testsupport.fixtures

import java.io.File
import java.util.zip.ZipFile

/**
 * Shared fixture-jar resolution (T-063): resolves the `testfixtures` corpus jars
 * (T-006) with no hard-coded absolute paths. This is the single canonical copy —
 * golden suites in `index` and `cli` resolve jars and class names through here.
 *
 * The Gradle build wires the concrete directory in as the [FIXTURES_DIR_PROPERTY]
 * system property (see `<module>/build.gradle.kts` in `core`/`index`/`cli`); a run
 * without that property falls back to searching upward from the working directory.
 * Either way the result is computed, never a literal.
 *
 * ## The jar-count trap (L-029)
 *
 * [binaryJar]/[sourcesJar] assert **exactly one** match and refuse to guess. The
 * `testFixtures` jar produced by this very module defaults into `build/libs`, where
 * it would sit next to the fixture jars and look like a second binary jar — it is
 * redirected to `build/test-fixtures-libs` in `testfixtures/build.gradle.kts`. If
 * you ever see "expected exactly one … found: [...]", suspect that redirect first.
 *
 * ## The rule that matters here (D-017)
 *
 * Fixture classes are **never loaded into the test JVM** — only read as bytes out
 * of the jar. `dev.jdx.fixtures.StaticInitMarker` writes a marker file from its
 * static initialiser, so even `Class.forName` on it would ruin the read-only
 * proof. Helpers here deal in [File] and [ByteArray]; [classBytes] is a zip read,
 * and corpus tests consult `javap` (which parses class files without running
 * initialisers) rather than reflection.
 *
 * ## Ask before you resolve (issue #66)
 *
 * A suite that needs the corpus should gate on [binaryCorpusAvailable] /
 * [sourcesCorpusAvailable] and `assumeTrue`, not resolve and catch. A missing corpus
 * is a property of the *machine*, not a defect: it deserves a visible SKIP, not a red
 * build whose stack trace points at product code. That distinction is what #66 was —
 * three PIT modules whose pre-scan could not go green on a clean checkout.
 *
 * This is the **only** copy of the walk-up resolution. `sources` and `index` used to
 * carry private copies (three and one respectively); they call this now. `core` keeps
 * one of its own for a real reason (its test source set must not depend on a project),
 * and `FixturesTest` pins the same behaviour. Do not add another.
 */
object FixtureJars {

    /** System property carrying the fixture-jar directory into the test JVM. */
    const val FIXTURES_DIR_PROPERTY: String = "jdx.fixturesDir"

    /**
     * Directory holding the fixture jars: `testfixtures-<version>.jar` and
     * `testfixtures-<version>-sources.jar`.
     */
    fun fixturesDir(): File =
        fixturesDirOrNull() ?: error(
            "fixture jars not found: set -D$FIXTURES_DIR_PROPERTY=<dir> or build :testfixtures first",
        )

    /**
     * The same directory, or `null` when no corpus can be located — the non-throwing
     * twin of [fixturesDir], so a caller can *ask* instead of catching.
     *
     * Prefer the [binaryCorpusAvailable] / [sourcesCorpusAvailable] probes: this returns a
     * directory the system property names even when that directory holds no jars, which is
     * the state a half-built corpus is in.
     */
    fun fixturesDirOrNull(): File? {
        System.getProperty(FIXTURES_DIR_PROPERTY)?.let { return File(it) }
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val candidate = File(dir, "testfixtures/build/libs")
            if (candidate.isDirectory) return candidate
            dir = dir.parentFile
        }
        return null
    }

    /**
     * Whether the compiled corpus jar is really there and unambiguously named — i.e. a test
     * that reads class bytes from it can run.
     *
     * A missing corpus is a property of the *machine*, not a defect, so a suite that needs
     * one should `assumeTrue(FixtureJars.binaryCorpusAvailable(), "…")` and report a skip.
     * The alternative — resolving optimistically and letting [binaryJar] throw — turns one
     * missing environment detail into a red suite, which is how issue #66 turned a nightly
     * red instead of a loud, honest skip. The Gradle build wires the corpus into every task
     * that needs it (`testfixtures/build.gradle.kts` + the root `pitest` wiring), so under
     * `./gradlew` these are always true; they matter for a bare IDE or JUnit run.
     */
    fun binaryCorpusAvailable(): Boolean = resolves(sources = false)

    /** As [binaryCorpusAvailable], for the `-sources` jar. */
    fun sourcesCorpusAvailable(): Boolean = resolves(sources = true)

    private fun resolves(sources: Boolean): Boolean {
        val dir = fixturesDirOrNull() ?: return false
        return runCatching { singleJar(dir, sources) }.isSuccess
    }

    /** The compiled fixture jar (never the `-sources` jar). Exactly one must match. */
    fun binaryJar(dir: File = fixturesDir()): File = singleJar(dir, sources = false)

    /** The `-sources` jar with the exact sources every fixture class was compiled from. */
    fun sourcesJar(dir: File = fixturesDir()): File = singleJar(dir, sources = true)

    private fun singleJar(dir: File, sources: Boolean): File {
        val jars = matchingJars(dir, sources)
        require(jars.size == 1) {
            "expected exactly one ${if (sources) "sources" else "binary"} fixture jar in $dir, found: $jars"
        }
        return jars.single()
    }

    private fun matchingJars(dir: File, sources: Boolean): List<File> =
        dir.listFiles { file ->
            file.isFile && file.extension == "jar" &&
                file.name.startsWith("testfixtures-") &&
                file.name.endsWith("-sources.jar") == sources
        }?.toList().orEmpty()

    /** Binary names (`com.example.Outer$Inner`) of every class in [jar]. Sorted. */
    fun classNames(jar: File): List<String> =
        ZipFile(jar).use { zip ->
            zip.entries().asSequence()
                .map { it.name }
                .filter { it.endsWith(".class") && it != "module-info.class" }
                .map { it.removeSuffix(".class").replace('/', '.') }
                .sorted()
                .toList()
        }

    /**
     * Raw bytes of one class file out of [jar] — a zip read, never a class load (D-017).
     *
     * @param binaryName e.g. `dev.jdx.fixtures.Nesting$Inner`.
     */
    fun classBytes(jar: File, binaryName: String): ByteArray {
        val entry = binaryName.replace('.', '/') + ".class"
        ZipFile(jar).use { zip ->
            val zipEntry = zip.getEntry(entry)
                ?: error("no such class in ${jar.name}: $binaryName")
            return zip.getInputStream(zipEntry).readBytes()
        }
    }
}
