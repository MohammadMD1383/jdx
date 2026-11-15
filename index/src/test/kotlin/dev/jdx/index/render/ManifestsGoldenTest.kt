package dev.jdx.index.render

import dev.jdx.index.service.JdxService
import dev.jdx.index.service.JdxService.ManifestOptions
import dev.jdx.index.service.JdxService.RootsSpec
import dev.jdx.index.service.ManifestCaseJars
import dev.jdx.testsupport.golden.GoldenFiles
import java.io.File
import java.nio.file.Files
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail

/**
 * Golden tests for the manifest renderer (issue #25 manifest slice).
 *
 * Rewrite with `./gradlew :index:tier2Test -Pgolden.update=true` — then read
 * the diff before committing.
 */
@Tag("tier2")
class ManifestsGoldenTest {

    @Test
    fun `text and json goldens cover pinned manifests`() {
        val dir = Files.createTempDirectory("manifest-golden-test")
        val jar = ManifestCaseJars.buildManifestCaseJar(dir)
        val roots = RootsSpec(jarSpecs = listOf(jar.toString()), includeJdk = false)
        val goldenDir = File("src/test/resources/golden/manifest")
        val contents = buildMap {
            fun capture(key: String, pattern: String?, options: ManifestOptions = ManifestOptions()) {
                val outcome = JdxService.manifest(pattern, roots, options)
                val listing = (outcome as? JdxService.ServiceOutcome.ManifestList)?.listing
                    ?: fail("manifest $pattern: expected ManifestList, got $outcome")
                put("$key.txt", listing.renderText())
                put("$key.json", listing.toJson(command = "manifest"))
            }
            capture("mf_named", "Main-Class")
            capture("mf_all", null)
            capture("mf_limit2", null, ManifestOptions(limit = 2))
            capture("mf_value", "CaseLib")
        }
        GoldenFiles.verifyAll(goldenDir, contents)
    }
}
