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
 */
object FixtureJars {

    /** System property carrying the fixture-jar directory into the test JVM. */
    const val FIXTURES_DIR_PROPERTY: String = "jdx.fixturesDir"

    /**
     * Directory holding the fixture jars: `testfixtures-<version>.jar` and
     * `testfixtures-<version>-sources.jar`.
     */
    fun fixturesDir(): File {
        System.getProperty(FIXTURES_DIR_PROPERTY)?.let { return File(it) }
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val candidate = File(dir, "testfixtures/build/libs")
            if (candidate.isDirectory) return candidate
            dir = dir.parentFile
        }
        error("fixture jars not found: set -D$FIXTURES_DIR_PROPERTY=<dir> or build :testfixtures first")
    }

    /** The compiled fixture jar (never the `-sources` jar). Exactly one must match. */
    fun binaryJar(dir: File = fixturesDir()): File = singleJar(dir, sources = false)

    /** The `-sources` jar with the exact sources every fixture class was compiled from. */
    fun sourcesJar(dir: File = fixturesDir()): File = singleJar(dir, sources = true)

    private fun singleJar(dir: File, sources: Boolean): File {
        val jars = dir.listFiles { file ->
            file.isFile && file.extension == "jar" &&
                file.name.startsWith("testfixtures-") &&
                file.name.endsWith("-sources.jar") == sources
        }?.toList().orEmpty()
        require(jars.size == 1) {
            "expected exactly one ${if (sources) "sources" else "binary"} fixture jar in $dir, found: $jars"
        }
        return jars.single()
    }

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
