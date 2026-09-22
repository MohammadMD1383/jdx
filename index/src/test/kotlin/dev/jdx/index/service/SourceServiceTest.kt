package dev.jdx.index.service

import dev.jdx.decompile.DecompileCache
import dev.jdx.decompile.DecompileResult
import dev.jdx.decompile.DecompilerEngine
import dev.jdx.decompile.DecompilerId
import dev.jdx.decompile.JavapDecompiler
import dev.jdx.decompile.JavapEnvironment
import dev.jdx.decompile.VineflowerDecompiler
import dev.jdx.index.service.JdxService.RootsSpec
import dev.jdx.index.service.JdxService.ServiceOutcome
import dev.jdx.index.service.JdxService.SourceOptions
import dev.jdx.testsupport.fixtures.FixtureJars
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Behaviour of `source` against real artifacts (T-023, tier 2): the fixture
 * corpus binary jar with its sibling `-sources.jar` plus the running JDK.
 *
 * Rendering itself is pinned by `SourceGoldenTest` in the render package; here
 * the assertions are structural — resolution, windows, exit codes,
 * degradation, determinism and text⊆JSON.
 */
@Tag("tier2")
class SourceServiceTest {

    private fun fixtureRoots(): RootsSpec =
        RootsSpec(jarSpecs = listOf(FixtureJars.binaryJar().absolutePath), includeJdk = false)

    private fun textOf(outcome: ServiceOutcome): String = outcome.renderText(false)

    /** A failing reconstruction engine that counts its invocations. */
    private class FailingDecompiler(val message: String = "boom") : DecompilerEngine {
        override val id: DecompilerId = DecompilerId.VINEFLOWER
        var calls: Int = 0
        override fun decompileClass(
            classBytes: ByteArray,
            binaryName: String,
            classpath: List<Path>,
        ): DecompileResult {
            calls++
            return DecompileResult.Failed(message)
        }
    }

    private fun tempEngine(tempDir: Path): VineflowerDecompiler =
        VineflowerDecompiler(DecompileCache(tempDir.resolve("decompile-cache")))

    private fun bareJar(tempDir: Path, name: String = "bare.jar"): Path {
        val binary = tempDir.resolve(name)
        val classBytes = ZipFile(FixtureJars.binaryJar()).use { zip ->
            zip.getInputStream(zip.getEntry("dev/jdx/fixtures/Generics.class")).readBytes()
        }
        writeJar(binary, mapOf("dev/jdx/fixtures/Generics.class" to classBytes))
        return binary
    }

    // -- found -----------------------------------------------------------------

    @Test
    fun `whole file serves verbatim with provenance`() {
        val outcome = JdxService.source("dev.jdx.fixtures.Generics", fixtureRoots())
        outcome.exitCode shouldBe 0
        val text = textOf(outcome)
        text.lines().first() shouldBe "dev.jdx.fixtures.Generics"
        text shouldContain "source: "
        text shouldContain "-sources.jar · dev/jdx/fixtures/Generics.java:1-38"
        text shouldContain "package dev.jdx.fixtures;"
        text shouldContain "public U identity(U value) {"
        text shouldContain "next: jdx show dev.jdx.fixtures.Generics"
    }

    @Test
    fun `same-file sibling serves the shared file`() {
        val outcome = JdxService.source("dev.jdx.fixtures.Tag", fixtureRoots())
        outcome.exitCode shouldBe 0
        textOf(outcome) shouldContain "dev/jdx/fixtures/Annos.java:1-"
    }

    @Test
    fun `nested type serves the outer file`() {
        val outcome = JdxService.source("dev.jdx.fixtures.Nesting\$Inner", fixtureRoots())
        outcome.exitCode shouldBe 0
        textOf(outcome) shouldContain "dev/jdx/fixtures/Nesting.java:1-"
    }

    @Test
    fun `lines window shows only that slice`() {
        val outcome = JdxService.source(
            "dev.jdx.fixtures.Generics",
            fixtureRoots(),
            SourceOptions(lines = 22 to 24),
        )
        outcome.exitCode shouldBe 0
        val text = textOf(outcome)
        text shouldContain "Generics.java:22-24"
        text shouldContain "public U identity(U value) {"
        text shouldNotContain "package dev.jdx.fixtures;"
    }

    @Test
    fun `lines window clamps the end to the file`() {
        val outcome = JdxService.source(
            "dev.jdx.fixtures.Generics",
            fixtureRoots(),
            SourceOptions(lines = 36 to 1000),
        )
        outcome.exitCode shouldBe 0
        textOf(outcome) shouldContain "Generics.java:36-38"
    }

    @Test
    fun `lines beyond the file exit 1 naming the range`() {
        val outcome = JdxService.source(
            "dev.jdx.fixtures.Generics",
            fixtureRoots(),
            SourceOptions(lines = 1000 to 1001),
        )
        outcome.exitCode shouldBe 1
        textOf(outcome) shouldContain "not found:"
        textOf(outcome) shouldContain "beyond"
        textOf(outcome) shouldContain "38 lines"
    }

    @Test
    fun `around centers on the member with erased and spelled queries agreeing`() {
        val ref = "dev.jdx.fixtures.Generics"
        val erased = JdxService.source(
            ref,
            fixtureRoots(),
            SourceOptions(aroundRef = "dev.jdx.fixtures.Generics#identity(java.lang.Object)"),
        )
        erased.exitCode shouldBe 0
        textOf(erased) shouldContain "public U identity(U value) {"
        textOf(erased) shouldContain "Generics.java:22-24"
        textOf(erased) shouldNotContain "package dev.jdx.fixtures;"
        // The generic signature's own spelling finds the same slice (D-009).
        val spelled = JdxService.source(
            ref,
            fixtureRoots(),
            SourceOptions(aroundRef = "dev.jdx.fixtures.Generics#identity(U)"),
        )
        spelled.exitCode shouldBe 0
        textOf(spelled) shouldBe textOf(erased)
    }

    @Test
    fun `around context expands the slice`() {
        val ref = "dev.jdx.fixtures.Generics"
        val around = "dev.jdx.fixtures.Generics#identity(java.lang.Object)"
        val plain = JdxService.source(ref, fixtureRoots(), SourceOptions(aroundRef = around))
        val expanded = JdxService.source(
            ref,
            fixtureRoots(),
            SourceOptions(aroundRef = around, contextLines = 2),
        )
        expanded.exitCode shouldBe 0
        textOf(expanded).lines().size shouldBe textOf(plain).lines().size + 4
    }

    @Test
    fun `around unknown member exits 1 naming the member`() {
        val outcome = JdxService.source(
            "dev.jdx.fixtures.Generics",
            fixtureRoots(),
            SourceOptions(aroundRef = "dev.jdx.fixtures.Generics#noSuchMember()"),
        )
        outcome.exitCode shouldBe 1
        textOf(outcome) shouldContain "not found: dev.jdx.fixtures.Generics#noSuchMember()"
    }

    @Test
    fun `around under-specified overloads exit 2 with candidates`() {
        val outcome = JdxService.source(
            "dev.jdx.fixtures.CovariantOverrides\$Child",
            fixtureRoots(),
            SourceOptions(aroundRef = "dev.jdx.fixtures.CovariantOverrides\$Child#copy"),
        )
        outcome.exitCode shouldBe 2
        textOf(outcome) shouldContain "ambiguous:"
        textOf(outcome) shouldContain "hint: re-run with one of the refs above"
    }

    @Test
    fun `line numbers prefix the display start`() {
        val outcome = JdxService.source(
            "dev.jdx.fixtures.Generics",
            fixtureRoots(),
            SourceOptions(lines = 22 to 23, lineNumbers = true),
        )
        outcome.exitCode shouldBe 0
        textOf(outcome) shouldContain "22 |     public U identity(U value) {"
    }

    @Test
    fun `max lines truncates with a footer`() {
        val outcome = JdxService.source(
            "dev.jdx.fixtures.Generics",
            fixtureRoots(),
            SourceOptions(maxLines = 2),
        )
        outcome.exitCode shouldBe 0
        textOf(outcome) shouldContain "2 of 38 lines shown (--max-lines 38 to see more)"
    }

    // -- ambiguity and misses ----------------------------------------------------

    @Test
    fun `short name with two jdk providers exits 2`() {
        // java.awt.List and java.util.List ship in every JDK; ambiguity is
        // decided from bytecode, so no sources are needed for exit 2.
        val outcome = JdxService.source("List", RootsSpec(jarSpecs = emptyList(), includeJdk = true))
        outcome.exitCode shouldBe 2
        textOf(outcome) shouldContain "ambiguous:"
        textOf(outcome) shouldContain "java.util.List"
    }

    @Test
    fun `unknown type exits 1 with did-you-mean`() {
        val outcome = JdxService.source("dev.jdx.fixtures.Generic", fixtureRoots())
        outcome.exitCode shouldBe 1
        textOf(outcome) shouldContain "not found:"
        textOf(outcome) shouldContain "dev.jdx.fixtures.Generics"
    }

    @Test
    fun `member refs are a usage error naming around`() {
        val outcome = JdxService.source("dev.jdx.fixtures.Generics#identity(java.lang.Object)", fixtureRoots())
        outcome.exitCode shouldBe 3
        textOf(outcome) shouldContain "--around"
    }

    @Test
    fun `invalid refs are usage errors`() {
        JdxService.source("dev.jdx.fixtures.Generics#", fixtureRoots()).exitCode shouldBe 3
    }

    @Test
    fun `bad windows are usage errors`() {
        JdxService.source("dev.jdx.fixtures.Generics", fixtureRoots(), SourceOptions(lines = 5 to 2))
            .exitCode shouldBe 3
        JdxService.source(
            "dev.jdx.fixtures.Generics",
            fixtureRoots(),
            SourceOptions(lines = 1 to 2, aroundRef = "dev.jdx.fixtures.Generics#identity(java.lang.Object)"),
        ).exitCode shouldBe 3
        JdxService.source(
            "dev.jdx.fixtures.Generics",
            fixtureRoots(),
            SourceOptions(lines = 1 to 2, contextLines = 1),
        ).exitCode shouldBe 3
        JdxService.source("dev.jdx.fixtures.Generics", fixtureRoots(), SourceOptions(contextLines = -1))
            .exitCode shouldBe 3
        JdxService.source("dev.jdx.fixtures.Generics", fixtureRoots(), SourceOptions(maxLines = -1))
            .exitCode shouldBe 3
    }

    @Test
    fun `unpaired binary falls back to vineflower with a reconstructed label`(@TempDir tempDir: Path) {
        // Most jars ship without sources: the ladder reconstructs (T-026) instead
        // of stopping. The temp-dir engine keeps the real user cache untouched.
        val binary = bareJar(tempDir)
        val roots = RootsSpec(jarSpecs = listOf(binary.toString()), includeJdk = false)
        val options = SourceOptions(decompiler = tempEngine(tempDir))
        val outcome = JdxService.source("dev.jdx.fixtures.Generics", roots, options)
        outcome.exitCode shouldBe 0
        val text = textOf(outcome)
        text shouldContain "decompiled by vineflower from bare.jar"
        text shouldContain "reconstructed"
        text shouldContain "class Generics"
        text shouldNotContain "-sources.jar"
        val json = outcome.toJson("source")
        json shouldContain "\"origin\":\"decompiled-vineflower\""
        json shouldContain "class Generics"
    }

    @Test
    fun `forced vineflower ignores paired sources`(@TempDir tempDir: Path) {
        val options = SourceOptions(engine = DecompilerId.VINEFLOWER, decompiler = tempEngine(tempDir))
        val outcome = JdxService.source("dev.jdx.fixtures.Generics", fixtureRoots(), options)
        outcome.exitCode shouldBe 0
        textOf(outcome) shouldContain "decompiled by vineflower"
        textOf(outcome) shouldContain "reconstructed"
        textOf(outcome) shouldNotContain "-sources.jar"
    }

    @Test
    fun `lines window slices reconstructed text`(@TempDir tempDir: Path) {
        val binary = bareJar(tempDir)
        val roots = RootsSpec(jarSpecs = listOf(binary.toString()), includeJdk = false)
        val options = SourceOptions(lines = 1 to 3, decompiler = tempEngine(tempDir))
        val outcome = JdxService.source("dev.jdx.fixtures.Generics", roots, options)
        outcome.exitCode shouldBe 0
        textOf(outcome) shouldContain "decompiled by vineflower"
        textOf(outcome) shouldContain "Generics.java:1-3"
    }

    @Test
    fun `around centers on a reconstructed member`(@TempDir tempDir: Path) {
        val binary = bareJar(tempDir)
        val roots = RootsSpec(jarSpecs = listOf(binary.toString()), includeJdk = false)
        val options = SourceOptions(
            aroundRef = "dev.jdx.fixtures.Generics#identity(java.lang.Object)",
            contextLines = 1,
            lineNumbers = true,
            decompiler = tempEngine(tempDir),
        )
        val outcome = JdxService.source("dev.jdx.fixtures.Generics", roots, options)
        outcome.exitCode shouldBe 0
        textOf(outcome) shouldContain "decompiled by vineflower"
        textOf(outcome) shouldContain "return value;"
    }

    @Test
    fun `paired sources never touch the decompiler`() {
        val fake = FailingDecompiler()
        val outcome = JdxService.source(
            "dev.jdx.fixtures.Generics",
            fixtureRoots(),
            SourceOptions(decompiler = fake),
        )
        outcome.exitCode shouldBe 0
        textOf(outcome) shouldContain "-sources.jar"
        fake.calls shouldBe 0
    }

    @Test
    fun `a doubly failing ladder exits 1 naming both causes`(@TempDir tempDir: Path) {
        // The default ladder (T-073) retries a Vineflower failure through
        // `javap`: only the double failure exits 1, naming both engines.
        val binary = bareJar(tempDir)
        val roots = RootsSpec(jarSpecs = listOf(binary.toString()), includeJdk = false)
        val vineflower = FailingDecompiler("vineflower-boom")
        val javap = FailingJavapDecompiler("javap-boom")
        val outcome = JdxService.source(
            "dev.jdx.fixtures.Generics",
            roots,
            SourceOptions(decompiler = vineflower, javapDecompiler = javap),
        )
        outcome.exitCode shouldBe 1
        vineflower.calls shouldBe 1
        javap.calls shouldBe 1
        val text = textOf(outcome)
        text shouldContain "vineflower-boom"
        text shouldContain "javap-boom"
        text shouldNotContain "Exception in thread"
        val json = outcome.toJson("source")
        json shouldContain "vineflower-boom"
        json shouldContain "javap-boom"
    }

    @Test
    fun `a failing vineflower degrades to disassembly`(@TempDir tempDir: Path) {
        // A Vineflower crash on the default ladder answers from `javap`
        // (T-073) — exit 0 with javap provenance, never the engine failure.
        assumeTrue(javapPresent(), "no javap on this machine")
        val binary = bareJar(tempDir)
        val roots = RootsSpec(jarSpecs = listOf(binary.toString()), includeJdk = false)
        val vineflower = FailingDecompiler("vineflower failed decompiling dev.jdx.fixtures.Generics: boom")
        val javap = tempJavapEngine(tempDir)
        val outcome = JdxService.source(
            "dev.jdx.fixtures.Generics",
            roots,
            SourceOptions(decompiler = vineflower, javapDecompiler = javap),
        )
        outcome.exitCode shouldBe 0
        vineflower.calls shouldBe 1
        val text = textOf(outcome)
        text shouldContain "disassembled by javap from bare.jar"
        text shouldContain "Compiled from"
        text shouldNotContain "Exception in thread"
        val json = outcome.toJson("source")
        json shouldContain "\"origin\":\"decompiled-javap\""
        json shouldContain "Compiled from"
    }

    @Test
    fun `forced vineflower stays strict on engine failure`(@TempDir tempDir: Path) {
        // Forced `--engine vineflower` never swaps engines silently (T-073):
        // its failure exits 1 naming the hatch, without touching `javap`.
        val binary = bareJar(tempDir)
        val roots = RootsSpec(jarSpecs = listOf(binary.toString()), includeJdk = false)
        val vineflower = FailingDecompiler("boom")
        val javap = FailingJavapDecompiler("unused")
        val outcome = JdxService.source(
            "dev.jdx.fixtures.Generics",
            roots,
            SourceOptions(engine = DecompilerId.VINEFLOWER, decompiler = vineflower, javapDecompiler = javap),
        )
        outcome.exitCode shouldBe 1
        vineflower.calls shouldBe 1
        javap.calls shouldBe 0
        textOf(outcome) shouldContain "--engine javap"
        textOf(outcome) shouldNotContain "Exception in thread"
    }

    @Test
    fun `decompiled sources are deterministic`(@TempDir tempDir: Path) {
        val binary = bareJar(tempDir)
        val roots = RootsSpec(jarSpecs = listOf(binary.toString()), includeJdk = false)
        val options = SourceOptions(decompiler = tempEngine(tempDir))
        val ref = "dev.jdx.fixtures.Generics"
        textOf(JdxService.source(ref, roots, options)) shouldBe textOf(JdxService.source(ref, roots, options))
        JdxService.source(ref, roots, options).toJson("source") shouldBe
            JdxService.source(ref, roots, options).toJson("source")
    }

    @Test
    fun `kotlin-only type serves the whole Kotlin file`(@TempDir tempDir: Path) {
        // Whole files need no PSI parse — only the PSI-confirmed path — so a
        // `.kt` hit serves verbatim even without a sidecar (T-039).
        val binary = tempDir.resolve("case.jar")
        val sources = tempDir.resolve("case-sources.jar")
        val classBytes = ZipFile(FixtureJars.binaryJar()).use { zip ->
            zip.getInputStream(zip.getEntry("dev/jdx/fixtures/Generics.class")).readBytes()
        }
        writeJar(binary, mapOf("dev/jdx/fixtures/Generics.class" to classBytes))
        writeJar(sources, mapOf("dev/jdx/fixtures/Generics.kt" to "fun dummy(): Int = 1\n".toByteArray()))
        val roots = RootsSpec(jarSpecs = listOf(binary.toString()), includeJdk = false)
        val outcome = JdxService.source("dev.jdx.fixtures.Generics", roots)
        outcome.exitCode shouldBe 0
        textOf(outcome) shouldContain "fun dummy(): Int = 1"
    }

    @Test
    fun `stale sources without the type degrade naming SOURCES_VERSION_MISMATCH`(@TempDir tempDir: Path) {
        // Paired sources exist but hold no file for the type: a stale or
        // mismatched sources jar, not a missing one.
        val binary = tempDir.resolve("stale.jar")
        val sources = tempDir.resolve("stale-sources.jar")
        val classBytes = ZipFile(FixtureJars.binaryJar()).use { zip ->
            zip.getInputStream(zip.getEntry("dev/jdx/fixtures/Generics.class")).readBytes()
        }
        writeJar(binary, mapOf("dev/jdx/fixtures/Generics.class" to classBytes))
        writeJar(sources, mapOf("other/Unrelated.java" to "package other;\npublic class Unrelated {}\n".toByteArray()))
        val roots = RootsSpec(jarSpecs = listOf(binary.toString()), includeJdk = false)
        val outcome = JdxService.source("dev.jdx.fixtures.Generics", roots)
        outcome.exitCode shouldBe 1
        textOf(outcome) shouldContain "SOURCES_VERSION_MISMATCH"
    }

    @Test
    fun `no roots at all exits 4`() {
        val outcome = JdxService.source(
            "dev.jdx.fixtures.Generics",
            RootsSpec(jarSpecs = emptyList(), includeJdk = false),
        )
        outcome.exitCode shouldBe 4
        textOf(outcome) shouldContain "no workspace"
    }

    @Test
    fun `querying the marker fixture never loads it (D-017)`() {
        val marker = File(System.getProperty("java.io.tmpdir"), "jdx-fixture-static-init-marker")
        marker.exists() shouldBe false
        val outcome = JdxService.source("dev.jdx.fixtures.StaticInitMarker", fixtureRoots())
        outcome.exitCode shouldBe 0
        textOf(outcome) shouldContain "public class StaticInitMarker {"
        // No shouldNotContain "Exception": the file legitimately catches
        // Exception in its own <clinit> text — the proof is the exit code
        // plus the absent marker below, not the absence of the word.
        marker.exists() shouldBe false
    }

    // -- invariants ----------------------------------------------------------------

    @Test
    fun `running twice yields identical bytes`() {
        val ref = "dev.jdx.fixtures.Generics"
        textOf(JdxService.source(ref, fixtureRoots())) shouldBe textOf(JdxService.source(ref, fixtureRoots()))
        val windowed = JdxService.source(ref, fixtureRoots(), SourceOptions(lines = 1 to 5))
        windowed.toJson("source") shouldBe
            JdxService.source(ref, fixtureRoots(), SourceOptions(lines = 1 to 5)).toJson("source")
    }

    @Test
    fun `source json carries the envelope with sources provenance and covers the text`() {
        val outcome = JdxService.source("dev.jdx.fixtures.Generics", fixtureRoots())
        outcome.exitCode shouldBe 0
        val json = outcome.toJson("source")
        json shouldContain "\"command\":\"source\""
        json shouldContain "\"ok\":true"
        json shouldContain "\"query\":\"dev.jdx.fixtures.Generics\""
        json shouldContain "\"ref\":\"dev.jdx.fixtures.Generics\""
        json shouldContain "\"file\":\"dev/jdx/fixtures/Generics.java\""
        json shouldContain "\"lines\":[1,38]"
        json shouldContain "\"artifact\":\"testfixtures-"
        json shouldContain "\"origin\":\"sources\""
        json shouldContain "package dev.jdx.fixtures;"
        json shouldContain "\"warnings\":["
        json shouldContain "\"provenance\":["
    }

    @Test
    fun `jdk type without src zip or with sources answers honestly`(@TempDir tempDir: Path) {
        // The JDK ships src.zip on this machine: ArrayList answers from sources.
        // Wherever src.zip is absent it reconstructs (T-026) — both honest.
        val outcome = JdxService.source(
            "java.util.ArrayList",
            RootsSpec(jarSpecs = emptyList(), includeJdk = true),
            SourceOptions(decompiler = tempEngine(tempDir)),
        )
        if (outcome.exitCode == 0) {
            textOf(outcome) shouldContain "source: "
        } else {
            // No src.zip and both engines down: the double failure (T-073)
            // names vineflower and javap alike.
            outcome.exitCode shouldBe 1
            textOf(outcome) shouldContain "vineflower"
            textOf(outcome) shouldContain "javap"
        }
        // Trace proxy, scoped: decompiled bodies legitimately name exception
        // types (`NoSuchElementException`, …) — only a trace header is a failure.
        textOf(outcome) shouldNotContain "Exception in thread"
    }

    // -- forced javap engine (T-027) ---------------------------------------------

    private fun tempJavapEngine(tempDir: Path): JavapDecompiler =
        JavapDecompiler(DecompileCache(tempDir.resolve("javap-cache")))

    private fun javapPresent(): Boolean =
        runCatching { JavapEnvironment.system().resolveExecutable() != null }.getOrDefault(false)

    /** A failing javap engine that counts its invocations. */
    private class FailingJavapDecompiler(val message: String = "boom") : DecompilerEngine {
        override val id: DecompilerId = DecompilerId.JAVAP
        var calls: Int = 0
        override fun decompileClass(
            classBytes: ByteArray,
            binaryName: String,
            classpath: List<Path>,
        ): DecompileResult {
            calls++
            return DecompileResult.Failed(message)
        }
    }

    @Test
    fun `forced javap serves whole disassembly with javap provenance`(@TempDir tempDir: Path) {
        assumeTrue(javapPresent(), "no javap on this machine")
        val binary = bareJar(tempDir)
        val roots = RootsSpec(jarSpecs = listOf(binary.toString()), includeJdk = false)
        val options = SourceOptions(engine = DecompilerId.JAVAP, javapDecompiler = tempJavapEngine(tempDir))
        val outcome = JdxService.source("dev.jdx.fixtures.Generics", roots, options)
        outcome.exitCode shouldBe 0
        val text = textOf(outcome)
        text.lines().first() shouldBe "dev.jdx.fixtures.Generics"
        text shouldContain "disassembled by javap from bare.jar"
        text shouldContain "reconstructed"
        text shouldContain "Compiled from"
        text shouldNotContain "Exception in thread"
        val json = outcome.toJson("source")
        json shouldContain "\"origin\":\"decompiled-javap\""
        json shouldContain "Compiled from"
    }

    @Test
    fun `lines window slices the disassembly`(@TempDir tempDir: Path) {
        assumeTrue(javapPresent(), "no javap on this machine")
        val binary = bareJar(tempDir)
        val roots = RootsSpec(jarSpecs = listOf(binary.toString()), includeJdk = false)
        val options = SourceOptions(
            lines = 1 to 2,
            engine = DecompilerId.JAVAP,
            javapDecompiler = tempJavapEngine(tempDir),
        )
        val outcome = JdxService.source("dev.jdx.fixtures.Generics", roots, options)
        outcome.exitCode shouldBe 0
        textOf(outcome) shouldContain "Compiled from"
    }

    @Test
    fun `around centers on a disassembled member`(@TempDir tempDir: Path) {
        assumeTrue(javapPresent(), "no javap on this machine")
        val binary = bareJar(tempDir)
        val roots = RootsSpec(jarSpecs = listOf(binary.toString()), includeJdk = false)
        val options = SourceOptions(
            aroundRef = "dev.jdx.fixtures.Generics#identity(java.lang.Object)",
            contextLines = 1,
            lineNumbers = true,
            engine = DecompilerId.JAVAP,
            javapDecompiler = tempJavapEngine(tempDir),
        )
        val outcome = JdxService.source("dev.jdx.fixtures.Generics", roots, options)
        outcome.exitCode shouldBe 0
        textOf(outcome) shouldContain "disassembled by javap"
        textOf(outcome) shouldContain "descriptor: (Ljava/lang/Object;)Ljava/lang/Object;"
    }

    @Test
    fun `a failing javap engine exits 1 naming disassembly`(@TempDir tempDir: Path) {
        val binary = bareJar(tempDir)
        val roots = RootsSpec(jarSpecs = listOf(binary.toString()), includeJdk = false)
        val fake = FailingJavapDecompiler()
        val outcome = JdxService.source(
            "dev.jdx.fixtures.Generics",
            roots,
            SourceOptions(engine = DecompilerId.JAVAP, javapDecompiler = fake),
        )
        outcome.exitCode shouldBe 1
        fake.calls shouldBe 1
        textOf(outcome) shouldContain "could not disassemble"
        textOf(outcome) shouldNotContain "Exception in thread"
    }

    @Test
    fun `disassembled sources are deterministic`(@TempDir tempDir: Path) {
        assumeTrue(javapPresent(), "no javap on this machine")
        val binary = bareJar(tempDir)
        val roots = RootsSpec(jarSpecs = listOf(binary.toString()), includeJdk = false)
        val options = SourceOptions(engine = DecompilerId.JAVAP, javapDecompiler = tempJavapEngine(tempDir))
        val ref = "dev.jdx.fixtures.Generics"
        textOf(JdxService.source(ref, roots, options)) shouldBe textOf(JdxService.source(ref, roots, options))
        JdxService.source(ref, roots, options).toJson("source") shouldBe
            JdxService.source(ref, roots, options).toJson("source")
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
}
