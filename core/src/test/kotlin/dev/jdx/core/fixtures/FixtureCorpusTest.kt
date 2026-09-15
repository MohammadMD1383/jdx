package dev.jdx.core.fixtures

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.io.File
import java.util.Calendar
import java.util.TimeZone
import java.util.zip.ZipFile

/**
 * Guards the `testfixtures` corpus itself (T-006): the jars exist, are byte-deterministic, are
 * self-describing via `@ExpectedMembers`, and prove D-017 (the static-initialiser marker is
 * absent even though every fixture was inspected two ways).
 *
 * Tier 2 (`@Tag("tier2")`, T-053): this suite shells out to `javap` per fixture class and
 * reads both jars end to end (~14 s) — far too slow for the tier-1 TDD loop. It runs in
 * `./gradlew check`, never in `./gradlew test`.
 *
 * ## Never loads a fixture class
 *
 * Every assertion here reads jar bytes or runs `javap` in a subprocess — `javap` parses class
 * files without running initialisers. If any test in this JVM loaded
 * `dev.jdx.fixtures.StaticInitMarker`, the marker test below would fail, and that failure would
 * be a real D-017 violation, not test noise.
 */
@Tag("tier2")
class FixtureCorpusTest {

    private val binaryJar: File by lazy { Fixtures.binaryJar() }
    private val sourcesJar: File by lazy { Fixtures.sourcesJar() }

    // Classes that cannot carry `@ExpectedMembers`, pinned exactly: anonymous and local classes
    // and enum constant bodies have no source declaration to attach an annotation to, and the
    // Kotlin file facade accepts no TYPE-targeted annotation. Anything else appearing here is a
    // review blocker; a new fixture with an anonymous class must extend this set consciously.
    private val expectedMembersExempt: Set<String> = setOf(
        "dev.jdx.fixtures.KotlinShapesKt",
        "dev.jdx.fixtures.Nesting\$1",
        "dev.jdx.fixtures.Nesting\$1Local",
        "dev.jdx.fixtures.TrafficLight\$1",
        "dev.jdx.fixtures.TrafficLight\$2",
        "dev.jdx.fixtures.TrafficLight\$3",
    )

    // `KotlinShapesKt` cannot carry the annotation (see above), so its truth is pinned here,
    // in declaration order, exactly as `javap -p` prints it. Same comparison, different home.
    private val fileFacadeExpected: List<String> = listOf(
        "public static final java.lang.String extensionGreeting(dev.jdx.fixtures.KotlinMembers)",
        "public static final <T> boolean isInstanceOf(java.lang.Object)",
    )

    @Test
    fun `binary and sources jars exist and are non-empty`() {
        binaryJar.isFile shouldBe true
        sourcesJar.isFile shouldBe true
        (binaryJar.length() > 0) shouldBe true
        (sourcesJar.length() > 0) shouldBe true
    }

    @Test
    fun `sources jar holds exactly the fixture sources`() {
        val entries = ZipFile(sourcesJar).use { zip ->
            zip.entries().asSequence().map { it.name }
                // META-INF/MANIFEST.MF is build-generated, not a fixture source.
                .filter { !it.endsWith("/") && !it.startsWith("META-INF/") }
                .sorted().toList()
        }
        entries shouldContainExactly listOf(
            "dev/jdx/fixtures/Annos.java",
            "dev/jdx/fixtures/CovariantOverrides.java",
            "dev/jdx/fixtures/ExpectedMembers.java",
            "dev/jdx/fixtures/Generics.java",
            "dev/jdx/fixtures/KotlinShapes.kt",
            "dev/jdx/fixtures/Nesting.java",
            "dev/jdx/fixtures/NoDebug.java",
            "dev/jdx/fixtures/PersonRecord.java",
            "dev/jdx/fixtures/SealedHierarchy.java",
            "dev/jdx/fixtures/StaticInitMarker.java",
            "dev/jdx/fixtures/TrafficLight.java",
            "dev/jdx/fixtures/VarargsAndModifiers.java",
        )
    }

    @Test
    fun `every jar entry carries the same fixed 1980 timestamp`() {
        // Byte-determinism (CLAUDE.md §2.5): reproducible archives fix every entry timestamp,
        // so rebuilding produces identical bytes. A single drifting entry breaks the promise.
        for (jar in listOf(binaryJar, sourcesJar)) {
            ZipFile(jar).use { zip ->
                val times = zip.entries().asSequence().map { it.time }.toSet()
                times.size shouldBe 1
                val calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
                    timeInMillis = times.single()
                }
                calendar.get(Calendar.YEAR) shouldBe 1980
            }
        }
    }

    @Test
    fun `every source-declared class carries ExpectedMembers and nothing else is exempt`() {
        val names = Fixtures.classNames(binaryJar)
        val annotated = names.filter { isAnnotated(Fixtures.classBytes(binaryJar, it)) }.toSet()
        val unannotated = (names.toSet() - annotated).sorted()

        // The exempt set is exact in both directions: no unexplained holes, no stale entries.
        unannotated shouldContainExactly expectedMembersExempt.sorted()
    }

    @Test
    fun `expected members agree with javap member for member`() {
        val javap = assumeJavap()
        val failures = mutableListOf<String>()
        for (binaryName in Fixtures.classNames(binaryJar)) {
            if (binaryName in expectedMembersExempt) continue
            val actual = memberLines(runJavap(javap, binaryJar, binaryName, verbose = false))
            val expected = expectedEntries(runJavap(javap, binaryJar, binaryName, verbose = true), binaryName)
            if (actual != expected) {
                failures.add(formatMismatch(binaryName, expected, actual))
            }
        }
        failures shouldBe emptyList<String>()
    }

    @Test
    fun `the kotlin file facade truth is pinned`() {
        val javap = assumeJavap()
        val actual = memberLines(
            runJavap(javap, binaryJar, "dev.jdx.fixtures.KotlinShapesKt", verbose = false),
        )
        actual shouldBe fileFacadeExpected
    }

    @Test
    fun `the static initialiser marker file is absent`() {
        // The class IS in the jar (or this test would prove nothing) and was inspected twice
        // above (bytes + javap) — yet nothing in this JVM may have initialised it (D-017).
        ("dev.jdx.fixtures.StaticInitMarker" in Fixtures.classNames(binaryJar)) shouldBe true
        Fixtures.staticInitMarkerFile().exists() shouldBe false
    }

    @Test
    fun `the no-debug class carries no line numbers or local variable tables`() {
        val bytes = Fixtures.classBytes(binaryJar, "dev.jdx.fixtures.NoDebug").toLatin1()
        bytes.contains("LineNumberTable") shouldBe false
        bytes.contains("LocalVariableTable") shouldBe false
    }

    // -- machinery -----------------------------------------------------------

    private fun isAnnotated(classBytes: ByteArray): Boolean =
        classBytes.toLatin1().contains("dev/jdx/fixtures/ExpectedMembers")

    private fun ByteArray.toLatin1(): String = String(this, Charsets.ISO_8859_1)

    /** Locates `javap`: the test runtime's JDK first, `PATH` as a fallback. Never throws. */
    private fun assumeJavap(): String {
        val homeJavap = File(System.getProperty("java.home"), "bin/javap")
        if (homeJavap.canExecute()) return homeJavap.absolutePath
        val onPath = try {
            val probe = ProcessBuilder("javap", "-version").redirectErrorStream(true).start()
            val exitedZero = probe.waitFor() == 0
            probe.inputStream.readBytes()
            exitedZero
        } catch (_: Exception) {
            false
        }
        assumeTrue(onPath, "javap not found: skipping javap-agreement checks (TESTING.md §5.1)")
        return "javap"
    }

    private fun runJavap(javap: String, jar: File, binaryName: String, verbose: Boolean): List<String> {
        // Flags before the class name: a trailing `-v` is parsed as a class name (L-018).
        val args = mutableListOf(javap, "-p")
        if (verbose) args.add("-v")
        args.addAll(listOf("-classpath", jar.absolutePath, binaryName))
        val process = ProcessBuilder(args).redirectErrorStream(true).start()
        val output = process.inputStream.readBytes().toString(Charsets.UTF_8)
        val exit = process.waitFor()
        check(exit == 0) { "javap failed for $binaryName:\n$output" }
        return output.lines()
    }

    /**
     * Member lines exactly as `javap -p` prints them, minus the trailing `;`: drops the
     * `Compiled from` header, the class declaration line and the class initializer, which the
     * annotation convention excludes (see `ExpectedMembers`).
     */
    private fun memberLines(javapOutput: List<String>): List<String> =
        javapOutput.map { it.trim() }
            .filter { it.isNotEmpty() && it != "}" && !it.startsWith("Compiled from") }
            .filter { !it.endsWith("{") && it != "static {};" }
            .map { it.removeSuffix(";") }

    /** The `@ExpectedMembers` values read back out of the class bytes via `javap -v`. */
    private fun expectedEntries(javapVerbose: List<String>, binaryName: String): List<String> {
        val annotationAt = javapVerbose.indexOfFirst {
            it.trim().startsWith("dev.jdx.fixtures.ExpectedMembers(")
        }
        check(annotationAt >= 0) { "no ExpectedMembers annotation found on $binaryName" }
        val valuesLine = javapVerbose.drop(annotationAt).firstOrNull { it.contains("value=[") }
            ?: error("no ExpectedMembers values found on $binaryName")
        return Regex("\"([^\"]*)\"").findAll(valuesLine).map { it.groupValues[1] }.toList()
    }

    private fun formatMismatch(binaryName: String, expected: List<String>, actual: List<String>): String {
        val width = maxOf(expected.size, actual.size)
        return buildString {
            appendLine("MISMATCH $binaryName (annotation vs javap -p):")
            for (i in 0 until width) {
                val left = expected.getOrNull(i) ?: "<missing>"
                val right = actual.getOrNull(i) ?: "<missing>"
                val mark = if (left == right) " " else "!"
                appendLine("$mark expected: $left")
                appendLine("$mark actual:   $right")
            }
        }
    }
}
