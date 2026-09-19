package dev.jdx.index.render

import dev.jdx.core.model.ClassInfo
import dev.jdx.core.model.Origin
import dev.jdx.core.model.Provenance
import dev.jdx.core.model.TypeName
import dev.jdx.core.model.typeNameFromBinaryName
import dev.jdx.core.render.MemberListingOptions
import dev.jdx.core.render.buildMemberListing
import dev.jdx.core.resolve.MemberResolver
import dev.jdx.index.asm.AsmClassReader
import dev.jdx.index.asm.ClassReadResult
import dev.jdx.testsupport.fixtures.FixtureJars
import dev.jdx.testsupport.golden.GoldenFiles
import java.io.File
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail

/**
 * Golden tests for both renderers over every fixture class (T-010).
 *
 * Each fixture class renders once as text and once as JSON; both outputs are
 * pinned to committed files under `src/test/resources/golden/members/`. The
 * lookup is the fixture corpus plus an empty `java.lang.Object` stub — hermetic
 * (no JDK dependence), so goldens are byte-identical on every machine. The
 * Object-collapse path with real members is covered in core's example tests.
 *
 * Rewrite with `./gradlew :index:tier2Test -Pgolden.update=true` — then read the
 * diff before committing it. A golden updated without reading is a test deleted
 * (TESTING.md §14). Comparison, update mode and the orphan check come from the
 * shared [GoldenFiles] helper (T-054).
 */
@Tag("tier2")
class RendererGoldenTest {

    @Test
    fun `text and json goldens cover every fixture class`() {
        val jar = FixtureJars.binaryJar()
        val names = FixtureJars.classNames(jar)
        val infos = names.associateWith { readFixtureClass(jar, it) }
        val lookup: (TypeName) -> ClassInfo? = { name ->
            infos[name.binaryName] ?: if (name.binaryName == "java.lang.Object") objectStub() else null
        }
        val goldenDir = File("src/test/resources/golden/members")
        val contents = buildMap {
            for (binaryName in names) {
                val info = infos.getValue(binaryName)
                val listing = buildMemberListing(
                    target = info,
                    resolved = MemberResolver.resolve(info, lookup),
                    // Fixed artifact label, not the real file name: the version string in
                    // `testfixtures-<version>.jar` must never leak into hermetic goldens.
                    provenance = listOf(Provenance(artifact = "fixture-corpus.jar", origin = Origin.BYTECODE)),
                    options = MemberListingOptions(),
                )
                put("$binaryName.txt", listing.renderText())
                put("$binaryName.json", listing.toJson(command = "members"))
            }
        }
        GoldenFiles.verifyAll(goldenDir, contents)
    }

    /** Raw bytes, never a class load (D-017). */
    private fun readFixtureClass(jar: File, binaryName: String): ClassInfo {
        val bytes = FixtureJars.classBytes(jar, binaryName)
        return when (val result = AsmClassReader.read(bytes)) {
            is ClassReadResult.Ok -> result.info
            is ClassReadResult.UnsupportedVersion ->
                fail("$binaryName: unsupported version (${result.warning.message})")
            is ClassReadResult.Corrupt -> fail("$binaryName: corrupt (${result.warning.message})")
        }
    }

    private fun objectStub(): ClassInfo = ClassInfo(
        name = typeNameFromBinaryName("java.lang.Object") as TypeName.ClassType,
        kind = dev.jdx.core.model.TypeKind.CLASS,
        superclass = null,
    )
}
