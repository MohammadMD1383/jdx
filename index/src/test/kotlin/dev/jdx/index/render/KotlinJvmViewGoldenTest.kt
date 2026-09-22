package dev.jdx.index.render

import dev.jdx.core.model.Origin
import dev.jdx.core.model.Provenance
import dev.jdx.index.service.JdxService
import dev.jdx.index.service.JdxService.MemberFilters
import dev.jdx.index.service.JdxService.RootsSpec
import dev.jdx.index.service.JdxService.SignatureOptions
import dev.jdx.testsupport.fixtures.FixtureJars
import dev.jdx.testsupport.golden.GoldenFiles
import java.io.File
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail

/**
 * Golden tests for `--view jvm` over the Kotlin fixtures (T-037).
 *
 * The JVM projection must pin byte-exactly: JVM names (mangled `internal`,
 * `@JvmName` JVM spellings), full JVM params (hidden `Continuation`),
 * unfolded getters/setters, no `= ...` defaults, no `property` rows. The
 * Kotlin declaration view stays pinned by `RendererGoldenTest` (members),
 * `SignatureGoldenTest` and `KotlinViewsServiceTest`.
 *
 * Rewrite with `./gradlew :index:tier2Test -Pgolden.update=true` — then read
 * the diff before committing it. A golden updated without reading is a test
 * deleted (TESTING.md §14).
 */
@Tag("tier2")
class KotlinJvmViewGoldenTest {

    private val jvmMembers = MemberFilters(view = JdxService.MemberView.JVM)
    private val jvmSignature = SignatureOptions(view = JdxService.MemberView.JVM)

    @Test
    fun `text and json goldens cover the jvm view of kotlin fixtures`() {
        val roots = RootsSpec(
            jarSpecs = listOf(FixtureJars.binaryJar().absolutePath),
            includeJdk = false,
        )
        val goldenDir = File("src/test/resources/golden/jvm-view")
        val contents = buildMap {
            for (binary in listOf("dev.jdx.fixtures.KotlinMembers", "dev.jdx.fixtures.KotlinData")) {
                val outcome = JdxService.members(binary, roots, jvmMembers)
                val listing = (outcome as? JdxService.ServiceOutcome.MemberList)?.listing
                    ?: fail("$binary: expected MemberList, got $outcome")
                val pinned = listing.copy(provenance = listOf(pinnedProvenance()))
                val key = binary.replace('.', '_')
                put("$key.txt", pinned.renderText())
                put("$key.json", pinned.toJson(command = "members"))
            }
            val outcome = JdxService.signature(
                "dev.jdx.fixtures.KotlinMembers#renamedForJvm",
                roots,
                jvmSignature,
            )
            val block = (outcome as? JdxService.ServiceOutcome.SignatureList)?.block
                ?: fail("renamedForJvm: expected SignatureList, got $outcome")
            val pinned = block.copy(provenance = listOf(pinnedProvenance()))
            put("dev_jdx_fixtures_KotlinMembers_renamedForJvm.txt", pinned.renderText())
            put("dev_jdx_fixtures_KotlinMembers_renamedForJvm.json", pinned.toJson(command = "signature"))
        }
        GoldenFiles.verifyAll(goldenDir, contents)
    }

    /** Fixed artifact label, not the real file name (the T-010 hermeticity rule). */
    private fun pinnedProvenance(): Provenance =
        Provenance(artifact = "fixture-corpus.jar", origin = Origin.BYTECODE)
}
