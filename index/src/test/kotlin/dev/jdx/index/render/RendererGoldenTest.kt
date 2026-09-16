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
import java.io.File
import java.util.zip.ZipFile
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
 * (TESTING.md §14). T-054 promotes this helper to shared infrastructure.
 */
@Tag("tier2")
class RendererGoldenTest {

    @Test
    fun `text and json goldens cover every fixture class`() {
        val jar = fixtureBinaryJar()
        val names = fixtureClassNames(jar)
        val infos = names.associateWith { readFixtureClass(jar, it) }
        val lookup: (TypeName) -> ClassInfo? = { name ->
            infos[name.binaryName] ?: if (name.binaryName == "java.lang.Object") objectStub() else null
        }
        val goldenDir = File("src/test/resources/golden/members")
        var updated = 0
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
            updated += checkGolden(goldenDir, "$binaryName.txt", listing.renderText())
            updated += checkGolden(goldenDir, "$binaryName.json", listing.toJson(command = "members"))
        }
        failOnOrphans(goldenDir, names.flatMap { listOf("$it.txt", "$it.json") }.toSet())
        if (updateMode()) println("golden.update: rewrote $updated file(s) under $goldenDir")
    }

    private fun checkGolden(dir: File, fileName: String, actual: String): Int {
        val file = File(dir, fileName)
        if (updateMode()) {
            file.parentFile.mkdirs()
            file.writeText(actual + "\n")
            return 1
        }
        if (!file.isFile) fail("missing golden file: ${file.path} (run with -Pgolden.update=true to create it)")
        val expected = file.readText().removeSuffix("\n")
        if (expected != actual) {
            fail("golden mismatch: ${file.path}\n${unifiedDiff(expected, actual)}")
        }
        return 0
    }

    private fun failOnOrphans(dir: File, expected: Set<String>) {
        if (!dir.isDirectory) return
        val orphans = dir.listFiles { file -> file.isFile }!!.map { it.name }.toSet() - expected
        if (orphans.isNotEmpty()) {
            fail("orphaned golden files (no fixture references them): ${orphans.sorted()}")
        }
    }

    /** First divergence with two lines of context either side — T-054 brings unified diff. */
    private fun unifiedDiff(expected: String, actual: String): String {
        val expectedLines = expected.lines()
        val actualLines = actual.lines()
        val diverge = expectedLines.zip(actualLines).indexOfFirst { (a, b) -> a != b }
            .takeIf { it != -1 } ?: minOf(expectedLines.size, actualLines.size)
        fun window(lines: List<String>): String = ((diverge - 2)..(diverge + 2))
            .filter { it in lines.indices }
            .joinToString("\n") { i -> "${i + 1}: ${lines[i]}" }
        return "first divergence at line ${diverge + 1}:\nexpected:\n${window(expectedLines)}\nactual:\n${window(actualLines)}"
    }

    private fun updateMode(): Boolean = System.getProperty("jdx.golden.update") == "true"

    private fun fixtureBinaryJar(): File {
        val dir = System.getProperty("jdx.fixturesDir")?.let { File(it) }
            ?: fail("jdx.fixturesDir not set (index build wires it; see index/build.gradle.kts)")
        val jars = dir.listFiles { file ->
            file.isFile && file.name.startsWith("testfixtures-") && !file.name.endsWith("-sources.jar")
        }?.toList().orEmpty()
        if (jars.size != 1) fail("expected exactly one binary fixture jar in $dir, found: $jars")
        return jars.single()
    }

    private fun fixtureClassNames(jar: File): List<String> = ZipFile(jar).use { zip ->
        zip.entries().asSequence()
            .map { it.name }
            .filter { it.endsWith(".class") && it != "module-info.class" }
            .map { it.removeSuffix(".class").replace('/', '.') }
            .sorted()
            .toList()
    }

    /** Raw bytes, never a class load (D-017). */
    private fun readFixtureClass(jar: File, binaryName: String): ClassInfo {
        val entry = binaryName.replace('.', '/') + ".class"
        val bytes = ZipFile(jar).use { zip -> zip.getInputStream(zip.getEntry(entry)).readBytes() }
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
