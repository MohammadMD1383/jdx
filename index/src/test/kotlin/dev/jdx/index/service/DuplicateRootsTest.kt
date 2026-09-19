package dev.jdx.index.service

import dev.jdx.core.model.WarningCode
import dev.jdx.index.workspace.FileWorkspaceStore
import dev.jdx.index.workspace.WorkspaceDefinition
import dev.jdx.index.workspace.WorkspaceResolver
import dev.jdx.index.service.JdxService.RootsSpec
import dev.jdx.index.service.JdxService.ServiceOutcome
import dev.jdx.testsupport.fixtures.FixtureJars
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.nio.file.Files
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Dedupe of identical resolved roots (T-068, tier 2).
 *
 * Explicit `--jars` merged in front of a workspace holding the same jar opens
 * one file as two roots: `search` doubles every hit and `tree` suffixes the
 * second copy `(2)`. Roots resolving to the same normalised absolute path must
 * open once (explicit occurrence keeping shadowing order); the same file
 * *name* in different directories (shading) must still list per provider.
 */
@Tag("tier2")
class DuplicateRootsTest {

    private fun singleRoots(): RootsSpec {
        val jar = FixtureJars.binaryJar()
        return RootsSpec(jarSpecs = listOf(jar.absolutePath), includeJdk = false)
    }

    private fun duplicateRoots(): RootsSpec {
        val jar = FixtureJars.binaryJar()
        return RootsSpec(jarSpecs = listOf(jar.absolutePath, jar.absolutePath), includeJdk = false)
    }

    private fun textOf(outcome: ServiceOutcome): String = outcome.renderText(false)

    @Test
    fun `same jar twice lists each search hit once`() {
        val single = JdxService.search("dev.jdx.fixtures.Generics", singleRoots())
        single.exitCode shouldBe 0
        val doubled = JdxService.search("dev.jdx.fixtures.Generics", duplicateRoots())
        doubled.exitCode shouldBe 0
        textOf(doubled) shouldBe textOf(single)
        val hits = (doubled as ServiceOutcome.SearchList).listing.hits
        hits shouldHaveSize 1
    }

    @Test
    fun `same file via different path forms opens once`() {
        val jar = FixtureJars.binaryJar()
        // Same file with a redundant `./` segment: a different spec string that
        // normalises to the same absolute path.
        val rewritten = jar.toPath().parent.resolve("./${jar.name}").toString()
        val roots = RootsSpec(jarSpecs = listOf(jar.absolutePath, rewritten), includeJdk = false)
        val outcome = JdxService.search("dev.jdx.fixtures.Generics", roots)
        outcome.exitCode shouldBe 0
        textOf(outcome) shouldBe textOf(JdxService.search("dev.jdx.fixtures.Generics", singleRoots()))
        (outcome as ServiceOutcome.SearchList).listing.hits shouldHaveSize 1
    }

    @Test
    fun `same jar twice resolves and lists once with one tree artifact`() {
        val single = singleRoots()
        val doubled = duplicateRoots()

        val singleResolve = JdxService.resolve("Generics", single)
        val doubledResolve = JdxService.resolve("Generics", doubled)
        doubledResolve.exitCode shouldBe 0
        textOf(doubledResolve) shouldBe textOf(singleResolve)

        val singleLs = JdxService.ls("dev.jdx.fixtures", single, limit = 1000)
        val doubledLs = JdxService.ls("dev.jdx.fixtures", doubled, limit = 1000)
        doubledLs.exitCode shouldBe 0
        textOf(doubledLs) shouldBe textOf(singleLs)

        val jarName = FixtureJars.binaryJar().name
        val singleTree = JdxService.tree(jarName, single)
        val doubledTree = JdxService.tree(jarName, doubled)
        doubledTree.exitCode shouldBe 0
        textOf(doubledTree) shouldBe textOf(singleTree)
        textOf(doubledTree) shouldNotContain "(2)"
        (doubledTree as ServiceOutcome.TreeList).listing.artifacts shouldHaveSize 1
    }

    @Test
    fun `duplicate roots emit no duplicate-fqn warning`() {
        val outcome = JdxService.members("dev.jdx.fixtures.Generics", duplicateRoots())
        outcome.exitCode shouldBe 0
        val warnings = (outcome as ServiceOutcome.MemberList).listing.warnings
        warnings.none { it.code == WarningCode.DUPLICATE_FQN } shouldBe true
    }

    /**
     * The acceptance case the bug was found under (T-068): one file named both as a
     * direct `--jars` path (front of the merge) and through a stored workspace's
     * relative glob (behind it). The merge itself is correct §13 behaviour — the
     * dedupe happens where the specs become roots, so this drives the full
     * resolver → RootsSpec → openRoots path, not just `openRoots` directly.
     */
    @Test
    fun `same jar via --jars and via a workspace lists each symbol once`(@TempDir temp: java.io.File) {
        val jar = FixtureJars.binaryJar().toPath()
        val store = FileWorkspaceStore(temp.toPath().resolve("config"))
        // The stored root is a glob over the jar's directory (the shape the bug was
        // found under), anchored absolutely so it cannot depend on the process CWD.
        store.save(WorkspaceDefinition("fx", listOf("${jar.parent}/*.jar"), includeJdk = false))
        val resolved = WorkspaceResolver.resolve(
            explicitJars = listOf(jar.toString()),
            flagWorkspace = "fx",
            loadWorkspace = store::load,
            listNames = store::listNames,
        )
        val roots = (resolved as WorkspaceResolver.Result.success).value
        val spec = RootsSpec.fromResolved(roots)
        // Sanity: the merge really did produce the same file twice.
        spec.jarSpecs.size shouldBe 2

        val single = JdxService.search("dev.jdx.fixtures.Generics", singleRoots())
        val outcome = JdxService.search("dev.jdx.fixtures.Generics", spec)
        outcome.exitCode shouldBe 0
        textOf(outcome) shouldBe textOf(single)
        (outcome as ServiceOutcome.SearchList).listing.hits shouldHaveSize 1
    }

    @Test
    fun `same jar via --jars and via a workspace answers members once with no duplicate-fqn warning`(@TempDir temp: java.io.File) {
        val jar = FixtureJars.binaryJar().toPath()
        val store = FileWorkspaceStore(temp.toPath().resolve("config"))
        store.save(WorkspaceDefinition("fx", listOf(jar.toString()), includeJdk = false))
        val resolved = WorkspaceResolver.resolve(
            explicitJars = listOf(jar.toString()),
            flagWorkspace = "fx",
            loadWorkspace = store::load,
            listNames = store::listNames,
        )
        val roots = (resolved as WorkspaceResolver.Result.success).value
        val spec = RootsSpec.fromResolved(roots)
        spec.jarSpecs.size shouldBe 2

        val outcome = JdxService.members("dev.jdx.fixtures.Generics", spec)
        outcome.exitCode shouldBe 0
        val warnings = (outcome as ServiceOutcome.MemberList).listing.warnings
        warnings.none { it.code == WarningCode.DUPLICATE_FQN } shouldBe true
    }

    @Test
    fun `same file name in different directories keeps per-provider hits`(@TempDir temp: java.io.File) {
        val jar = FixtureJars.binaryJar()
        val first = Files.createDirectories(temp.toPath().resolve("a")).resolve(jar.name)
        val second = Files.createDirectories(temp.toPath().resolve("b")).resolve(jar.name)
        Files.copy(jar.toPath(), first)
        Files.copy(jar.toPath(), second)
        val roots = RootsSpec(jarSpecs = listOf(first.toString(), second.toString()), includeJdk = false)
        val outcome = JdxService.search("dev.jdx.fixtures.Generics", roots)
        outcome.exitCode shouldBe 0
        // Shading visibility: two different files stay two providers.
        (outcome as ServiceOutcome.SearchList).listing.hits shouldHaveSize 2
        textOf(outcome) shouldContain jar.name
    }
}
