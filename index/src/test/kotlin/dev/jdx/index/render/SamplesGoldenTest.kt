package dev.jdx.index.render

import dev.jdx.index.service.JdxService
import dev.jdx.index.service.JdxService.RootsSpec
import dev.jdx.index.service.JdxService.SampleOptions
import dev.jdx.index.service.buildSamplesCaseJar
import dev.jdx.testsupport.golden.GoldenFiles
import java.io.File
import java.nio.file.Files
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail

/**
 * Golden tests for the samples renderer over a crafted corpus (T-034).
 *
 * Each pinned query resolves through [JdxService.samples] against the
 * hand-built `samples-case.jar` ([buildSamplesCaseJar]), whose labels are
 * already hermetic (the jar file name, never a temp path — D-007), once as
 * text and once as JSON under `src/test/resources/golden/samples/`.
 * Service-level resolution (ambiguity, degradation, exit codes) is covered in
 * `SamplesServiceTest`; here the assertions are pure rendering — including
 * the sourced snippet, ranking order and `--limit` variants.
 *
 * Rewrite with `./gradlew :index:tier2Test -Pgolden.update=true` — then read
 * the diff before committing it. A golden updated without reading is a test
 * deleted (TESTING.md §14). Comparison, update mode and the orphan check come
 * from the shared [GoldenFiles] helper (T-054).
 */
@Tag("tier2")
class SamplesGoldenTest {

    private data class GoldenQuery(
        val ref: String,
        val limit: Int = 100,
        val preferSources: Boolean = false,
    )

    private val queries = listOf(
        GoldenQuery("s.Lib#greet"),
        GoldenQuery("s.Lib#greet(java.lang.String)"),
        GoldenQuery("s.Lib"),
        GoldenQuery("s.Bean#<init>()"),
        GoldenQuery("s.Lib#greet", preferSources = true),
    )

    @Test
    fun `text and json goldens cover pinned samples`() {
        val dir = Files.createTempDirectory("samples-golden-test")
        val roots = RootsSpec(jarSpecs = listOf(buildSamplesCaseJar(dir).toString()), includeJdk = false)
        val goldenDir = File("src/test/resources/golden/samples")
        val contents = buildMap {
            for (query in queries) {
                val options = SampleOptions(limit = query.limit, preferSources = query.preferSources)
                val outcome = JdxService.samples(query.ref, roots, options)
                val listing = (outcome as? JdxService.ServiceOutcome.SampleList)?.listing
                    ?: fail("samples ${query.ref}: expected SampleList, got $outcome")
                val key = "samples_" + query.ref
                    .replace('$', '_')
                    .replace(Regex("[^A-Za-z0-9_#]"), "_") +
                    (if (query.preferSources) "_prefer" else "")
                put("$key.txt", listing.renderText())
                put("$key.json", listing.toJson(command = "samples"))
            }
            // A `--limit` truncation variant over the five-row blind query.
            val outcome = JdxService.samples("s.Lib#greet", roots, SampleOptions(limit = 2))
            val listing = (outcome as? JdxService.ServiceOutcome.SampleList)?.listing
                ?: fail("limited: expected SampleList, got $outcome")
            put("samples_s_Lib_greet_limit2.txt", listing.renderText())
            put("samples_s_Lib_greet_limit2.json", listing.toJson(command = "samples"))
        }
        GoldenFiles.verifyAll(goldenDir, contents)
    }
}
