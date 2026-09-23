package dev.jdx.index.kotlin

import dev.jdx.index.maven.MavenCoords
import dev.jdx.index.maven.MavenFetch
import dev.jdx.sources.KOTLIN_COMPILER_VERSION
import dev.jdx.sources.kotlinSidecarDir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.property.Arb
import io.kotest.property.arbitrary.filter
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * The Kotlin sidecar fetch (T-080, tier 2): the artifact table, the download
 * URLs, and [fetchKotlinSidecar] over injected fake fetchers — no test ever
 * touches the network. Filesystem-touching by construction, so tagged out of
 * the tier-1 loop.
 */
@Tag("tier2")
class KotlinSidecarFetchTest {

    /** Serves [jars] (file name → bytes) with matching `.sha1` bodies, like Maven Central. */
    private fun centralFetcher(jars: Map<String, ByteArray>): MavenFetch.Fetcher =
        MavenFetch.Fetcher { url ->
            val fileName = url.substringAfterLast("/")
            if (url.endsWith(".sha1")) {
                val jarName = fileName.removeSuffix(".sha1")
                jars[jarName]?.let { (MavenFetch.sha1Hex(it) + "  $jarName").toByteArray(Charsets.UTF_8) }
            } else {
                jars[fileName]
            }
        }

    /** Canned bytes per artifact, distinct so a swapped write would show. */
    private fun cannedJars(): Map<String, ByteArray> =
        KOTLIN_SIDECAR_ARTIFACTS.associate { it.fileName to "bytes-for-${it.fileName}".toByteArray(Charsets.UTF_8) }

    @Test
    fun `the set is the compiler plus its six runtime jars`() {
        val names = KOTLIN_SIDECAR_ARTIFACTS.map { it.fileName }
        names shouldBe listOf(
            "kotlin-compiler-embeddable-$KOTLIN_COMPILER_VERSION.jar",
            "kotlin-stdlib-$KOTLIN_COMPILER_VERSION.jar",
            "kotlin-script-runtime-$KOTLIN_COMPILER_VERSION.jar",
            "kotlin-reflect-1.6.10.jar",
            "kotlin-daemon-embeddable-$KOTLIN_COMPILER_VERSION.jar",
            "kotlinx-coroutines-core-jvm-1.8.0.jar",
            "annotations-13.0.jar",
        )
    }

    @Test
    fun `download urls follow the Maven Central layout`(@TempDir home: Path) {
        for (artifact in KOTLIN_SIDECAR_ARTIFACTS) {
            kotlinSidecarDownloadUrl(artifact, MavenCoords.CENTRAL_BASE_URL) shouldBe
                "${MavenCoords.CENTRAL_BASE_URL}${artifact.group.replace('.', '/')}/" +
                "${artifact.artifact}/${artifact.version}/${artifact.fileName}"
        }
        // A mirror base without a trailing slash joins identically.
        val first = KOTLIN_SIDECAR_ARTIFACTS.first()
        kotlinSidecarDownloadUrl(first, "https://repo.example.com/maven2") shouldBe
            "https://repo.example.com/maven2/${first.group.replace('.', '/')}/" +
                "${first.artifact}/${first.version}/${first.fileName}"
    }

    @Test
    fun `a full fetch installs every jar with exact bytes`(@TempDir home: Path) {
        val jars = cannedJars()

        val outcome = fetchKotlinSidecar(home, centralFetcher(jars))

        (outcome is KotlinSidecarOutcome.Ok) shouldBe true
        val report = (outcome as KotlinSidecarOutcome.Ok).report
        report.installed shouldBe KOTLIN_SIDECAR_ARTIFACTS.map { it.fileName }
        report.alreadyPresent shouldBe emptyList()
        val dir = kotlinSidecarDir(home)
        for ((name, bytes) in jars) {
            Files.readAllBytes(dir.resolve(name)) shouldBe bytes
        }
    }

    @Test
    fun `present jars are skipped without network`(@TempDir home: Path) {
        val dir = kotlinSidecarDir(home)
        Files.createDirectories(dir)
        for (artifact in KOTLIN_SIDECAR_ARTIFACTS) {
            Files.write(dir.resolve(artifact.fileName), "old".toByteArray(Charsets.UTF_8))
        }
        var calls = 0
        val counting = MavenFetch.Fetcher { _: String ->
            calls++
            null
        }

        val outcome = fetchKotlinSidecar(home, counting)

        (outcome is KotlinSidecarOutcome.Ok) shouldBe true
        val report = (outcome as KotlinSidecarOutcome.Ok).report
        report.installed shouldBe emptyList()
        report.alreadyPresent shouldBe KOTLIN_SIDECAR_ARTIFACTS.map { it.fileName }
        calls shouldBe 0
    }

    @Test
    fun `a re-run resumes after the jars already installed`(@TempDir home: Path) {
        val jars = cannedJars()
        val dir = kotlinSidecarDir(home)
        Files.createDirectories(dir)
        val firstTwo = KOTLIN_SIDECAR_ARTIFACTS.take(2)
        for (artifact in firstTwo) {
            Files.write(dir.resolve(artifact.fileName), jars.getValue(artifact.fileName))
        }

        val outcome = fetchKotlinSidecar(home, centralFetcher(jars))

        (outcome is KotlinSidecarOutcome.Ok) shouldBe true
        val report = (outcome as KotlinSidecarOutcome.Ok).report
        report.alreadyPresent shouldBe firstTwo.map { it.fileName }
        report.installed shouldBe KOTLIN_SIDECAR_ARTIFACTS.drop(2).map { it.fileName }
    }

    @Test
    fun `force re-downloads present jars`(@TempDir home: Path) {
        val dir = kotlinSidecarDir(home)
        Files.createDirectories(dir)
        val first = KOTLIN_SIDECAR_ARTIFACTS.first()
        Files.write(dir.resolve(first.fileName), "stale".toByteArray(Charsets.UTF_8))
        val jars = cannedJars()

        val outcome = fetchKotlinSidecar(home, centralFetcher(jars), force = true)

        (outcome is KotlinSidecarOutcome.Ok) shouldBe true
        Files.readAllBytes(dir.resolve(first.fileName)) shouldBe jars.getValue(first.fileName)
    }

    @Test
    fun `a checksum mismatch fails with nothing written for that artifact`(@TempDir home: Path) {
        val jars = cannedJars()
        val bad = MavenFetch.Fetcher { url: String ->
            when {
                url.endsWith("kotlin-stdlib-$KOTLIN_COMPILER_VERSION.jar.sha1") -> "0".repeat(40).toByteArray()
                else -> centralFetcher(jars).get(url)
            }
        }

        val outcome = fetchKotlinSidecar(home, bad)

        (outcome is KotlinSidecarOutcome.Failed) shouldBe true
        val message = (outcome as KotlinSidecarOutcome.Failed).message
        message shouldContain "kotlin-stdlib"
        message shouldContain "re-run to resume"
        Files.exists(kotlinSidecarDir(home).resolve("kotlin-stdlib-$KOTLIN_COMPILER_VERSION.jar")) shouldBe false
    }

    @Test
    fun `an unreachable repository fails naming the artifact and the repos`(@TempDir home: Path) {
        val outcome = fetchKotlinSidecar(
            home,
            MavenFetch.Fetcher { null },
            repoBaseUrls = listOf("https://repo.example.com/maven2"),
        )

        (outcome is KotlinSidecarOutcome.Failed) shouldBe true
        val message = (outcome as KotlinSidecarOutcome.Failed).message
        message shouldContain "kotlin-compiler-embeddable"
        message shouldContain "https://repo.example.com/maven2"
    }

    @Test
    fun `a throwing fetcher never throws the fetch`(@TempDir home: Path) {
        val outcome = fetchKotlinSidecar(home, MavenFetch.Fetcher { throw IllegalStateException("boom") })

        (outcome is KotlinSidecarOutcome.Failed) shouldBe true
    }

    @Test
    fun `hostile home segments never throw the fetch`() {
        runBlocking {
            checkAll(100, Arb.string().filter { it.none { c -> c.code == 0 } }) { segment ->
                val home = Path.of(System.getProperty("java.io.tmpdir"), "jdx-kt-fetch-$segment")
                try {
                    fetchKotlinSidecar(home, MavenFetch.Fetcher { null })
                } catch (e: Exception) {
                    throw AssertionError("fetch threw on $segment: $e")
                }
            }
        }
    }
}
