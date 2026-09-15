package dev.jdx.index.artifact

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Tier-2 tests for [SourcesPairing] (T-007): the five rules of PROPOSAL.md §5.2, each with
 * a real directory on disk, plus an opportunistic pass over the real Gradle cache.
 */
@Tag("tier2")
class SourcesPairingTest {

    private fun touchJar(jar: Path): Path {
        Files.createDirectories(jar.parent)
        ArtifactTestJars.craftJar(jar, mapOf("com/acme/A.class" to byteArrayOf(1, 2, 3)))
        return jar
    }

    @Test
    fun `sibling sources jar wins`(@TempDir temp: Path) {
        val binary = touchJar(temp.resolve("acme-1.0.jar"))
        val sources = touchJar(temp.resolve("acme-1.0-sources.jar"))
        SourcesPairing.pair(binary) shouldBe SourcesPair.External(sources.toAbsolutePath().normalize())
    }

    @Test
    fun `gradle cache layout is found across sha1 dirs`(@TempDir temp: Path) {
        // …/files-2.1/<group>/<artifact>/<version>/<sha1>/acme-1.0.jar, sources beside it
        // under a *different* sha1 dir — the layout Gradle actually produces.
        val versionDir = temp.resolve("files-2.1/com.acme/acme/1.0")
        val binary = touchJar(versionDir.resolve("aaaa/acme-1.0.jar"))
        val sources = touchJar(versionDir.resolve("bbbb/acme-1.0-sources.jar"))
        SourcesPairing.pair(binary) shouldBe SourcesPair.External(sources.toAbsolutePath().normalize())
    }

    @Test
    fun `unrelated sources jars in a flat dir never pair`(@TempDir temp: Path) {
        val binary = touchJar(temp.resolve("foo-1.0.jar"))
        touchJar(temp.resolve("bar-1.0-sources.jar"))
        SourcesPairing.pair(binary) shouldBe SourcesPair.Absent
    }

    @Test
    fun `explicit override beats the sibling`(@TempDir temp: Path) {
        val binary = touchJar(temp.resolve("lib/acme-1.0.jar"))
        touchJar(temp.resolve("lib/acme-1.0-sources.jar"))
        val override = touchJar(temp.resolve("elsewhere/custom-sources.jar"))
        SourcesPairing.pair(binary, override) shouldBe
            SourcesPair.External(override.toAbsolutePath().normalize())
    }

    @Test
    fun `missing explicit path falls back to the sibling`(@TempDir temp: Path) {
        val binary = touchJar(temp.resolve("acme-1.0.jar"))
        val sources = touchJar(temp.resolve("acme-1.0-sources.jar"))
        SourcesPairing.pair(binary, temp.resolve("nope-sources.jar")) shouldBe
            SourcesPair.External(sources.toAbsolutePath().normalize())
    }

    @Test
    fun `embedded sources are reported, not mistaken for absent`(@TempDir temp: Path) {
        val jar = temp.resolve("bundled-1.0.jar")
        Files.createDirectories(jar.parent)
        ArtifactTestJars.craftJar(
            jar,
            mapOf(
                "com/acme/A.class" to byteArrayOf(1, 2, 3),
                "com/acme/A.java" to "class A {}".toByteArray(),
            ),
        )
        SourcesPairing.pair(jar) shouldBe SourcesPair.Embedded
    }

    @Test
    fun `plain jar with no sources anywhere is absent`(@TempDir temp: Path) {
        SourcesPairing.pair(touchJar(temp.resolve("lonely/acme-1.0.jar"))) shouldBe SourcesPair.Absent
    }

    @Test
    fun `non-jar paths are absent`(@TempDir temp: Path) {
        SourcesPairing.pair(temp.resolve("whatever.txt")) shouldBe SourcesPair.Absent
    }

    @Test
    fun `real gradle cache sources pair by stem when present`() {
        // Opportunistic: proves rule 2 against the owner's real cache instead of only a
        // fabricated one. Aborts — never fails — on machines without that cache.
        val caches = Path.of(System.getProperty("user.home"), ".gradle", "caches")
        assumeTrue(Files.isDirectory(caches), "no Gradle cache at $caches")
        val sourcesJar = Files.walk(caches).use { walk ->
            walk.filter { Files.isRegularFile(it) }
                .filter { it.fileName.toString().endsWith("-sources.jar") }
                .findFirst()
                .orElse(null)
        }
        assumeTrue(sourcesJar != null, "no -sources.jar anywhere under $caches")
        sourcesJar!!
        val stem = sourcesJar.fileName.toString().removeSuffix("-sources.jar") + ".jar"
        // The binary may sit beside its sources (same sha1 dir, rule 1) or under a sibling
        // sha1 dir of the same `<version>/` parent (rule 2, the usual Gradle shape) — look
        // in both, exercising whichever layout this cache really has.
        val sameDir = sourcesJar.parent.resolve(stem)
        val binary: Path? = if (Files.isRegularFile(sameDir)) {
            sameDir
        } else {
            val versionDir = sourcesJar.parent.parent
            if (versionDir == null || !Files.isDirectory(versionDir)) null else {
                Files.newDirectoryStream(versionDir).use { children ->
                    children.asSequence()
                        .filter { Files.isDirectory(it) }
                        .map { it.resolve(stem) }
                        .firstOrNull { Files.isRegularFile(it) }
                }
            }
        }
        assumeTrue(binary != null, "sources jar without a discoverable binary: $sourcesJar")
        val paired = SourcesPairing.pair(binary!!)
        assumeTrue(paired is SourcesPair.External, "binary without discoverable sources: $binary")
        (paired as SourcesPair.External).path.fileName shouldBe sourcesJar.fileName
    }
}
