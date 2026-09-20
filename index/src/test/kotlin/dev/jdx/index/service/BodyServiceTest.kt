package dev.jdx.index.service

import dev.jdx.index.service.JdxService.BodyOptions
import dev.jdx.index.service.JdxService.RootsSpec
import dev.jdx.index.service.JdxService.ServiceOutcome
import dev.jdx.testsupport.fixtures.FixtureJars
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Behaviour of `body` against real artifacts (T-022, tier 2): the fixture corpus
 * binary jar with its sibling `-sources.jar` plus the running JDK.
 *
 * Rendering itself is pinned by `BodyGoldenTest` in the render package; here the
 * assertions are structural — resolution, overload ambiguity, exit codes,
 * degradation, determinism and text⊆JSON.
 */
@Tag("tier2")
class BodyServiceTest {

    private fun fixtureRoots(): RootsSpec =
        RootsSpec(jarSpecs = listOf(FixtureJars.binaryJar().absolutePath), includeJdk = false)

    private fun textOf(outcome: ServiceOutcome): String = outcome.renderText(false)

    // -- found -----------------------------------------------------------------

    @Test
    fun `generic method body slices verbatim with provenance`() {
        val outcome = JdxService.body("dev.jdx.fixtures.Generics#identity(java.lang.Object)", fixtureRoots())
        outcome.exitCode shouldBe 0
        val text = textOf(outcome)
        text.lines().first() shouldBe "dev.jdx.fixtures.Generics#identity(java.lang.Object)"
        text shouldContain "return value;"
        text shouldContain "-sources.jar · dev/jdx/fixtures/Generics.java:"
        text shouldContain "next: jdx show dev.jdx.fixtures.Generics"
    }

    @Test
    fun `type-variable spelling resolves through the generic signature`() {
        // `U` erases to `Object` in the descriptor; the generic signature still
        // names it, so the source spelling finds the same body (D-009).
        val spelled = JdxService.body("dev.jdx.fixtures.Generics#identity(U)", fixtureRoots())
        val erased = JdxService.body("dev.jdx.fixtures.Generics#identity(java.lang.Object)", fixtureRoots())
        spelled.exitCode shouldBe 0
        textOf(spelled) shouldBe textOf(erased)
    }

    @Test
    fun `nested type body resolves through dollar nesting`() {
        val outcome = JdxService.body("dev.jdx.fixtures.Nesting\$Inner#outer()", fixtureRoots())
        outcome.exitCode shouldBe 0
        textOf(outcome) shouldContain "source: "
        textOf(outcome) shouldContain "Nesting.java"
    }

    @Test
    fun `field body slices the declaration`() {
        // `seconds()` (empty params) names the method only: fields never match
        // a parameterised ref, while bare `seconds` is ambiguous (D-016).
        val outcome = JdxService.body("dev.jdx.fixtures.TrafficLight#seconds()", fixtureRoots())
        outcome.exitCode shouldBe 0
        textOf(outcome) shouldContain "return seconds;"
    }

    @Test
    fun `constructor body matches init`() {
        // Record compact constructor: implicit components in bytecode, explicit
        // validation block in sources.
        val outcome = JdxService.body("dev.jdx.fixtures.PersonRecord#<init>(java.lang.String,int)", fixtureRoots())
        outcome.exitCode shouldBe 0
        textOf(outcome) shouldContain "if (age < 0) {"
    }

    @Test
    fun `context expands and line numbers prefix`() {
        val plain = JdxService.body("dev.jdx.fixtures.Generics#identity(java.lang.Object)", fixtureRoots())
        val expanded = JdxService.body(
            "dev.jdx.fixtures.Generics#identity(java.lang.Object)",
            fixtureRoots(),
            BodyOptions(contextLines = 2, lineNumbers = true),
        )
        expanded.exitCode shouldBe 0
        textOf(expanded) shouldContain "|"
        textOf(expanded).lines().size shouldBe textOf(plain).lines().size + 4
    }

    @Test
    fun `max lines truncates with a footer`() {
        val outcome = JdxService.body(
            "dev.jdx.fixtures.Generics#identity(java.lang.Object)",
            fixtureRoots(),
            BodyOptions(maxLines = 1),
        )
        outcome.exitCode shouldBe 0
        textOf(outcome) shouldContain "1 of 3 lines shown (--max-lines 3 to see more)"
    }

    // -- ambiguity and misses ----------------------------------------------------

    @Test
    fun `under-specified overloads exit 2 with candidates`() {
        val outcome = JdxService.body("dev.jdx.fixtures.CovariantOverrides\$Child#copy", fixtureRoots())
        outcome.exitCode shouldBe 2
        textOf(outcome) shouldContain "ambiguous:"
        textOf(outcome) shouldContain "hint: re-run with one of the refs above"
    }

    @Test
    fun `unknown member exits 1 with the query named`() {
        val outcome = JdxService.body("dev.jdx.fixtures.Generics#noSuchMethod()", fixtureRoots())
        outcome.exitCode shouldBe 1
        textOf(outcome) shouldContain "not found: dev.jdx.fixtures.Generics#noSuchMethod()"
    }

    @Test
    fun `unknown type exits 1 with did-you-mean`() {
        val outcome = JdxService.body("dev.jdx.fixtures.Generic#identity(U)", fixtureRoots())
        outcome.exitCode shouldBe 1
        textOf(outcome) shouldContain "not found:"
        textOf(outcome) shouldContain "dev.jdx.fixtures.Generics"
    }

    @Test
    fun `bridge member present only in bytecode reports no source counterpart`() {
        val outcome = JdxService.body(
            "dev.jdx.fixtures.Generics\$Recursive#compareTo(java.lang.Object)",
            fixtureRoots(),
        )
        outcome.exitCode shouldBe 1
        textOf(outcome) shouldContain "T-028"
    }

    @Test
    fun `kotlin-only type degrades naming T-039`(@TempDir tempDir: Path) {
        // A binary whose paired sources carry only the `.kt` file: names T-039.
        val binary = tempDir.resolve("case.jar")
        val sources = tempDir.resolve("case-sources.jar")
        val classBytes = ZipFile(FixtureJars.binaryJar()).use { zip ->
            zip.getInputStream(zip.getEntry("dev/jdx/fixtures/Generics.class")).readBytes()
        }
        writeJar(binary, mapOf("dev/jdx/fixtures/Generics.class" to classBytes))
        writeJar(sources, mapOf("dev/jdx/fixtures/Generics.kt" to "fun dummy(): Int = 1\n".toByteArray()))
        val roots = RootsSpec(jarSpecs = listOf(binary.toString()), includeJdk = false)
        val outcome = JdxService.body("dev.jdx.fixtures.Generics#identity(java.lang.Object)", roots)
        outcome.exitCode shouldBe 1
        textOf(outcome) shouldContain "T-039"
    }

    @Test
    fun `unpaired binary degrades naming T-026`(@TempDir tempDir: Path) {
        // Most jars ship without sources: routine exit 1, never a trace.
        val binary = tempDir.resolve("bare.jar")
        val classBytes = ZipFile(FixtureJars.binaryJar()).use { zip ->
            zip.getInputStream(zip.getEntry("dev/jdx/fixtures/Generics.class")).readBytes()
        }
        writeJar(binary, mapOf("dev/jdx/fixtures/Generics.class" to classBytes))
        val roots = RootsSpec(jarSpecs = listOf(binary.toString()), includeJdk = false)
        val outcome = JdxService.body("dev.jdx.fixtures.Generics#identity(java.lang.Object)", roots)
        outcome.exitCode shouldBe 1
        textOf(outcome) shouldContain "T-026"
        textOf(outcome) shouldNotContain "Exception"
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

    @Test
    fun `type refs are a usage error naming source`() {
        val outcome = JdxService.body("dev.jdx.fixtures.Generics", fixtureRoots())
        outcome.exitCode shouldBe 3
        textOf(outcome) shouldContain "T-023"
    }

    @Test
    fun `negative context and max-lines are usage errors`() {
        JdxService.body("dev.jdx.fixtures.Generics#identity(java.lang.Object)", fixtureRoots(), BodyOptions(contextLines = -1))
            .exitCode shouldBe 3
        JdxService.body("dev.jdx.fixtures.Generics#identity(java.lang.Object)", fixtureRoots(), BodyOptions(maxLines = -1))
            .exitCode shouldBe 3
    }

    @Test
    fun `no roots at all exits 4`() {
        val outcome = JdxService.body(
            "dev.jdx.fixtures.Generics#identity(java.lang.Object)",
            RootsSpec(jarSpecs = emptyList(), includeJdk = false),
        )
        outcome.exitCode shouldBe 4
        textOf(outcome) shouldContain "no workspace"
    }

    // -- invariants ----------------------------------------------------------------

    @Test
    fun `running twice yields identical bytes`() {
        val ref = "dev.jdx.fixtures.Generics#identity(java.lang.Object)"
        textOf(JdxService.body(ref, fixtureRoots())) shouldBe textOf(JdxService.body(ref, fixtureRoots()))
        val overloaded = "dev.jdx.fixtures.Generics#wildcard(java.util.List,java.util.List)"
        JdxService.body(overloaded, fixtureRoots()).toJson("body") shouldBe
            JdxService.body(overloaded, fixtureRoots()).toJson("body")
    }

    @Test
    fun `body json carries the envelope with sources provenance and covers the text`() {
        val outcome = JdxService.body("dev.jdx.fixtures.Generics#identity(java.lang.Object)", fixtureRoots())
        outcome.exitCode shouldBe 0
        val json = outcome.toJson("body")
        json shouldContain "\"command\":\"body\""
        json shouldContain "\"ok\":true"
        json shouldContain "\"query\":\"dev.jdx.fixtures.Generics#identity(java.lang.Object)\""
        json shouldContain "\"ref\":\"dev.jdx.fixtures.Generics#identity(java.lang.Object)\""
        json shouldContain "\"file\":\"dev/jdx/fixtures/Generics.java\""
        json shouldContain "\"lines\":["
        json shouldContain "\"artifact\":\"testfixtures-"
        json shouldContain "\"origin\":\"sources\""
        json shouldContain "return value;"
        json shouldContain "\"warnings\":["
        json shouldContain "\"provenance\":["
    }

    @Test
    fun `jdk member without src zip or with sources answers honestly`() {
        // The JDK ships src.zip on this machine: ArrayList#get answers from sources.
        // Wherever src.zip is absent this is exit 1 naming T-026 — both honest.
        val outcome = JdxService.body(
            "java.util.ArrayList#get(int)",
            RootsSpec(jarSpecs = emptyList(), includeJdk = true),
        )
        if (outcome.exitCode == 0) {
            textOf(outcome) shouldContain "source: "
        } else {
            outcome.exitCode shouldBe 1
            textOf(outcome) shouldContain "T-026"
        }
        textOf(outcome) shouldNotContain "Exception"
    }
}
