package dev.jdx.index.render

import dev.jdx.index.service.JdxService
import dev.jdx.index.service.JdxService.RootsSpec
import dev.jdx.index.service.JdxService.UsageOptions
import dev.jdx.index.service.buildUsagesCaseJar
import dev.jdx.testsupport.golden.GoldenFiles
import java.io.File
import java.nio.file.Files
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail

/**
 * Golden tests for the usages renderer over a crafted corpus (T-030).
 *
 * Each pinned symbol resolves through [JdxService.usages] against the
 * hand-built `usages-case.jar` ([buildUsagesCaseJar]), whose labels are
 * already hermetic (the jar file name, never a temp path — D-007), once as
 * text and once as JSON under `src/test/resources/golden/usages/`.
 * Service-level resolution (ambiguity, degradation, exit codes) is covered in
 * `UsagesServiceTest`; here the assertions are pure rendering — including a
 * `--limit` truncation variant.
 *
 * Rewrite with `./gradlew :index:tier2Test -Pgolden.update=true` — then read
 * the diff before committing it. A golden updated without reading is a test
 * deleted (TESTING.md §14). Comparison, update mode and the orphan check come
 * from the shared [GoldenFiles] helper (T-054).
 */
@Tag("tier2")
class UsagesGoldenTest {

    private data class GoldenQuery(
        val ref: String,
        val limit: Int = Int.MAX_VALUE,
    )

    private val queries = listOf(
        GoldenQuery("u.Lib"),
        GoldenQuery("u.Lib#greet"),
        GoldenQuery("u.Lib#count"),
    )

    @Test
    fun `text and json goldens cover pinned usages`() {
        val dir = Files.createTempDirectory("usages-golden-test")
        val roots = RootsSpec(jarSpecs = listOf(buildUsagesCaseJar(dir).toString()), includeJdk = false)
        val goldenDir = File("src/test/resources/golden/usages")
        val contents = buildMap {
            for (query in queries) {
                val outcome = JdxService.usages(query.ref, roots, UsageOptions(limit = query.limit))
                val listing = (outcome as? JdxService.ServiceOutcome.UsageList)?.listing
                    ?: fail("${query.ref}: expected UsageList, got $outcome")
                val key = query.ref
                    .replace('$', '_')
                    .replace(Regex("[^A-Za-z0-9_#]"), "_")
                put("$key.txt", listing.renderText())
                put("$key.json", listing.toJson(command = "usages"))
            }
            // A `--limit` truncation variant over the five-hit type query.
            val outcome = JdxService.usages("u.Lib", roots, UsageOptions(limit = 2))
            val listing = (outcome as? JdxService.ServiceOutcome.UsageList)?.listing
                ?: fail("u.Lib limited: expected UsageList, got $outcome")
            put("u_Lib_limit2.txt", listing.renderText())
            put("u_Lib_limit2.json", listing.toJson(command = "usages"))
        }
        GoldenFiles.verifyAll(goldenDir, contents)
    }
}
