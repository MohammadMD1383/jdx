package dev.jdx.index.workspace

import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * The auto-discovery on-disk cache (T-016, tier 2): derived workspaces persist under
 * `~/.cache/jdx/auto/<hash>.toml` with a fingerprint sidecar, and any build-file
 * change — or any corruption — invalidates the entry back to "re-derive".
 */
@Tag("tier2")
class ProjectCacheTest {

    private fun projectWithBuildFile(temp: Path): Path {
        val root = temp.resolve("proj").also { Files.createDirectories(it) }
        Files.writeString(root.resolve("settings.gradle"), "rootProject.name = \"demo\"\n")
        return root
    }

    private fun definitionFor(hash: String): WorkspaceDefinition =
        WorkspaceDefinition(name = hash, jars = listOf("/repo/build/classes", "/repo/dep.jar"))

    @Test
    fun `save then load round-trips`(@TempDir temp: Path) {
        val root = projectWithBuildFile(temp)
        val cache = temp.resolve("auto")
        val hash = ProjectDiscovery.projectHash(root)
        ProjectCache.save(cache, definitionFor(hash), ProjectDiscovery.computeFingerprint(root))
        ProjectCache.load(cache, hash, root) shouldBe definitionFor(hash)
    }

    @Test
    fun `a build-file change invalidates the entry`(@TempDir temp: Path) {
        val root = projectWithBuildFile(temp)
        val cache = temp.resolve("auto")
        val hash = ProjectDiscovery.projectHash(root)
        ProjectCache.save(cache, definitionFor(hash), ProjectDiscovery.computeFingerprint(root))
        Files.writeString(root.resolve("settings.gradle"), "rootProject.name = \"renamed\"\n")
        ProjectCache.load(cache, hash, root) shouldBe null
    }

    @Test
    fun `a new build file invalidates the entry`(@TempDir temp: Path) {
        val root = projectWithBuildFile(temp)
        val cache = temp.resolve("auto")
        val hash = ProjectDiscovery.projectHash(root)
        ProjectCache.save(cache, definitionFor(hash), ProjectDiscovery.computeFingerprint(root))
        Files.writeString(root.resolve("gradle.lockfile"), "com.example:lib:1.0=runtimeClasspath\n")
        ProjectCache.load(cache, hash, root) shouldBe null
    }

    @Test
    fun `a corrupt workspace file reads as absent`(@TempDir temp: Path) {
        val root = projectWithBuildFile(temp)
        val cache = temp.resolve("auto")
        val hash = ProjectDiscovery.projectHash(root)
        ProjectCache.save(cache, definitionFor(hash), ProjectDiscovery.computeFingerprint(root))
        Files.writeString(ProjectCache.cacheFile(cache, hash), "name = [unclosed\n")
        ProjectCache.load(cache, hash, root) shouldBe null
    }

    @Test
    fun `a missing or fingerprint-less entry reads as absent`(@TempDir temp: Path) {
        val root = projectWithBuildFile(temp)
        val cache = temp.resolve("auto")
        val hash = ProjectDiscovery.projectHash(root)
        ProjectCache.load(cache, hash, root) shouldBe null
        ProjectCache.save(cache, definitionFor(hash), ProjectDiscovery.computeFingerprint(root))
        Files.delete(ProjectCache.fingerprintFile(cache, hash))
        ProjectCache.load(cache, hash, root) shouldBe null
    }

    @Test
    fun `a name-mismatched entry reads as absent`(@TempDir temp: Path) {
        val root = projectWithBuildFile(temp)
        val cache = temp.resolve("auto")
        val hash = ProjectDiscovery.projectHash(root)
        val renamed = definitionFor(hash).copy(name = "other")
        // Saved under the wrong stem: the loader's stem check rejects it.
        Files.createDirectories(cache)
        Files.writeString(
            ProjectCache.cacheFile(cache, hash),
            WorkspaceToml.encode(renamed),
        )
        Files.writeString(
            ProjectCache.fingerprintFile(cache, hash),
            ProjectDiscovery.computeFingerprint(root) + "\n",
        )
        ProjectCache.load(cache, hash, root) shouldBe null
    }
}
