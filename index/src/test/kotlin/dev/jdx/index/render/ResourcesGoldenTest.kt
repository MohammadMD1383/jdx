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
 * Golden tests for the resources renderer (issue #25 listing + `--cat`).
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
            fun captureList(key: String, pattern: String?, options: ResourceOptions = ResourceOptions()) {
                val outcome = JdxService.resources(pattern, roots, options)
                val listing = (outcome as? JdxService.ServiceOutcome.ResourceList)?.listing
                    ?: fail("resources $pattern: expected ResourceList, got $outcome")
                put("$key.txt", listing.renderText())
                put("$key.json", listing.toJson(command = "resources"))
            }
            fun captureCat(key: String, pattern: String, options: ResourceOptions) {
                val outcome = JdxService.resources(pattern, roots, options)
                val content = (outcome as? JdxService.ServiceOutcome.ResourceCat)?.content
                    ?: fail("resources $pattern --cat: expected ResourceCat, got $outcome")
                put("$key.txt", content.renderText())
                put("$key.json", content.toJson(command = "resources"))
            }
            captureList("res_named", "messages.properties")
            captureList("res_all", null)
            captureList("res_limit2", null, ResourceOptions(limit = 2))
            captureList("res_meta", "META-INF")
            captureCat(
                "res_cat",
                "com/example/messages.properties",
                ResourceOptions(cat = true),
            )
            captureCat(
                "res_cat_bin",
                "blob.bin",
                ResourceOptions(cat = true),
            )
        }
        GoldenFiles.verifyAll(goldenDir, contents)
    }
}
