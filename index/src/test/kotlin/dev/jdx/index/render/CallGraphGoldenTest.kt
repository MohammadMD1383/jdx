package dev.jdx.index.render

import dev.jdx.index.service.JdxService
import dev.jdx.index.service.JdxService.CallOptions
import dev.jdx.index.service.JdxService.RootsSpec
import dev.jdx.index.service.buildCallsCaseJar
import dev.jdx.testsupport.golden.GoldenFiles
import java.io.File
import java.nio.file.Files
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail

/**
 * Golden tests for the call-hierarchy renderer over a crafted corpus (T-033).
 *
 * Each pinned query resolves through [JdxService.callers]/[JdxService.calls]
 * against the hand-built `calls-case.jar` ([buildCallsCaseJar]), whose labels
 * are already hermetic (the jar file name, never a temp path — D-007), once
 * as text and once as JSON under `src/test/resources/golden/calls/`.
 * Service-level resolution (ambiguity, degradation, exit codes) is covered in
 * `CallersServiceTest`/`CallsServiceTest`; here the assertions are pure
 * rendering — including depth, cycle and `--limit` variants.
 *
 * Rewrite with `./gradlew :index:tier2Test -Pgolden.update=true` — then read
 * the diff before committing it. A golden updated without reading is a test
 * deleted (TESTING.md §14). Comparison, update mode and the orphan check come
 * from the shared [GoldenFiles] helper (T-054).
 */
@Tag("tier2")
class CallGraphGoldenTest {

    private data class GoldenQuery(
        val command: String,
        val ref: String,
        val depth: Int = 1,
        val limit: Int = Int.MAX_VALUE,
    )

    private val queries = listOf(
        GoldenQuery("callers", "c.Lib#greet(java.lang.String)", depth = 2),
        GoldenQuery("callers", "c.Lib#greet"),
        GoldenQuery("callers", "c.Loop#a()", depth = 3),
        GoldenQuery("callers", "c.Bean#<init>()"),
        GoldenQuery("calls", "c.App#run()", depth = 2),
        GoldenQuery("calls", "c.Loop#a()", depth = 3),
        GoldenQuery("calls", "c.Self#tick()"),
        GoldenQuery("calls", "c.Bean#<init>()"),
    )

    @Test
    fun `text and json goldens cover pinned call graphs`() {
        val dir = Files.createTempDirectory("calls-golden-test")
        val roots = RootsSpec(jarSpecs = listOf(buildCallsCaseJar(dir).toString()), includeJdk = false)
        val goldenDir = File("src/test/resources/golden/calls")
        val contents = buildMap {
            for (query in queries) {
                val options = CallOptions(depth = query.depth, limit = query.limit)
                val outcome = when (query.command) {
                    "callers" -> JdxService.callers(query.ref, roots, options)
                    else -> JdxService.calls(query.ref, roots, options)
                }
                val listing = (outcome as? JdxService.ServiceOutcome.CallGraph)?.listing
                    ?: fail("${query.command} ${query.ref}: expected CallGraph, got $outcome")
                val key = query.command + "_" + query.ref
                    .replace('$', '_')
                    .replace(Regex("[^A-Za-z0-9_#]"), "_") +
                    "_d${query.depth}"
                put("$key.txt", listing.renderText())
                put("$key.json", listing.toJson(command = query.command))
            }
            // A `--limit` truncation variant over the seven-row depth-2 query.
            val outcome = JdxService.callers(
                "c.Lib#greet(java.lang.String)",
                roots,
                CallOptions(depth = 2, limit = 3),
            )
            val listing = (outcome as? JdxService.ServiceOutcome.CallGraph)?.listing
                ?: fail("limited: expected CallGraph, got $outcome")
            put("callers_c_Lib_greet_java_lang_String__d2_limit3.txt", listing.renderText())
            put("callers_c_Lib_greet_java_lang_String__d2_limit3.json", listing.toJson(command = "callers"))
        }
        GoldenFiles.verifyAll(goldenDir, contents)
    }
}
