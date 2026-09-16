package dev.jdx.index.workspace

import dev.jdx.core.model.WarningCode
import dev.jdx.index.artifact.ArtifactTestJars
import dev.jdx.index.service.JdxService
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Auto-discovery through the real read path (T-016, tier 2): a fabricated Gradle
 * project whose `build/classes` holds one fixture class answers `members` with no
 * workspace flags, and the coordinate-less project warns `PROJECT_DISCOVERY_FALLBACK`
 * in both renderings.
 */
@Tag("tier2")
class ProjectDiscoveryServiceTest {

    /** Copies the first top-level fixture class into a fabricated project's classes dir. */
    private fun fabricatedProject(temp: Path): Pair<Path, String> {
        val root = temp.resolve("proj").also { Files.createDirectories(it) }
        Files.writeString(root.resolve("settings.gradle"), "rootProject.name = \"demo\"\n")
        ZipFile(ArtifactTestJars.binaryJar()).use { zip ->
            val entry = zip.entries().asSequence()
                .filter { !it.isDirectory && it.name.endsWith(".class") && '$' !in it.name }
                .first()
            val target = root.resolve("build/classes/java/main").resolve(entry.name)
            Files.createDirectories(target.parent)
            zip.getInputStream(entry).use { input -> Files.copy(input, target) }
            val binary = entry.name.removeSuffix(".class").replace('/', '.')
            return root to binary
        }
    }

    private fun discoveredRoots(
        store: WorkspaceStore,
        jars: List<String>,
        warnings: List<dev.jdx.core.model.Warning> = emptyList(),
    ): JdxService.RootsSpec {
        val outcome = WorkspaceResolver.resolve(
            discoveredJars = jars,
            discoveredSelection = "auto-discovered project at '/proj' (nearest build file: settings.gradle)",
            discoveredWarnings = warnings,
            loadWorkspace = store::load,
            listNames = store::listNames,
        )
        check(outcome is WorkspaceResolver.Result.success) { "resolution failed: $outcome" }
        return JdxService.RootsSpec.fromResolved(outcome.value)
    }

    @Test
    fun `a discovered classes dir answers members with a fallback warning`(@TempDir temp: Path) {
        val (root, binary) = fabricatedProject(temp)
        val derived = ProjectDiscovery.deriveBinaryRoots(root)
        (derived.fallbackWarning?.code) shouldBe WarningCode.PROJECT_DISCOVERY_FALLBACK

        val roots = discoveredRoots(
            InMemoryWorkspaceStore(),
            derived.jars,
            listOfNotNull(derived.fallbackWarning),
        ).copy(includeJdk = false)
        val outcome = JdxService.members(binary, roots)
        outcome.exitCode shouldBe 0
        val text = outcome.renderText(false)
        text shouldContain binary.substringAfterLast('.')
        text shouldContain "PROJECT_DISCOVERY_FALLBACK"
        outcome.toJson("members") shouldContain "PROJECT_DISCOVERY_FALLBACK"
    }

    @Test
    fun `cached derived roots still answer`(@TempDir temp: Path) {
        val (root, binary) = fabricatedProject(temp)
        val cache = temp.resolve("auto")
        val hash = ProjectDiscovery.projectHash(root)
        val derived = ProjectDiscovery.deriveBinaryRoots(root)
        val definition = WorkspaceDefinition(name = hash, jars = derived.jars, includeJdk = true)
        ProjectCache.save(cache, definition, ProjectDiscovery.computeFingerprint(root))

        val loaded = ProjectCache.load(cache, hash, root)
        loaded shouldBe definition
        val roots = discoveredRoots(InMemoryWorkspaceStore(), loaded?.jars ?: emptyList())
            .copy(includeJdk = false)
        JdxService.members(binary, roots).exitCode shouldBe 0
    }
}
