package dev.jdx.index.service

import dev.jdx.index.service.JdxService.RootsSpec
import dev.jdx.index.service.JdxService.ServiceOutcome
import dev.jdx.testsupport.fixtures.FixtureJars
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * `SOURCES_VERSION_MISMATCH` detection (T-028, tier 2): matched fixture jars
 * stay silent across shapes (generics, records, enums, annotations, nesting),
 * while crafted stale `-sources.jar`s warn on success and name the code on
 * failure — exit codes unchanged either way.
 */
@Tag("tier2")
class SourcesMismatchServiceTest {

    private fun fixtureRoots(): RootsSpec =
        RootsSpec(jarSpecs = listOf(FixtureJars.binaryJar().absolutePath), includeJdk = false)

    private fun textOf(outcome: ServiceOutcome): String = outcome.renderText(false)

    private fun realSourceText(path: String): String =
        ZipFile(FixtureJars.sourcesJar()).use { zip ->
            zip.getInputStream(zip.getEntry(path)).readBytes().toString(StandardCharsets.UTF_8)
        }

    private fun stalePair(tempDir: Path, sourcesText: String): RootsSpec {
        val binary = tempDir.resolve("case.jar")
        val sources = tempDir.resolve("case-sources.jar")
        val classBytes = ZipFile(FixtureJars.binaryJar()).use { zip ->
            zip.getInputStream(zip.getEntry("dev/jdx/fixtures/Generics.class")).readBytes()
        }
        writeJar(binary, mapOf("dev/jdx/fixtures/Generics.class" to classBytes))
        writeJar(sources, mapOf("dev/jdx/fixtures/Generics.java" to sourcesText.toByteArray()))
        return RootsSpec(jarSpecs = listOf(binary.toString()), includeJdk = false)
    }

    private fun writeJar(jar: Path, entries: Map<String, ByteArray>) {
        ZipOutputStream(Files.newOutputStream(jar)).use { zip ->
            entries.entries.sortedBy { it.key }.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
    }

    // -- matched jars stay silent ------------------------------------------------

    @Test
    fun `matched bodies carry no mismatch warning`() {
        val bodies = listOf(
            "dev.jdx.fixtures.Generics#identity(java.lang.Object)",
            "dev.jdx.fixtures.TrafficLight#seconds()",
            "dev.jdx.fixtures.PersonRecord#<init>(java.lang.String,int)",
            "dev.jdx.fixtures.Annos#tagged()",
            "dev.jdx.fixtures.Nesting\$Inner#outer()",
        )
        for (ref in bodies) {
            val outcome = JdxService.body(ref, fixtureRoots())
            outcome.exitCode shouldBe 0
            textOf(outcome) shouldNotContain "SOURCES_VERSION_MISMATCH"
            outcome.toJson("body") shouldNotContain "SOURCES_VERSION_MISMATCH"
        }
    }

    @Test
    fun `matched sources carry no mismatch warning`() {
        // Matrix/Tag are excluded: they share Annos.java with siblings, and
        // file lookup is outer-file only — a pre-existing mapping limit,
        // unrelated to mismatch detection.
        val types = listOf(
            "dev.jdx.fixtures.Generics",
            "dev.jdx.fixtures.TrafficLight",
            "dev.jdx.fixtures.PersonRecord",
            "dev.jdx.fixtures.Annos",
            "dev.jdx.fixtures.Nesting\$Inner",
        )
        for (ref in types) {
            val outcome = JdxService.source(ref, fixtureRoots())
            outcome.exitCode shouldBe 0
            textOf(outcome) shouldNotContain "SOURCES_VERSION_MISMATCH"
            outcome.toJson("source") shouldNotContain "SOURCES_VERSION_MISMATCH"
        }
    }

    @Test
    fun `matched docs carry no mismatch warning`() {
        val types = listOf(
            "dev.jdx.fixtures.Generics",
            "dev.jdx.fixtures.TrafficLight",
            "dev.jdx.fixtures.PersonRecord",
        )
        for (ref in types) {
            val outcome = JdxService.doc(ref, fixtureRoots())
            outcome.exitCode shouldBe 0
            textOf(outcome) shouldNotContain "SOURCES_VERSION_MISMATCH"
            outcome.toJson("doc") shouldNotContain "SOURCES_VERSION_MISMATCH"
        }
    }

    // -- stale jars warn on success ----------------------------------------------

    @Test
    fun `an added source member warns on body source and doc`(@TempDir tempDir: Path) {
        val stale = realSourceText("dev/jdx/fixtures/Generics.java")
            .replace("    public U identity(U value) {", "    public void brandNew() {\n    }\n\n    public U identity(U value) {")
        val roots = stalePair(tempDir, stale)

        val body = JdxService.body("dev.jdx.fixtures.Generics#identity(java.lang.Object)", roots)
        body.exitCode shouldBe 0
        textOf(body) shouldContain "warning SOURCES_VERSION_MISMATCH"
        textOf(body) shouldContain "declares dev.jdx.fixtures.Generics#brandNew()"
        body.toJson("body") shouldContain "\"code\":\"SOURCES_VERSION_MISMATCH\""

        val source = JdxService.source("dev.jdx.fixtures.Generics", roots)
        source.exitCode shouldBe 0
        textOf(source) shouldContain "warning SOURCES_VERSION_MISMATCH"
        source.toJson("source") shouldContain "\"code\":\"SOURCES_VERSION_MISMATCH\""

        val around = JdxService.source(
            "dev.jdx.fixtures.Generics",
            roots,
            JdxService.SourceOptions(aroundRef = "dev.jdx.fixtures.Generics#identity(java.lang.Object)"),
        )
        around.exitCode shouldBe 0
        textOf(around) shouldContain "warning SOURCES_VERSION_MISMATCH"

        val doc = JdxService.doc("dev.jdx.fixtures.Generics", roots)
        doc.exitCode shouldBe 0
        textOf(doc) shouldContain "warning SOURCES_VERSION_MISMATCH"
        doc.toJson("doc") shouldContain "\"code\":\"SOURCES_VERSION_MISMATCH\""
    }

    @Test
    fun `a removed source member warns on the surviving body`(@TempDir tempDir: Path) {
        // `identity` deleted from sources; `wildcard` still answers — with the
        // omission named.
        val stale = realSourceText("dev/jdx/fixtures/Generics.java")
            .replace("    public U identity(U value) {\n        return value;\n    }\n\n", "")
        val roots = stalePair(tempDir, stale)

        val missing = JdxService.body("dev.jdx.fixtures.Generics#identity(java.lang.Object)", roots)
        missing.exitCode shouldBe 1
        textOf(missing) shouldContain "SOURCES_VERSION_MISMATCH"

        val surviving = JdxService.body(
            "dev.jdx.fixtures.Generics#wildcard(java.util.List,java.util.List)",
            roots,
        )
        surviving.exitCode shouldBe 0
        textOf(surviving) shouldContain "warning SOURCES_VERSION_MISMATCH"
        textOf(surviving) shouldContain "omits dev.jdx.fixtures.Generics#identity(Object)"
    }

    @Test
    fun `a renamed source member warns on both sides`(@TempDir tempDir: Path) {
        val stale = realSourceText("dev/jdx/fixtures/Generics.java")
            .replace("public U identity(U value)", "public U renamed(U value)")
        val roots = stalePair(tempDir, stale)

        val outcome = JdxService.body(
            "dev.jdx.fixtures.Generics#wildcard(java.util.List,java.util.List)",
            roots,
        )
        outcome.exitCode shouldBe 0
        val text = textOf(outcome)
        text shouldContain "warning SOURCES_VERSION_MISMATCH"
        text shouldContain "declares dev.jdx.fixtures.Generics#renamed(U)"
        text shouldContain "omits dev.jdx.fixtures.Generics#identity(Object)"
    }

    // -- invariants ----------------------------------------------------------------

    @Test
    fun `warned answers are deterministic with text covered by json`(@TempDir tempDir: Path) {
        val stale = realSourceText("dev/jdx/fixtures/Generics.java")
            .replace("    public U identity(U value) {", "    public void brandNew() {\n    }\n\n    public U identity(U value) {")
        val roots = stalePair(tempDir, stale)
        val ref = "dev.jdx.fixtures.Generics#identity(java.lang.Object)"
        textOf(JdxService.body(ref, roots)) shouldBe textOf(JdxService.body(ref, roots))
        JdxService.body(ref, roots).toJson("body") shouldBe JdxService.body(ref, roots).toJson("body")
        val text = textOf(JdxService.body(ref, roots))
        val json = JdxService.body(ref, roots).toJson("body")
        // Every text fact is structural in JSON (D-007): the warning line and
        // the member it names both appear as the coded warning.
        text shouldContain "warning SOURCES_VERSION_MISMATCH"
        json shouldContain "\"code\":\"SOURCES_VERSION_MISMATCH\""
        json shouldContain "brandNew"
    }
}
