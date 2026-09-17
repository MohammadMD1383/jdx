package dev.jdx.index.maven

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Tests for Maven coordinate resolution (T-019): local-cache precedence
 * (fetch cache → Gradle → `~/.m2`), opt-in fetching with checksum
 * verification, and the failure taxonomy (exit-3 shape vs exit-5 absence).
 *
 * Filesystem fixtures are fabricated under `@TempDir`; the one real jar used
 * (the fixture corpus) is copied in, never referenced by absolute path. Tier 2:
 * jar-shaped IO, no network (fetching runs over an injected fake fetcher).
 */
@Tag("tier2")
class MavenResolverTest {

    private fun fixtureJar(): Path {
        val dir = System.getProperty("jdx.fixturesDir")?.let { File(it) }
            ?: error("jdx.fixturesDir not set (index build wires it; see index/build.gradle.kts)")
        return dir.listFiles { file -> file.name.endsWith(".jar") && !file.name.contains("sources") }
            ?.sortedBy { it.name }?.firstOrNull()?.toPath()
            ?: error("no fixture binary jar in $dir")
    }

    private fun repositories(root: Path, repoBase: String = "http://unused.invalid/repo"): MavenResolver.Repositories =
        MavenResolver.Repositories(
            gradleFilesRoot = root.resolve("gradle"),
            m2Repo = root.resolve("m2"),
            fetchCacheRoot = root.resolve("cache"),
            repoBaseUrl = repoBase,
        )

    private fun fakeFetcher(files: Map<String, ByteArray>): MavenFetch.Fetcher =
        MavenFetch.Fetcher { url ->
            val base = url.removeSuffix(".sha1")
            val name = base.substringAfterLast('/')
            when {
                url.endsWith(".sha1") -> {
                    val bytes = files[name] ?: return@Fetcher null
                    "${MavenFetch.sha1Hex(bytes)}  $name\n".toByteArray(Charsets.UTF_8)
                }
                else -> files[name]
            }
        }

    @Test
    fun `resolves a binary and its sibling sources from an m2 layout`(@TempDir root: Path) {
        val jar = fixtureJar()
        val versionDir = root.resolve("m2/com/example/demo/1.0")
        Files.createDirectories(versionDir)
        Files.copy(jar, versionDir.resolve("demo-1.0.jar"))
        Files.copy(jar, versionDir.resolve("demo-1.0-sources.jar"))
        val outcome = MavenResolver.resolve("com.example:demo:1.0", false, repositories(root))
        ((outcome is MavenResolver.Outcome.Resolved)) shouldBe true
        val artifact = (outcome as MavenResolver.Outcome.Resolved).artifact
        artifact.binaryJar shouldBe versionDir.resolve("demo-1.0.jar").toAbsolutePath().normalize()
        artifact.sourcesJar shouldBe versionDir.resolve("demo-1.0-sources.jar").toAbsolutePath().normalize()
        (artifact.fetched) shouldBe false
    }

    @Test
    fun `prefers the exact jar in a Gradle files-cache directory`(@TempDir root: Path) {
        val versionDir = root.resolve("gradle/com.example/demo/1.0/hashdir")
        Files.createDirectories(versionDir)
        Files.write(versionDir.resolve("demo-1.0-extra.jar"), "extra".toByteArray())
        Files.write(versionDir.resolve("demo-1.0.jar"), "exact".toByteArray())
        Files.write(versionDir.resolve("demo-1.0-sources.jar"), "sources".toByteArray())
        val outcome = MavenResolver.resolve("com.example:demo:1.0", false, repositories(root))
        ((outcome is MavenResolver.Outcome.Resolved)) shouldBe true
        val artifact = (outcome as MavenResolver.Outcome.Resolved).artifact
        (artifact.binaryJar.fileName.toString()) shouldBe "demo-1.0.jar"
        // Sources are excluded from binary candidates but still pair as siblings.
        (artifact.sourcesJar?.fileName?.toString()) shouldBe "demo-1.0-sources.jar"
    }

    @Test
    fun `fetch cache wins over Gradle which wins over m2`(@TempDir root: Path) {
        val repos = repositories(root)
        val gradleDir = root.resolve("gradle/com.example/demo/1.0/h")
        val m2Dir = root.resolve("m2/com/example/demo/1.0")
        Files.createDirectories(gradleDir)
        Files.createDirectories(m2Dir)
        Files.write(m2Dir.resolve("demo-1.0.jar"), "m2".toByteArray())
        Files.write(gradleDir.resolve("demo-1.0.jar"), "gradle".toByteArray())
        var resolved = MavenResolver.resolve("com.example:demo:1.0", false, repos)
        var binary = (resolved as MavenResolver.Outcome.Resolved).artifact.binaryJar
        (Files.readAllBytes(binary).toString(Charsets.UTF_8)) shouldBe "gradle"
        val cacheDir = root.resolve("cache/com/example/demo/1.0")
        Files.createDirectories(cacheDir)
        Files.write(cacheDir.resolve("demo-1.0.jar"), "cache".toByteArray())
        resolved = MavenResolver.resolve("com.example:demo:1.0", false, repos)
        binary = (resolved as MavenResolver.Outcome.Resolved).artifact.binaryJar
        (Files.readAllBytes(binary).toString(Charsets.UTF_8)) shouldBe "cache"
    }

    @Test
    fun `an invalid coordinate fails without a fetch hint`(@TempDir root: Path) {
        val outcome = MavenResolver.resolve("not-a-coordinate", false, repositories(root))
        ((outcome is MavenResolver.Outcome.Unresolved)) shouldBe true
        val unresolved = outcome as MavenResolver.Outcome.Unresolved
        unresolved.message shouldContain "group:artifact:version"
        (unresolved.fetchHint) shouldBe false
    }

    @Test
    fun `a locally absent coordinate without fetch names --fetch`(@TempDir root: Path) {
        val outcome = MavenResolver.resolve("com.example:demo:9.9.9", false, repositories(root))
        ((outcome is MavenResolver.Outcome.Unresolved)) shouldBe true
        val unresolved = outcome as MavenResolver.Outcome.Unresolved
        unresolved.message shouldContain "--fetch"
        (unresolved.fetchHint) shouldBe true
    }

    @Test
    fun `fetch downloads the binary and sources into the cache layout`(@TempDir root: Path) {
        val binaryBytes = "fetched-binary".toByteArray(Charsets.UTF_8)
        val sourcesBytes = "fetched-sources".toByteArray(Charsets.UTF_8)
        val fetcher = fakeFetcher(
            mapOf("demo-1.0.jar" to binaryBytes, "demo-1.0-sources.jar" to sourcesBytes),
        )
        val outcome = MavenResolver.resolve("com.example:demo:1.0", true, repositories(root), fetcher)
        ((outcome is MavenResolver.Outcome.Resolved)) shouldBe true
        val artifact = (outcome as MavenResolver.Outcome.Resolved).artifact
        (artifact.fetched) shouldBe true
        artifact.binaryJar shouldBe
            root.resolve("cache/com/example/demo/1.0/demo-1.0.jar").toAbsolutePath().normalize()
        artifact.sourcesJar shouldBe
            root.resolve("cache/com/example/demo/1.0/demo-1.0-sources.jar").toAbsolutePath().normalize()
        // A second resolution hits the cache — no fetch needed anymore.
        val again = MavenResolver.resolve(
            "com.example:demo:1.0",
            false,
            repositories(root),
            MavenFetch.Fetcher { error("must not fetch") },
        )
        ((again is MavenResolver.Outcome.Resolved)) shouldBe true
        ((again as MavenResolver.Outcome.Resolved).artifact.fetched) shouldBe false
    }

    @Test
    fun `fetch degrades to bytecode when sources are absent upstream`(@TempDir root: Path) {
        val binaryBytes = "fetched-binary".toByteArray(Charsets.UTF_8)
        val outcome = MavenResolver.resolve(
            "com.example:demo:1.0",
            true,
            repositories(root),
            fakeFetcher(mapOf("demo-1.0.jar" to binaryBytes)),
        )
        ((outcome is MavenResolver.Outcome.Resolved)) shouldBe true
        val artifact = (outcome as MavenResolver.Outcome.Resolved).artifact
        (artifact.sourcesJar == null) shouldBe true
    }

    @Test
    fun `fetch fails when the checksum is rejected`(@TempDir root: Path) {
        val fetcher = MavenFetch.Fetcher { url ->
            if (url.endsWith("demo-1.0.jar")) "tampered".toByteArray(Charsets.UTF_8)
            else "0".repeat(40).toByteArray(Charsets.UTF_8)
        }
        val outcome = MavenResolver.resolve("com.example:demo:1.0", true, repositories(root), fetcher)
        ((outcome is MavenResolver.Outcome.Unresolved)) shouldBe true
        // Nothing half-written may read as a hit afterwards.
        (Files.exists(root.resolve("cache/com/example/demo/1.0/demo-1.0.jar"))) shouldBe false
    }

    @Test
    fun `resolveAll stops at the first failure`(@TempDir root: Path) {
        val versionDir = root.resolve("m2/com/example/demo/1.0")
        Files.createDirectories(versionDir)
        Files.write(versionDir.resolve("demo-1.0.jar"), "ok".toByteArray())
        val outcome = MavenResolver.resolveAll(
            listOf("com.example:demo:1.0", "bogus"),
            false,
            repositories(root),
        )
        ((outcome is MavenResolver.ResolveAllOutcome.Failed)) shouldBe true
        (outcome as MavenResolver.ResolveAllOutcome.Failed).message shouldContain "group:artifact:version"
    }
}
