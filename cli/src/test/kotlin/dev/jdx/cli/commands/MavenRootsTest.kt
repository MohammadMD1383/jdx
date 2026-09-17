package dev.jdx.cli.commands

import dev.jdx.index.maven.MavenFetch
import dev.jdx.index.maven.MavenResolver
import dev.jdx.index.service.JdxService
import dev.jdx.index.workspace.InMemoryWorkspaceStore
import dev.jdx.index.workspace.WorkspaceDefinition
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Tests for `--coord`/`--fetch` root resolution (T-019): explicit coordinates
 * merge in front like `--jars`, stored workspace coords resolve behind the
 * workspace's jars, malformed values are usage errors, and absent artifacts
 * fail with the `--fetch` hint — never a stack trace.
 *
 * Hermetic by construction: an isolated store, a null environment and
 * discovery, and fabricated repositories under `@TempDir` holding a copy of
 * the fixture corpus jar. Tier 2: jar-shaped IO, no network.
 */
@Tag("tier2")
class MavenRootsTest {

    private fun fixtureJar(): Path {
        val dir = System.getProperty("jdx.fixturesDir")?.let { File(it) }
            ?: error("jdx.fixturesDir not set (cli build wires it; see cli/build.gradle.kts)")
        return dir.listFiles { file -> file.name.endsWith(".jar") && !file.name.contains("sources") }
            ?.sortedBy { it.name }?.firstOrNull()?.toPath()
            ?: error("no fixture binary jar in $dir")
    }

    private fun m2WithDemo(root: Path): Path {
        val versionDir = root.resolve("m2/com/example/demo/1.0")
        Files.createDirectories(versionDir)
        Files.copy(fixtureJar(), versionDir.resolve("demo-1.0.jar"))
        return root.resolve("m2")
    }

    private fun reposFor(root: Path, m2: Path): MavenResolver.Repositories =
        MavenResolver.Repositories(
            gradleFilesRoot = null,
            m2Repo = m2,
            fetchCacheRoot = root.resolve("cache"),
        )

    private fun resolve(
        root: Path,
        m2: Path,
        coords: List<String>,
        allowFetch: Boolean = false,
        workspace: String? = null,
        store: dev.jdx.index.workspace.WorkspaceStore = InMemoryWorkspaceStore(),
    ): ReadCommandSupport.RootsOrFailure = ReadCommandSupport.resolveRoots(
        jars = emptyList(),
        noJdk = true,
        workspace = workspace,
        store = store,
        getenv = { null },
        workingDir = root,
        cacheBase = root.resolve("auto"),
        gradleFilesRoot = null,
        m2Repo = null,
        discover = { _, _, _, _ -> null },
        coords = coords,
        allowFetch = allowFetch,
        mavenRepositories = reposFor(root, m2),
        mavenFetcher = MavenFetch.Fetcher { error("must not fetch") },
    )

    @Test
    fun `an explicit coord resolves to its jar and answers queries`(@TempDir root: Path) {
        val m2 = m2WithDemo(root)
        val resolved = resolve(root, m2, listOf("com.example:demo:1.0"))
        ((resolved is ReadCommandSupport.RootsOrFailure.Ready)) shouldBe true
        val roots = (resolved as ReadCommandSupport.RootsOrFailure.Ready).roots
        (roots.jarSpecs.size) shouldBe 1
        roots.jarSpecs.single() shouldContain "demo-1.0.jar"
        val outcome = JdxService.show("dev.jdx.fixtures.Generics", roots)
        (outcome.exitCode) shouldBe 0
        outcome.renderText() shouldContain "dev.jdx.fixtures.Generics"
    }

    @Test
    fun `a malformed coord is a usage error`(@TempDir root: Path) {
        val m2 = m2WithDemo(root)
        val resolved = resolve(root, m2, listOf("not-a-coordinate"))
        ((resolved is ReadCommandSupport.RootsOrFailure.Failed)) shouldBe true
        val outcome = (resolved as ReadCommandSupport.RootsOrFailure.Failed).outcome
        (outcome.exitCode) shouldBe 3
        outcome.renderText() shouldContain "group:artifact:version"
    }

    @Test
    fun `an absent coord without fetch fails naming --fetch`(@TempDir root: Path) {
        val m2 = m2WithDemo(root)
        val resolved = resolve(root, m2, listOf("com.example:missing:9.9.9"))
        ((resolved is ReadCommandSupport.RootsOrFailure.Failed)) shouldBe true
        val outcome = (resolved as ReadCommandSupport.RootsOrFailure.Failed).outcome
        (outcome.exitCode) shouldBe 5
        outcome.renderText() shouldContain "--fetch"
    }

    @Test
    fun `stored workspace coords resolve behind the workspace jars`(@TempDir root: Path) {
        val m2 = m2WithDemo(root)
        val store = InMemoryWorkspaceStore()
        store.save(WorkspaceDefinition("ws", listOf("other.jar"), false, listOf("com.example:demo:1.0")))
        val resolved = resolve(root, m2, emptyList(), workspace = "ws", store = store)
        // `other.jar` names nothing real, so resolution of the stored coord must
        // still succeed on its own — the jar failure surfaces at query time.
        // Here the stored coord resolves; the assertion is on its position.
        ((resolved is ReadCommandSupport.RootsOrFailure.Ready)) shouldBe true
        val roots = (resolved as ReadCommandSupport.RootsOrFailure.Ready).roots
        (roots.jarSpecs.size) shouldBe 2
        roots.jarSpecs[0] shouldBe "other.jar"
        roots.jarSpecs[1] shouldContain "demo-1.0.jar"
    }
}
