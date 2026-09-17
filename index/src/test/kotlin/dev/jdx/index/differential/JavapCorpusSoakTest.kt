package dev.jdx.index.differential

import dev.jdx.index.artifact.ArtifactLoader
import io.kotest.matchers.shouldBe
import java.io.File
import java.nio.file.Files
import kotlin.io.path.isRegularFile
import kotlin.random.Random
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * The tier-3 `javap` differential over the real local jar corpus (T-056,
 * TESTING.md §8).
 *
 * The fixture corpus proves agreement on shapes we imagined; this suite proves
 * it on shapes we did not: a **seeded random sample** of classes from real
 * jars (compilers, build tools, obfuscators) is diffed exactly like the tier-2
 * suite. Sampling is deterministic for a fixed seed — a failure prints the
 * seed, and re-running with it reproduces the same sample
 * (`-Djdx.soakSeed=<n>` overrides the default).
 *
 * Tagged `soak`, never `tier2` (docs/TESTING.md §2): it needs the local jar
 * corpus and takes minutes. Runs via `./gradlew soak [-Pcorpus=<dir>]`; the
 * `soakTest` task hands the directory in as `-Djdx.corpusDir`, so a
 * contributor without the owner's cache points it at their own `~/.m2`.
 * Skips — never fails — when the corpus dir or `javap` is absent.
 *
 * Classes neither side can read honestly (corrupt entries, class files newer
 * than the running JDK, names the model cannot represent) are recorded as
 * skips with a reason histogram, not failures: this suite owns *agreement
 * where both sides answer*, and degrades exactly like the tool does.
 */
@Tag("soak")
class JavapCorpusSoakTest {

    private companion object {
        const val DEFAULT_SEED: Long = 20260917L
        const val MAX_JARS: Int = 30
        const val CLASSES_PER_JAR: Int = 4
    }

    @Test
    fun `seeded corpus sample agrees with javap member for member`() {
        val javap = Javap.findJavap()
        assumeTrue(javap != null, "javap not found: skipping javap-agreement checks (TESTING.md §5.1)")
        val corpusDir = File(
            System.getProperty("jdx.corpusDir")
                ?: "${System.getProperty("user.home")}/.gradle/caches",
        )
        assumeTrue(
            corpusDir.isDirectory,
            "corpus dir ${corpusDir.absolutePath} absent: skipping corpus soak " +
                "(TESTING.md §8); point it at real jars with -Pcorpus=<dir>",
        )
        val seed = System.getProperty("jdx.soakSeed")?.toLongOrNull() ?: DEFAULT_SEED
        val random = Random(seed)

        val jars = collectJars(corpusDir, random)
        assumeTrue(
            jars.isNotEmpty(),
            "no readable jars under ${corpusDir.absolutePath}: skipping corpus soak (-Pcorpus=<dir>)",
        )

        val failures = mutableListOf<String>()
        val skipReasons = mutableMapOf<String, Int>()
        var compared = 0
        for (jar in jars) {
            for (binaryName in sampleClasses(jar, random)) {
                val result = try {
                    ServiceDifferential.compare(jar, binaryName, javap!!)
                } catch (thrown: Exception) {
                    failures.add(
                        "THREW ${jar.name} $binaryName: " +
                            "${thrown.javaClass.simpleName}: ${thrown.message}",
                    )
                    continue
                }
                when (result) {
                    is ServiceDifferential.Comparison.Compared -> {
                        compared++
                        if (!result.agrees) failures.add(ServiceDifferential.formatMismatch(result))
                    }
                    // Exit 5 is the tool's honest "cannot read this artifact"
                    // (corrupt entry, future class version, unmodellable name):
                    // a skip with a counted reason, never a failure.
                    is ServiceDifferential.Comparison.ServiceError ->
                        if (result.exitCode == 5) {
                            skipReasons["jdx-unreadable (exit 5)"] =
                                (skipReasons["jdx-unreadable (exit 5)"] ?: 0) + 1
                        } else {
                            failures.add(
                                "JDX ERROR ${result.binaryName} " +
                                    "(exit ${result.exitCode}): ${result.message}",
                            )
                        }
                    is ServiceDifferential.Comparison.Skipped ->
                        skipReasons[result.reason.substringBefore(":")] =
                            (skipReasons[result.reason.substringBefore(":")] ?: 0) + 1
                }
            }
        }

        println(
            "SOAK javap-differential seed=$seed jars=${jars.size} compared=$compared " +
                "skipped=${skipReasons.values.sum()} failed=${failures.size}",
        )
        skipReasons.entries.sortedByDescending { it.value }.forEach { (reason, count) ->
            println("SOAK skip [$count x] $reason")
        }
        (compared > 0) shouldBe true
        failures shouldBe emptyList<String>()
    }

    /**
     * Candidate jars in deterministic pre-shuffle order, shuffled with the
     * run seed, capped at [MAX_JARS]. Files carrying auxiliary artifacts
     * (sources, javadoc) hold no classes and are excluded by name; jars that
     * fail to open at all are dropped here — they cannot yield samples.
     */
    private fun collectJars(corpusDir: File, random: Random): List<File> {
        val found = mutableListOf<File>()
        Files.walk(corpusDir.toPath()).use { walk ->
            walk.filter { path ->
                path.isRegularFile() && path.fileName.toString().endsWith(".jar") &&
                    !path.fileName.toString().endsWith("-sources.jar") &&
                    !path.fileName.toString().endsWith("-javadoc.jar")
            }.forEach { found.add(it.toFile()) }
        }
        return found.sortedBy { it.absolutePath }.shuffled(random).take(MAX_JARS)
    }

    /**
     * Up to [CLASSES_PER_JAR] class names from one jar, seeded-shuffled. Names
     * come from [ArtifactLoader] — the same entry set the service queries see —
     * so multi-release versioned entries (`META-INF/versions/11/...`, which no
     * binary name addresses) and rejected traversal entries are never sampled.
     * A jar that will not open at all contributes no samples.
     */
    private fun sampleClasses(jar: File, random: Random): List<String> {
        return try {
            ArtifactLoader.openJar(jar.toPath()).use { root ->
                root.classEntryPaths()
                    .map { it.removeSuffix(".class").replace('/', '.') }
                    .shuffled(random)
                    .take(CLASSES_PER_JAR)
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
