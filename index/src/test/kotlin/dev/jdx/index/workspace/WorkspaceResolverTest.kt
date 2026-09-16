package dev.jdx.index.workspace

import dev.jdx.core.model.Warning
import dev.jdx.core.model.WarningCode
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/**
 * Tests for the §13 resolution order (T-015). Pure map-backed lookup — tier 1.
 *
 * The load-bearing invariants: `-w` beats `JDX_WORKSPACE` beats `ws use`; explicit
 * `--jars` merge *in front of* workspace jars (classpath shadowing); `--no-jdk`
 * always wins over a workspace that keeps the JDK.
 */
class WorkspaceResolverTest {

    private val stored = mapOf(
        "mc" to WorkspaceDefinition("mc", listOf("ws.jar"), includeJdk = true),
        "nojdk" to WorkspaceDefinition("nojdk", listOf("ws.jar"), includeJdk = false),
    )

    private fun resolve(
        explicitJars: List<String> = emptyList(),
        explicitNoJdk: Boolean = false,
        flagWorkspace: String? = null,
        envWorkspace: String? = null,
        activeWorkspace: String? = null,
        lookup: Map<String, WorkspaceDefinition> = stored,
        discoveredJars: List<String> = emptyList(),
        discoveredSelection: String? = null,
        discoveredWarnings: List<Warning> = emptyList(),
    ): WorkspaceResolver.Result<WorkspaceResolver.ResolvedRoots, WorkspaceResolver.ResolutionFailure> =
        WorkspaceResolver.resolve(
            explicitJars = explicitJars,
            explicitNoJdk = explicitNoJdk,
            flagWorkspace = flagWorkspace,
            envWorkspace = envWorkspace,
            activeWorkspace = activeWorkspace,
            loadWorkspace = { lookup[it] },
            listNames = { lookup.keys.sorted() },
            discoveredJars = discoveredJars,
            discoveredSelection = discoveredSelection,
            discoveredWarnings = discoveredWarnings,
        )

    private fun success(
        result: WorkspaceResolver.Result<WorkspaceResolver.ResolvedRoots, WorkspaceResolver.ResolutionFailure>,
    ): WorkspaceResolver.ResolvedRoots {
        (result is WorkspaceResolver.Result.success) shouldBe true
        return (result as WorkspaceResolver.Result.success).value
    }

    private fun failure(
        result: WorkspaceResolver.Result<WorkspaceResolver.ResolvedRoots, WorkspaceResolver.ResolutionFailure>,
    ): String {
        (result is WorkspaceResolver.Result.failure) shouldBe true
        return (result as WorkspaceResolver.Result.failure).error.message
    }

    @Test
    fun `no workspace anywhere resolves flags only`() {
        val resolved = success(resolve(explicitJars = listOf("a.jar")))
        resolved.jarSpecs shouldBe listOf("a.jar")
        resolved.includeJdk shouldBe true
        resolved.workspaceName shouldBe null
    }

    @Test
    fun `flag beats env beats active`() {
        val lookup = stored + ("other" to WorkspaceDefinition("other", listOf("other.jar")))
        success(
            resolve(flagWorkspace = "mc", envWorkspace = "other", activeWorkspace = "other", lookup = lookup),
        ).workspaceName shouldBe "mc"
        success(
            resolve(envWorkspace = "other", activeWorkspace = "mc", lookup = lookup),
        ).workspaceName shouldBe "other"
        success(resolve(activeWorkspace = "mc")).workspaceName shouldBe "mc"
    }

    @Test
    fun `blank flag env and active read as unset`() {
        val resolved = success(resolve(flagWorkspace = "  ", envWorkspace = "", activeWorkspace = " "))
        resolved.workspaceName shouldBe null
        resolved.jarSpecs shouldBe emptyList()
    }

    @Test
    fun `explicit jars merge in front of workspace jars`() {
        val resolved = success(resolve(explicitJars = listOf("cli.jar"), flagWorkspace = "mc"))
        resolved.jarSpecs shouldBe listOf("cli.jar", "ws.jar")
        resolved.workspaceName shouldBe "mc"
    }

    @Test
    fun `explicit no-jdk wins over a workspace that keeps the JDK`() {
        success(resolve(explicitNoJdk = true, flagWorkspace = "mc")).includeJdk shouldBe false
        success(resolve(flagWorkspace = "mc")).includeJdk shouldBe true
        success(resolve(flagWorkspace = "nojdk")).includeJdk shouldBe false
        // ... and an explicit flag cannot re-enable a workspace-switched-off JDK (there is
        // no --jdk flag on read commands by design — the workspace owns the default).
        success(resolve(explicitNoJdk = false, flagWorkspace = "nojdk")).includeJdk shouldBe false
    }

    @Test
    fun `a missing workspace names itself and hints at list`() {
        val message = failure(resolve(flagWorkspace = "missing"))
        message shouldContain "no such workspace 'missing'"
        message shouldContain "jdx ws list"
    }

    @Test
    fun `a missing workspace suggests near names`() {
        val message = failure(resolve(flagWorkspace = "mx"))
        message shouldContain "Did you mean: mc"
    }

    @Test
    fun `an env workspace miss names the env var`() {
        val message = failure(resolve(envWorkspace = "missing"))
        message shouldContain "JDX_WORKSPACE='missing'"
    }

    @Test
    fun `an invalid flag name is a failure, not a lookup`() {
        val message = failure(resolve(flagWorkspace = "../evil"))
        message shouldContain "invalid workspace name"
    }

    @Test
    fun `auto-discovery fills the workspace half when no workspace is selected`() {
        val warning = Warning(WarningCode.PROJECT_DISCOVERY_FALLBACK, "no coordinates")
        val resolved = success(
            resolve(
                discoveredJars = listOf("build/classes"),
                discoveredSelection = "auto-discovered project at '/repo' (nearest build file: pom.xml)",
                discoveredWarnings = listOf(warning),
            ),
        )
        resolved.jarSpecs shouldBe listOf("build/classes")
        resolved.includeJdk shouldBe true
        resolved.workspaceName shouldBe null
        resolved.selection shouldBe "auto-discovered project at '/repo' (nearest build file: pom.xml)"
        resolved.warnings shouldBe listOf(warning)
    }

    @Test
    fun `explicit jars merge in front of discovered jars`() {
        val resolved = success(
            resolve(
                explicitJars = listOf("cli.jar"),
                discoveredJars = listOf("build/classes", "dep.jar"),
                discoveredSelection = "auto-discovered project at '/repo' (nearest build file: build.gradle)",
            ),
        )
        resolved.jarSpecs shouldBe listOf("cli.jar", "build/classes", "dep.jar")
        resolved.workspaceName shouldBe null
    }

    @Test
    fun `a named workspace beats auto-discovery`() {
        val resolved = success(
            resolve(
                flagWorkspace = "mc",
                discoveredJars = listOf("build/classes"),
                discoveredSelection = "auto-discovered project at '/repo' (nearest build file: pom.xml)",
                discoveredWarnings = listOf(Warning(WarningCode.PROJECT_DISCOVERY_FALLBACK, "stale")),
            ),
        )
        resolved.jarSpecs shouldBe listOf("ws.jar")
        resolved.workspaceName shouldBe "mc"
        resolved.warnings shouldBe emptyList()
    }

    @Test
    fun `explicit no-jdk wins over auto-discovery`() {
        val resolved = success(
            resolve(
                explicitNoJdk = true,
                discoveredJars = listOf("build/classes"),
                discoveredSelection = "auto-discovered project at '/repo' (nearest build file: pom.xml)",
            ),
        )
        resolved.includeJdk shouldBe false
        resolved.jarSpecs shouldBe listOf("build/classes")
    }

    @Test
    fun `an unreadable workspace is a failure naming the cause`() {
        val result = WorkspaceResolver.resolve(
            flagWorkspace = "mc",
            loadWorkspace = { throw java.io.IOException("disk on fire") },
            listNames = { emptyList() },
        )
        failure(result) shouldContain "cannot load workspace 'mc': disk on fire"
    }
}
