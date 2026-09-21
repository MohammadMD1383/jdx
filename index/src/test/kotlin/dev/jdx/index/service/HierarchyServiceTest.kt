package dev.jdx.index.service

import dev.jdx.core.model.WarningCode
import dev.jdx.index.service.JdxService.HierarchyOptions
import dev.jdx.index.service.JdxService.RootsSpec
import dev.jdx.index.service.JdxService.ServiceOutcome
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.nio.file.Files
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Behaviour of `hierarchy` against a crafted corpus (T-032, tier 2):
 * ASM-built classes whose every supertype edge is placed by hand
 * ([buildHierarchyCaseJar]), so the expected up/down sets are exact.
 *
 * Rendering itself is pinned by `HierarchyGoldenTest` in the render package;
 * here the assertions are structural — resolution, directions, depth caps,
 * artifact filters, truncation, exit codes, determinism and text⊆JSON.
 */
@Tag("tier2")
class HierarchyServiceTest {

    private val tempDir: java.nio.file.Path = Files.createTempDirectory("hierarchy-service-test")

    private fun caseRoots(): RootsSpec =
        RootsSpec(jarSpecs = listOf(buildHierarchyCaseJar(tempDir).toString()), includeJdk = false)

    private fun textOf(outcome: ServiceOutcome): String = outcome.renderText(false)

    private fun hierarchyOf(outcome: ServiceOutcome): dev.jdx.core.render.HierarchyListing =
        (outcome as? ServiceOutcome.Hierarchy)?.listing
            ?: error("expected Hierarchy, got $outcome")

    // -- upward chain ---------------------------------------------------------

    @Test
    fun `up chain lists transitive supertypes nearest first`() {
        val outcome = JdxService.hierarchy("h.Leaf", caseRoots(), HierarchyOptions(down = false))
        outcome.exitCode shouldBe 0
        val listing = hierarchyOf(outcome)
        listing.supertypes.map { it.relation + " " + it.binary } shouldBe listOf(
            "extends h.Middle",
            "extends h.Base",
            "implements h.Iface",
            "extends java.lang.Object",
        )
        listing.supertypes.map { it.depth } shouldBe listOf(1, 2, 2, 3)
    }

    @Test
    fun `interface superinterfaces render as extends`() {
        val outcome = JdxService.hierarchy("h.SubIface", caseRoots(), HierarchyOptions(down = false))
        outcome.exitCode shouldBe 0
        val text = textOf(outcome)
        text shouldContain "    extends h.Iface"
        text shouldNotContain "implements h.Iface"
    }

    @Test
    fun `supertype outside the workspace prints without an artifact and warns`() {
        val outcome = JdxService.hierarchy("h.Orphan", caseRoots(), HierarchyOptions(down = false))
        outcome.exitCode shouldBe 0
        val listing = hierarchyOf(outcome)
        val orphan = listing.supertypes.single { it.binary == "h.Missing" }
        orphan.artifact shouldBe null
        listing.warnings.map { it.code } shouldBe listOf(WarningCode.UNRESOLVED_SUPERTYPE)
    }

    @Test
    fun `java lang Object outside the workspace is silent`() {
        val outcome = JdxService.hierarchy("h.Other", caseRoots(), HierarchyOptions(down = false))
        outcome.exitCode shouldBe 0
        val listing = hierarchyOf(outcome)
        listing.supertypes.map { it.binary } shouldBe listOf("java.lang.Object")
        listing.supertypes.single().artifact shouldBe null
        listing.warnings shouldBe emptyList()
    }

    // -- downward scan --------------------------------------------------------

    @Test
    fun `down scan lists transitive subtypes with via notes`() {
        val outcome = JdxService.hierarchy("h.Base", caseRoots(), HierarchyOptions(up = false))
        outcome.exitCode shouldBe 0
        val listing = hierarchyOf(outcome)
        listing.subtypes.map { it.binary } shouldBe listOf("h.Leaf", "h.Middle")
        listing.subtypes.single { it.binary == "h.Middle" }.via shouldBe null
        listing.subtypes.single { it.binary == "h.Leaf" }.via shouldBe "extends h.Middle"
    }

    @Test
    fun `down scan of an interface lists implementors`() {
        val outcome = JdxService.hierarchy("h.Iface", caseRoots(), HierarchyOptions(up = false))
        outcome.exitCode shouldBe 0
        val listing = hierarchyOf(outcome)
        listing.subtypes.map { it.binary } shouldBe listOf("h.Impl", "h.Leaf", "h.Middle", "h.SubIface")
        listing.subtypes.single { it.binary == "h.SubIface" }.via shouldBe null
    }

    @Test
    fun `unrelated type has an empty down set but still exits 0`() {
        val outcome = JdxService.hierarchy("h.Other", caseRoots())
        outcome.exitCode shouldBe 0
        val text = textOf(outcome)
        text shouldContain "  subtypes in workspace (0)"
    }

    // -- directions, depth, filters, truncation ---------------------------------

    @Test
    fun `direct shows one level each way`() {
        val outcome = JdxService.hierarchy("h.Middle", caseRoots(), HierarchyOptions(directOnly = true))
        outcome.exitCode shouldBe 0
        val listing = hierarchyOf(outcome)
        listing.supertypes.map { it.binary } shouldBe listOf("h.Base", "h.Iface")
        listing.subtypes.map { it.binary } shouldBe listOf("h.Leaf")
    }

    @Test
    fun `depth one matches direct`() {
        val direct = hierarchyOf(JdxService.hierarchy("h.Leaf", caseRoots(), HierarchyOptions(directOnly = true)))
        val depthOne = hierarchyOf(JdxService.hierarchy("h.Leaf", caseRoots(), HierarchyOptions(depth = 1)))
        direct.supertypes shouldBe depthOne.supertypes
        direct.subtypes shouldBe depthOne.subtypes
    }

    @Test
    fun `depth one keeps grandchildren out of the down set`() {
        val outcome = JdxService.hierarchy("h.Base", caseRoots(), HierarchyOptions(up = false, depth = 1))
        outcome.exitCode shouldBe 0
        hierarchyOf(outcome).subtypes.map { it.binary } shouldBe listOf("h.Middle")
    }

    @Test
    fun `in and exclude filter subtypes by artifact label`() {
        val base = HierarchyOptions(up = false)
        val everything = hierarchyOf(JdxService.hierarchy("h.Iface", caseRoots(), base))
        everything.subtypes.size shouldBe 4
        val excluded = JdxService.hierarchy(
            "h.Iface",
            caseRoots(),
            base.copy(exclude = "hierarchy-case.jar"),
        )
        hierarchyOf(excluded).subtypes shouldBe emptyList()
        val missing = JdxService.hierarchy("h.Iface", caseRoots(), base.copy(inArtifact = "other.jar"))
        hierarchyOf(missing).subtypes shouldBe emptyList()
    }

    @Test
    fun `limit truncates subtypes with a footer`() {
        val outcome = JdxService.hierarchy("h.Iface", caseRoots(), HierarchyOptions(up = false, limit = 2))
        outcome.exitCode shouldBe 0
        val listing = hierarchyOf(outcome)
        listing.subtypes.size shouldBe 2
        val text = textOf(outcome)
        text shouldContain "2 of 4 subtypes shown (--limit 4 to see more)"
    }

    // -- errors -----------------------------------------------------------------

    @Test
    fun `member refs are usage errors`() {
        val outcome = JdxService.hierarchy("h.Leaf#toString", caseRoots())
        outcome.exitCode shouldBe 3
        textOf(outcome) shouldContain "takes a type reference"
    }

    @Test
    fun `unknown types exit 1 with suggestions`() {
        val outcome = JdxService.hierarchy("h.Lea", caseRoots())
        outcome.exitCode shouldBe 1
    }

    @Test
    fun `ambiguous short names exit 2`() {
        val outcome = JdxService.hierarchy("Leaf", caseRoots())
        outcome.exitCode shouldBe 2
        textOf(outcome) shouldContain "h.Leaf"
        textOf(outcome) shouldContain "h2.Leaf"
    }

    @Test
    fun `invalid refs exit 3 and empty roots exit 4`() {
        JdxService.hierarchy("h.Leaf#(", caseRoots()).exitCode shouldBe 3
        JdxService.hierarchy(
            "h.Leaf",
            RootsSpec(jarSpecs = emptyList(), includeJdk = false),
        ).exitCode shouldBe 4
    }

    @Test
    fun `negative limit and zero depth are usage errors`() {
        JdxService.hierarchy("h.Leaf", caseRoots(), HierarchyOptions(limit = -1)).exitCode shouldBe 3
        JdxService.hierarchy("h.Leaf", caseRoots(), HierarchyOptions(depth = 0)).exitCode shouldBe 3
        JdxService.hierarchy("h.Leaf", caseRoots(), HierarchyOptions(up = false, down = false)).exitCode shouldBe 3
    }

    // -- metamorphic: up and down agree (TESTING.md §6) -----------------------------

    @Test
    fun `down contains S iff up of S contains T`() {
        val jar = buildHierarchyCaseJar(tempDir)
        val roots = RootsSpec(jarSpecs = listOf(jar.toString()), includeJdk = false)
        val binaries = java.util.jar.JarFile(jar.toFile()).use { jarFile ->
            jarFile.entries().asSequence()
                .map { it.name }
                .filter { it.endsWith(".class") && !it.contains("$") }
                .map { it.removeSuffix(".class").replace('/', '.') }
                .toSet()
        }
        binaries.size shouldBe 9
        for (target in binaries) {
            val down = hierarchyOf(
                JdxService.hierarchy(target, roots, HierarchyOptions(up = false)),
            ).subtypes.map { it.binary }.toSet()
            for (sub in down) {
                val up = hierarchyOf(
                    JdxService.hierarchy(sub, roots, HierarchyOptions(down = false)),
                ).supertypes.map { it.binary }.toSet()
                (target in up) shouldBe true
            }
            val up = hierarchyOf(
                JdxService.hierarchy(target, roots, HierarchyOptions(down = false)),
            ).supertypes.filter { it.artifact != null }.map { it.binary }.toSet()
            for (parent in up) {
                val downOfParent = hierarchyOf(
                    JdxService.hierarchy(parent, roots, HierarchyOptions(up = false)),
                ).subtypes.map { it.binary }.toSet()
                (target in downOfParent) shouldBe true
            }
        }
    }

    // -- determinism and parity ---------------------------------------------------

    @Test
    fun `run twice yields identical bytes`() {
        val first = JdxService.hierarchy("h.Iface", caseRoots())
        val second = JdxService.hierarchy("h.Iface", caseRoots())
        first.renderText(false) shouldBe second.renderText(false)
        first.toJson("hierarchy") shouldBe second.toJson("hierarchy")
    }

    @Test
    fun `every text row appears in json`() {
        val listing = hierarchyOf(JdxService.hierarchy("h.Iface", caseRoots()))
        val json = listing.toJson("hierarchy")
        for (entry in listing.supertypes) {
            json shouldContain entry.binary
        }
        for (entry in listing.subtypes) {
            json shouldContain entry.binary
            json shouldContain entry.artifact
        }
    }
}
