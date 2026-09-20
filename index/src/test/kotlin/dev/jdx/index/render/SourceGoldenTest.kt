package dev.jdx.index.render

import dev.jdx.core.model.Origin
import dev.jdx.core.model.Provenance
import dev.jdx.core.render.buildSourceBlock
import dev.jdx.index.service.JdxService
import dev.jdx.sources.openSourceRoot
import dev.jdx.testsupport.fixtures.FixtureJars
import dev.jdx.testsupport.golden.GoldenFiles
import java.io.File
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Golden tests for the source renderer over real fixture sources (T-023).
 *
 * Each pinned type serves once as text and once as JSON; both outputs are
 * pinned to committed files under `src/test/resources/golden/source/`.
 * Provenance uses the fixed `fixture-sources.jar` label (never the versioned
 * file name), so goldens are byte-identical on every machine. Service-level
 * resolution (ambiguity, degradation, exit codes) is covered in
 * `SourceServiceTest`; here the assertions are pure rendering — including a
 * `--lines` window and a `--line-numbers` variant.
 *
 * Rewrite with `./gradlew :index:tier2Test -Pgolden.update=true` — then read the
 * diff before committing it. A golden updated without reading is a test deleted
 * (TESTING.md §14). Comparison, update mode and the orphan check come from the
 * shared [GoldenFiles] helper (T-054).
 */
@Tag("tier2")
class SourceGoldenTest {

    private val types = listOf(
        "dev.jdx.fixtures.Generics",
        "dev.jdx.fixtures.Nesting\$Inner",
        "dev.jdx.fixtures.TrafficLight",
    )

    @Test
    fun `text and json goldens cover pinned sources`() {
        val root = openSourceRoot(FixtureJars.sourcesJar().toPath())
        try {
            val goldenDir = File("src/test/resources/golden/source")
            val contents = buildMap {
                for (binary in types) {
                    val path = root.findSource(binary)
                        ?: throw AssertionError("$binary: expected a source file")
                    // The service's own line reader (trailing-newline drop
                    // included), so these goldens pin exactly what `source`
                    // serves rather than a parallel splitting rule.
                    val fileLines = JdxService.readSourceLines(root, path)
                        ?: throw AssertionError("$binary: unreadable source $path")
                    val provenance = listOf(
                        Provenance(
                            artifact = "fixture-sources.jar",
                            origin = Origin.SOURCES,
                            file = path,
                            lineRange = 1..fileLines.size,
                        ),
                    )
                    val plain = buildSourceBlock(
                        canonicalRef = binary,
                        declaringType = binary,
                        file = path,
                        fileLines = fileLines,
                        startLine = 1,
                        endLine = fileLines.size,
                        provenance = provenance,
                    )
                    val key = binary.replace('$', '_')
                    put("$key.txt", plain.renderText())
                    put("$key.json", plain.toJson(command = "source"))
                    if (binary == types.first()) {
                        val windowed = buildSourceBlock(
                            canonicalRef = binary,
                            declaringType = binary,
                            file = path,
                            fileLines = fileLines,
                            startLine = 22,
                            endLine = 24,
                            provenance = listOf(
                                Provenance(
                                    artifact = "fixture-sources.jar",
                                    origin = Origin.SOURCES,
                                    file = path,
                                    lineRange = 22..24,
                                ),
                            ),
                            lineNumbers = true,
                        )
                        put("$key-lines.txt", windowed.renderText())
                        put("$key-lines.json", windowed.toJson(command = "source"))
                    }
                }
            }
            GoldenFiles.verifyAll(goldenDir, contents)
        } finally {
            runCatching { root.close() }
        }
    }
}
