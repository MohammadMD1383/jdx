package dev.jdx.index.render

import dev.jdx.index.service.JdxService
import dev.jdx.index.service.JdxService.RootsSpec
import dev.jdx.index.service.JdxService.ServiceOptions
import dev.jdx.index.service.ServicesCaseJars
import dev.jdx.testsupport.golden.GoldenFiles
import java.io.File
import java.nio.file.Files
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail

/**
 * Golden tests for the services renderer (issue #25).
 *
 * Rewrite with `./gradlew :index:tier2Test -Pgolden.update=true` — then read
 * the diff before committing.
 */
@Tag("tier2")
class ServicesGoldenTest {

    @Test
    fun `text and json goldens cover pinned services`() {
        val dir = Files.createTempDirectory("services-golden-test")
        val jar = ServicesCaseJars.buildServicesCaseJar(dir)
        val roots = RootsSpec(jarSpecs = listOf(jar.toString()), includeJdk = false)
        val goldenDir = File("src/test/resources/golden/services")
        val contents = buildMap {
            fun capture(key: String, pattern: String?, options: ServiceOptions = ServiceOptions()) {
                val outcome = JdxService.services(pattern, roots, options)
                val listing = (outcome as? JdxService.ServiceOutcome.ServiceList)?.listing
                    ?: fail("services $pattern: expected ServiceList, got $outcome")
                put("$key.txt", listing.renderText())
                put("$key.json", listing.toJson(command = "services"))
            }
            capture("svc_spi", "com.example.Spi")
            capture("svc_all", null)
            capture("svc_spi_limit1", "Spi", ServiceOptions(limit = 1))
            capture("svc_provider", "Alpha")
        }
        GoldenFiles.verifyAll(goldenDir, contents)
    }
}
