package dev.jdx.core.fixtures

import java.io.File
import java.util.zip.ZipFile

/**
 * Resolves the `testfixtures` corpus jars (T-006) with no hard-coded absolute paths.
 *
 * The Gradle build wires the concrete directory in as the `jdx.fixturesDir` system property
 * (see `core/build.gradle.kts`); an IDE run without that property falls back to searching
 * upward from the working directory. Either way the result is computed, never a literal.
 *
 * ## Relationship to the shared `FixtureJars` helper (T-063)
 *
 * `dev.jdx.testsupport.fixtures.FixtureJars` (testfixtures' `testFixtures` source set) is the
 * canonical copy for golden suites in other modules. This object does **not** delegate to it:
 * core's test source set is deliberately dependency-light (property testing only — T-055),
 * and the D-017 marker helpers below are part of the T-006 corpus contract, which lives with
 * core's corpus tests. Resolution logic here is expected to mirror `FixtureJars` exactly;
 * if you change one, change both (the `FixturesTest` assertions pin the same behaviour).
 *
 * ## The rule that matters here (D-017)
 *
 * Fixture classes are **never loaded into the test JVM** — only read as bytes out of the jar.
 * [StaticInitMarker][dev.jdx.fixtures.StaticInitMarker] writes a marker file from its static
 * initialiser, so even `Class.forName` on it would ruin the read-only proof. Helpers here deal
 * in [File] and [ByteArray]; [classBytes] is a zip read, and the corpus tests consult `javap`
 * (which parses class files without running initialisers) rather than reflection.
 */
object Fixtures {

    /** Name of the marker file [dev.jdx.fixtures.StaticInitMarker] writes on initialisation. */
    const val STATIC_INIT_MARKER_NAME: String = "jdx-fixture-static-init-marker"

    /**
     * Directory holding the fixture jars: `testfixtures-<version>.jar` and
     * `testfixtures-<version>-sources.jar`.
     */
    fun fixturesDir(): File {
        System.getProperty("jdx.fixturesDir")?.let { return File(it) }
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val candidate = File(dir, "testfixtures/build/libs")
            if (candidate.isDirectory) return candidate
            dir = dir.parentFile
        }
        error("fixture jars not found: set -Djdx.fixturesDir=<dir> or build :testfixtures first")
    }

    /** The compiled fixture jar (never the `-sources` jar). Exactly one must match. */
    fun binaryJar(dir: File = fixturesDir()): File = singleJar(dir, false)

    /** The `-sources` jar with the exact sources every fixture class was compiled from. */
    fun sourcesJar(dir: File = fixturesDir()): File = singleJar(dir, true)

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

    /** Binary names (`com.example.Outer.Inner`) of every class in [jar]. Sorted. */
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

    /**
     * The marker file whose existence proves a test JVM initialised
     * [dev.jdx.fixtures.StaticInitMarker]. Computed from the same formula as the fixture's
     * `MARKER_PATH`, duplicated here deliberately: referencing the fixture's field would load
     * the class and create the file this helper exists to watch for.
     */
    fun staticInitMarkerFile(): File =
        File(System.getProperty("java.io.tmpdir"), STATIC_INIT_MARKER_NAME)
}
