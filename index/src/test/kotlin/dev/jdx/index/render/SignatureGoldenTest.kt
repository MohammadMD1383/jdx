package dev.jdx.index.render

import dev.jdx.core.model.Origin
import dev.jdx.core.model.Provenance
import dev.jdx.index.service.JdxService
import dev.jdx.index.service.JdxService.RootsSpec
import dev.jdx.index.service.JdxService.SignatureOptions
import dev.jdx.testsupport.fixtures.FixtureJars
import dev.jdx.testsupport.golden.GoldenFiles
import java.io.File
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail

/**
 * Golden tests for the signature renderer over real fixture bytecode (T-024).
 *
 * Each pinned member resolves through [JdxService.signature] against the
 * fixture binary jar, then re-pins with the fixed `fixture-corpus.jar` label
 * (never the versioned file name — the T-010 hermeticity rule), once as text
 * and once as JSON under `src/test/resources/golden/signature/`.
 * Service-level resolution (ambiguity, degradation, exit codes) is covered in
 * `SignatureServiceTest`; here the assertions are pure rendering — including
 * a bridge-disambiguation variant and a `--limit` truncation variant.
 *
 * Rewrite with `./gradlew :index:tier2Test -Pgolden.update=true` — then read
 * the diff before committing it. A golden updated without reading is a test
 * deleted (TESTING.md §14). Comparison, update mode and the orphan check come
 * from the shared [GoldenFiles] helper (T-054).
 */
@Tag("tier2")
class SignatureGoldenTest {

    private data class GoldenMember(
        val ref: String,
        val includeSynthetic: Boolean = false,
        val limit: Int = Int.MAX_VALUE,
    )

    private val members = listOf(
        GoldenMember("dev.jdx.fixtures.Generics#identity(U)"),
        GoldenMember("dev.jdx.fixtures.TrafficLight#seconds"),
        GoldenMember("dev.jdx.fixtures.CovariantOverrides\$Child#copy", includeSynthetic = true),
        GoldenMember("dev.jdx.fixtures.PersonRecord#<init>(java.lang.String,int)"),
    )

    @Test
    fun `text and json goldens cover pinned signatures`() {
        val roots = RootsSpec(
            jarSpecs = listOf(FixtureJars.binaryJar().absolutePath),
            includeJdk = false,
        )
        val goldenDir = File("src/test/resources/golden/signature")
        val contents = buildMap {
            for (member in members) {
                val outcome = JdxService.signature(
                    member.ref,
                    roots,
                    SignatureOptions(
                        includeSynthetic = member.includeSynthetic,
                        maxSignatures = member.limit,
                    ),
                )
                val block = (outcome as? JdxService.ServiceOutcome.SignatureList)?.block
                    ?: fail("${member.ref}: expected SignatureList, got $outcome")
                val pinned = block.copy(
                    provenance = listOf(
                        Provenance(artifact = "fixture-corpus.jar", origin = Origin.BYTECODE),
                    ),
                )
                val key = member.ref
                    .replace('$', '_')
                    .replace(Regex("[^A-Za-z0-9_#]"), "_")
                    .let { if (member.includeSynthetic) "${it}_synthetic" else it }
                put("$key.txt", pinned.renderText())
                put("$key.json", pinned.toJson(command = "signature"))
            }
            // A `--limit` truncation variant over the field+method pair.
            val outcome = JdxService.signature(
                "dev.jdx.fixtures.TrafficLight#seconds",
                roots,
                SignatureOptions(maxSignatures = 1),
            )
            val block = (outcome as? JdxService.ServiceOutcome.SignatureList)?.block
                ?: fail("TrafficLight#seconds: expected SignatureList, got $outcome")
            val pinned = block.copy(
                provenance = listOf(
                    Provenance(artifact = "fixture-corpus.jar", origin = Origin.BYTECODE),
                ),
            )
            put("dev_jdx_fixtures_TrafficLight_seconds_limit1.txt", pinned.renderText())
            put("dev_jdx_fixtures_TrafficLight_seconds_limit1.json", pinned.toJson(command = "signature"))
        }
        GoldenFiles.verifyAll(goldenDir, contents)
    }
}
