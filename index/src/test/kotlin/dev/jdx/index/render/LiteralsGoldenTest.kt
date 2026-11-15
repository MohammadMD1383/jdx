package dev.jdx.index.render

import dev.jdx.index.service.JdxService
import dev.jdx.index.service.JdxService.LiteralOptions
import dev.jdx.index.service.JdxService.RootsSpec
import dev.jdx.index.service.buildLiteralsCaseJar
import dev.jdx.testsupport.golden.GoldenFiles
import java.io.File
import java.nio.file.Files
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail

/**
 * Golden tests for the literals renderer (issue #86).
 *
 * Rewrite with `./gradlew :index:tier2Test -Pgolden.update=true` — then read
 * the diff before committing.
 */
@Tag("tier2")
class LiteralsGoldenTest {

    @Test
    fun `text and json goldens cover pinned literals`() {
        val dir = Files.createTempDirectory("literals-golden-test")
        val roots = RootsSpec(jarSpecs = listOf(buildLiteralsCaseJar(dir).toString()), includeJdk = false)
        val goldenDir = File("src/test/resources/golden/literals")
        val contents = buildMap {
            fun capture(key: String, pattern: String, options: LiteralOptions = LiteralOptions()) {
                val outcome = JdxService.literals(pattern, roots, options)
                val listing = (outcome as? JdxService.ServiceOutcome.LiteralList)?.listing
                    ?: fail("literals $pattern: expected LiteralList, got $outcome")
                put("$key.txt", listing.renderText())
                put("$key.json", listing.toJson(command = "literals"))
            }
            capture("lit_notification", "notification.missing")
            capture("lit_hello_ignore", "hello world", LiteralOptions(ignoreCase = true))
            capture("lit_notification_limit1", "notification", LiteralOptions(limit = 1))
            capture("lit_marker", "marker")
        }
        GoldenFiles.verifyAll(goldenDir, contents)
    }
}
