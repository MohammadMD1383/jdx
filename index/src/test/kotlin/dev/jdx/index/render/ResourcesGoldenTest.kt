package dev.jdx.index.render

import dev.jdx.index.service.JdxService
import dev.jdx.index.service.JdxService.ResourceOptions
import dev.jdx.index.service.JdxService.RootsSpec
import dev.jdx.index.service.ResourcesCaseJars
import dev.jdx.testsupport.golden.GoldenFiles
import java.io.File
import java.nio.file.Files
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail

/**
 * Golden tests for the resources renderer (issue #25 resource-listing slice).
 *
 * Rewrite with `./gradlew :index:tier2Test -Pgolden.update=true` — then read
 * the diff before committing.
 */
@Tag("tier2")
class ResourcesGoldenTest {

    @Test
    fun `text and json goldens cover pinned resources`() {
        val dir = Files.createTempDirectory("resources-golden-test")
        val jar = ResourcesCaseJars.buildResourcesCaseJar(dir)
        val roots = RootsSpec(jarSpecs = listOf(jar.toString()), includeJdk = false)
        val goldenDir = File("src/test/resources/golden/resources")
        val contents = buildMap {
            fun capture(key: String, pattern: String?, options: ResourceOptions = ResourceOptions()) {
                val outcome = JdxService.resources(pattern, roots, options)
                val listing = (outcome as? JdxService.ServiceOutcome.ResourceList)?.listing
                    ?: fail("resources $pattern: expected ResourceList, got $outcome")
                put("$key.txt", listing.renderText())
                put("$key.json", listing.toJson(command = "resources"))
            }
            capture("res_named", "messages.properties")
            capture("res_all", null)
            capture("res_limit2", null, ResourceOptions(limit = 2))
            capture("res_meta", "META-INF")
        }
        GoldenFiles.verifyAll(goldenDir, contents)
    }
}
