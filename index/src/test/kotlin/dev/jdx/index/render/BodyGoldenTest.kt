package dev.jdx.index.render

import dev.jdx.core.model.MemberSymbolRef
import dev.jdx.core.model.Origin
import dev.jdx.core.model.Provenance
import dev.jdx.core.model.typeNameFromBinaryName
import dev.jdx.core.render.buildBodyBlock
import dev.jdx.sources.JavaBodyResult
import dev.jdx.sources.findJavaBodies
import dev.jdx.sources.openSourceRoot
import dev.jdx.testsupport.fixtures.FixtureJars
import dev.jdx.testsupport.golden.GoldenFiles
import java.io.File
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail

/**
 * Golden tests for the body renderer over real fixture sources (T-022).
 *
 * Each pinned member slices once as text and once as JSON; both outputs are pinned
 * to committed files under `src/test/resources/golden/body/`. Provenance uses the
 * fixed `fixture-sources.jar` label (never the versioned file name), so goldens are
 * byte-identical on every machine. Service-level resolution (ambiguity, degradation,
 * exit codes) is covered in `BodyServiceTest`; here the assertions are pure
 * rendering — including a `--context`/`--line-numbers` variant.
 *
 * Rewrite with `./gradlew :index:tier2Test -Pgolden.update=true` — then read the
 * diff before committing it. A golden updated without reading is a test deleted
 * (TESTING.md §14). Comparison, update mode and the orphan check come from the
 * shared [GoldenFiles] helper (T-054).
 */
@Tag("tier2")
class BodyGoldenTest {

    private data class GoldenMember(
        val binary: String,
        val name: String,
        val params: String?,
    )

    private val members = listOf(
        GoldenMember("dev.jdx.fixtures.Generics", "identity", null),
        GoldenMember("dev.jdx.fixtures.Nesting\$Inner", "outer", ""),
        GoldenMember("dev.jdx.fixtures.TrafficLight", "seconds", ""),
        GoldenMember("dev.jdx.fixtures.PersonRecord", "<init>", null),
    )

    @Test
    fun `text and json goldens cover pinned bodies`() {
        val root = openSourceRoot(FixtureJars.sourcesJar().toPath())
        try {
            val goldenDir = File("src/test/resources/golden/body")
            val contents = buildMap {
                for (member in members) {
                    val ref = MemberSymbolRef(
                        declaringType = typeNameFromBinaryName(member.binary) as dev.jdx.core.model.TypeName.ClassType,
                        name = member.name,
                        parameterTypes = member.params?.let { parseParams(it) },
                    )
                    val result = findJavaBodies(root, ref)
                    val found = (result as? JavaBodyResult.Found)
                        ?: fail("${member.binary}#${member.name}: expected Found, got $result")
                    val body = found.bodies.singleOrNull()
                        ?: fail("${member.binary}#${member.name}: expected one body, got ${found.bodies.size}")
                    val fileLines = root.openSource(body.file).use {
                        it.readBytes().toString(Charsets.UTF_8).split('\n')
                            .map { line -> line.removeSuffix("\r") }
                    }
                    val provenance = listOf(
                        Provenance(
                            artifact = "fixture-sources.jar",
                            origin = Origin.SOURCES,
                            file = body.file,
                            lineRange = body.startLine..body.endLine,
                        ),
                    )
                    val plain = buildBodyBlock(
                        canonicalRef = "${member.binary}#${member.name}${member.params?.let { "($it)" } ?: ""}",
                        declaringType = member.binary,
                        file = body.file,
                        fileLines = fileLines,
                        startLine = body.startLine,
                        endLine = body.endLine,
                        provenance = provenance,
                    )
                    // Golden keys must survive a Windows checkout: `<init>` and
                    // friends carry `<`/`>`, which Win32 forbids in file names
                    // (the v1.2.0 release matrix proved it — checkout fails
                    // before any step runs). Flatten those, keep this suite's
                    // dotted naming otherwise (`#` and `.` are Win32-legal).
                    val key = "${member.binary.replace('$', '_')}#${member.name}"
                        .replace(Regex("[^A-Za-z0-9_.#]"), "_")
                    put("$key.txt", plain.renderText())
                    put("$key.json", plain.toJson(command = "body"))
                    if (member == members.first()) {
                        val dressed = buildBodyBlock(
                            canonicalRef = "${member.binary}#${member.name}",
                            declaringType = member.binary,
                            file = body.file,
                            fileLines = fileLines,
                            startLine = body.startLine,
                            endLine = body.endLine,
                            provenance = provenance,
                            contextLines = 1,
                            lineNumbers = true,
                        )
                        put("$key-context.txt", dressed.renderText())
                        put("$key-context.json", dressed.toJson(command = "body"))
                    }
                }
            }
            GoldenFiles.verifyAll(goldenDir, contents)
        } finally {
            runCatching { root.close() }
        }
    }

    /** Empty string means an explicitly zero-parameter list; `null` means under-specified. */
    private fun parseParams(params: String): List<dev.jdx.core.model.TypeName> {
        if (params.isEmpty()) return emptyList()
        return params.split(",").map {
            typeNameFromBinaryName(it.trim())
        }
    }
}
