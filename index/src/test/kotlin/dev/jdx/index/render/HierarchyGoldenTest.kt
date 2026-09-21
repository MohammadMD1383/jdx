package dev.jdx.index.render

import dev.jdx.index.service.JdxService
import dev.jdx.index.service.JdxService.HierarchyOptions
import dev.jdx.index.service.JdxService.RootsSpec
import dev.jdx.index.service.buildHierarchyCaseJar
import dev.jdx.testsupport.golden.GoldenFiles
import java.io.File
import java.nio.file.Files
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail

/**
 * Golden tests for the hierarchy renderer over a crafted corpus (T-032).
 *
 * Each pinned symbol resolves through [JdxService.hierarchy] against the
 * hand-built `hierarchy-case.jar` ([buildHierarchyCaseJar]), whose labels are
 * already hermetic (the jar file name, never a temp path — D-007), once as
 * text and once as JSON under `src/test/resources/golden/hierarchy/`.
 * Service-level resolution (ambiguity, degradation, exit codes) is covered in
 * `HierarchyServiceTest`; here the assertions are pure rendering — including
 * direction-selected and `--limit` truncation variants.
 *
 * Rewrite with `./gradlew :index:tier2Test -Pgolden.update=true` — then read
 * the diff before committing it. A golden updated without reading is a test
 * deleted (TESTING.md §14). Comparison, update mode and the orphan check come
 * from the shared [GoldenFiles] helper (T-054).
 */
@Tag("tier2")
class HierarchyGoldenTest {

    private data class GoldenQuery(
        val ref: String,
        val options: HierarchyOptions = HierarchyOptions(),
    )

    private val queries = listOf(
        GoldenQuery("h.Leaf"),
        GoldenQuery("h.Iface"),
        GoldenQuery("h.Leaf", HierarchyOptions(down = false)),
        GoldenQuery("h.Iface", HierarchyOptions(up = false)),
    )

    @Test
    fun `text and json goldens cover pinned hierarchies`() {
        val dir = Files.createTempDirectory("hierarchy-golden-test")
        val roots = RootsSpec(jarSpecs = listOf(buildHierarchyCaseJar(dir).toString()), includeJdk = false)
        val goldenDir = File("src/test/resources/golden/hierarchy")
        val contents = buildMap {
            for (query in queries) {
                val outcome = JdxService.hierarchy(query.ref, roots, query.options)
                val listing = (outcome as? JdxService.ServiceOutcome.Hierarchy)?.listing
                    ?: fail("${query.ref}: expected Hierarchy, got $outcome")
                val key = query.ref
                    .replace('$', '_')
                    .replace(Regex("[^A-Za-z0-9_#]"), "_") +
                    if (!query.options.up) "_down" else if (!query.options.down) "_up" else ""
                put("$key.txt", listing.renderText())
                put("$key.json", listing.toJson(command = "hierarchy"))
            }
            // A `--limit` truncation variant over the four-subtype interface query.
            val outcome = JdxService.hierarchy(
                "h.Iface",
                roots,
                HierarchyOptions(up = false, limit = 2),
            )
            val listing = (outcome as? JdxService.ServiceOutcome.Hierarchy)?.listing
                ?: fail("h.Iface limited: expected Hierarchy, got $outcome")
            put("h_Iface_limit2.txt", listing.renderText())
            put("h_Iface_limit2.json", listing.toJson(command = "hierarchy"))
        }
        GoldenFiles.verifyAll(goldenDir, contents)
    }
}
