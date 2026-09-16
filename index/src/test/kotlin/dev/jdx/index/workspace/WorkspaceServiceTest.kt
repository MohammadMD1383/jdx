package dev.jdx.index.workspace

import dev.jdx.index.artifact.ArtifactTestJars
import dev.jdx.index.service.JdxService
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Workspaces read through the real service (T-015, tier 2): a stored workspace's
 * ordered roots resolve and answer queries, earlier roots shadow later ones, and the
 * shadowing surfaces as `DUPLICATE_FQN` naming every provider in order.
 *
 * Two copies of the fixture jar stand in for two artifacts sharing classes — same
 * bytes, different paths, so the winner is provably the earlier root, not the
 * "better" file. Member rendering itself is pinned by the golden suites; here the
 * assertions are structural (exit codes, warning order, root order).
 */
@Tag("tier2")
class WorkspaceServiceTest {

    private fun rootsOf(
        store: WorkspaceStore,
        explicitJars: List<String> = emptyList(),
        workspace: String? = null,
    ): JdxService.RootsSpec {
        val outcome = WorkspaceResolver.resolve(
            explicitJars = explicitJars,
            flagWorkspace = workspace,
            loadWorkspace = store::load,
            listNames = store::listNames,
        )
        check(outcome is WorkspaceResolver.Result.success) { "resolution failed: $outcome" }
        return JdxService.RootsSpec.fromResolved(outcome.value)
    }

    @Test
    fun `a stored workspace answers queries`(@TempDir temp: Path) {
        val jar = ArtifactTestJars.binaryJar().absolutePath
        val store = FileWorkspaceStore(temp.resolve("config"))
        store.save(WorkspaceDefinition("fx", listOf(jar), includeJdk = false))

        val outcome = JdxService.members("dev.jdx.fixtures.VarargsAndModifiers", rootsOf(store, workspace = "fx"))
        (outcome.exitCode) shouldBe 0
        val listing = (outcome as JdxService.ServiceOutcome.MemberList).listing
        (listing.warnings.any { it.code == dev.jdx.core.model.WarningCode.DUPLICATE_FQN }) shouldBe false
        outcome.renderText(false) shouldContain "VarargsAndModifiers"
    }

    @Test
    fun `earlier roots shadow later ones and warn in order`(@TempDir temp: Path) {
        val original = ArtifactTestJars.binaryJar().toPath()
        val first = temp.resolve("first.jar").also { Files.copy(original, it) }
        val second = temp.resolve("second.jar").also { Files.copy(original, it) }
        val store = FileWorkspaceStore(temp.resolve("config"))
        store.save(WorkspaceDefinition("ordered", listOf(first.toString(), second.toString()), includeJdk = false))

        val outcome = JdxService.members("dev.jdx.fixtures.VarargsAndModifiers", rootsOf(store, workspace = "ordered"))
        (outcome.exitCode) shouldBe 0
        val text = outcome.renderText(false)
        text shouldContain "DUPLICATE_FQN"
        // Classpath order: the first root wins and is named first.
        text shouldContain "showing first.jar"
        (text.indexOf("first.jar") < text.indexOf("second.jar")) shouldBe true

        // Reversed order reverses the winner — order is data, not accident.
        store.save(WorkspaceDefinition("reversed", listOf(second.toString(), first.toString()), includeJdk = false))
        val flipped = JdxService.members(
            "dev.jdx.fixtures.VarargsAndModifiers",
            rootsOf(store, workspace = "reversed"),
        )
        val flippedText = flipped.renderText(false)
        flippedText shouldContain "showing second.jar"
    }

    @Test
    fun `explicit jars merge in front of the workspace end to end`(@TempDir temp: Path) {
        val original = ArtifactTestJars.binaryJar().toPath()
        val first = temp.resolve("first.jar").also { Files.copy(original, it) }
        val second = temp.resolve("second.jar").also { Files.copy(original, it) }
        val store = FileWorkspaceStore(temp.resolve("config"))
        store.save(WorkspaceDefinition("ws", listOf(second.toString()), includeJdk = false))

        val outcome = JdxService.members(
            "dev.jdx.fixtures.VarargsAndModifiers",
            rootsOf(store, explicitJars = listOf(first.toString()), workspace = "ws"),
        )
        (outcome.exitCode) shouldBe 0
        val text = outcome.renderText(false)
        text shouldContain "DUPLICATE_FQN"
        text shouldContain "showing first.jar"
    }

    @Test
    fun `determinism across stores with the same roots`() {
        val first = InMemoryWorkspaceStore().also {
            it.save(WorkspaceDefinition("ws", listOf("a.jar", "b.jar"), includeJdk = false))
        }
        val second = InMemoryWorkspaceStore().also {
            it.save(WorkspaceDefinition("ws", listOf("a.jar", "b.jar"), includeJdk = false))
        }
        rootsOf(first, workspace = "ws") shouldBe rootsOf(second, workspace = "ws")
    }
}
