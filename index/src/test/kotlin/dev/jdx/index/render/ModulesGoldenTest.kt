package dev.jdx.index.render

import dev.jdx.index.service.JdxService
import dev.jdx.index.service.JdxService.ModuleOptions
import dev.jdx.index.service.JdxService.RootsSpec
import dev.jdx.index.service.ModulesCaseJars
import dev.jdx.testsupport.golden.GoldenFiles
import java.io.File
import java.nio.file.Files
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail

/**
 * Golden tests for the modules renderer (issue #25 module-info slice).
 *
 * Rewrite with `./gradlew :index:tier2Test -Pgolden.update=true` — then read
 * the diff before committing.
 */
@Tag("tier2")
class ModulesGoldenTest {

    @Test
    fun `text and json goldens cover pinned modules`() {
        val dir = Files.createTempDirectory("modules-golden-test")
        val jar = ModulesCaseJars.buildModulesCaseJar(dir)
        val roots = RootsSpec(jarSpecs = listOf(jar.toString()), includeJdk = false)
        val goldenDir = File("src/test/resources/golden/modules")
        val contents = buildMap {
            fun capture(key: String, pattern: String?, options: ModuleOptions = ModuleOptions()) {
                val outcome = JdxService.modules(pattern, roots, options)
                val listing = (outcome as? JdxService.ServiceOutcome.ModuleList)?.listing
                    ?: fail("modules $pattern: expected ModuleList, got $outcome")
                put("$key.txt", listing.renderText())
                put("$key.json", listing.toJson(command = "modules"))
            }
            capture("mod_named", "com.example.mod")
            capture("mod_all", null)
            capture("mod_limit1", "example", ModuleOptions(limit = 1))
            capture("mod_provider", "Impl")
        }
        GoldenFiles.verifyAll(goldenDir, contents)
    }
}
