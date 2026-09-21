package dev.jdx.index.service

import dev.jdx.decompile.DecompileCache
import dev.jdx.decompile.DecompileResult
import dev.jdx.decompile.DecompilerEngine
import dev.jdx.decompile.DecompilerId
import dev.jdx.decompile.JavapDecompiler
import dev.jdx.decompile.JavapEnvironment
import dev.jdx.decompile.VineflowerDecompiler
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
import org.junit.jupiter.api.Assumptions.assumeTrue
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

    /** A scripted reconstruction engine returning fixed text. */
    private class ScriptedDecompiler(val text: String) : DecompilerEngine {
        override val id: DecompilerId = DecompilerId.VINEFLOWER
        var calls: Int = 0
        override fun decompileClass(
            classBytes: ByteArray,
            binaryName: String,
            classpath: List<Path>,
        ): DecompileResult {
            calls++
            return DecompileResult.Decompiled(text, "test")
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
        textOf(outcome) shouldContain "SOURCES_VERSION_MISMATCH"
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
    fun `unpaired binary falls back to vineflower with a reconstructed label`(@TempDir tempDir: Path) {
        // Most jars ship without sources: the ladder reconstructs (T-026) instead
        // of stopping. The temp-dir engine keeps the real user cache untouched.
        val binary = bareJar(tempDir)
        val roots = RootsSpec(jarSpecs = listOf(binary.toString()), includeJdk = false)
        val options = BodyOptions(decompiler = tempEngine(tempDir))
        val outcome = JdxService.body("dev.jdx.fixtures.Generics#identity(java.lang.Object)", roots, options)
        outcome.exitCode shouldBe 0
        val text = textOf(outcome)
        text shouldContain "return value;"
        text shouldContain "decompiled by vineflower from bare.jar"
        text shouldContain "reconstructed"
        text shouldNotContain "-sources.jar"
        val json = outcome.toJson("body")
        json shouldContain "\"origin\":\"decompiled-vineflower\""
        json shouldContain "return value;"
    }

    @Test
    fun `forced vineflower ignores paired sources`(@TempDir tempDir: Path) {
        val options = BodyOptions(engine = DecompilerId.VINEFLOWER, decompiler = tempEngine(tempDir))
        val outcome = JdxService.body(
            "dev.jdx.fixtures.Generics#identity(java.lang.Object)",
            fixtureRoots(),
            options,
        )
        outcome.exitCode shouldBe 0
        textOf(outcome) shouldContain "decompiled by vineflower"
        textOf(outcome) shouldContain "reconstructed"
        textOf(outcome) shouldNotContain "-sources.jar"
    }

    @Test
    fun `paired sources never touch the decompiler`() {
        val fake = ScriptedDecompiler("unused")
        val outcome = JdxService.body(
            "dev.jdx.fixtures.Generics#identity(java.lang.Object)",
            fixtureRoots(),
            BodyOptions(decompiler = fake),
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
        val outcome = JdxService.body(
            "dev.jdx.fixtures.Generics#identity(java.lang.Object)",
            roots,
            BodyOptions(decompiler = vineflower, javapDecompiler = javap),
        )
        outcome.exitCode shouldBe 1
        vineflower.calls shouldBe 1
        javap.calls shouldBe 1
        val text = textOf(outcome)
        text shouldContain "vineflower-boom"
        text shouldContain "javap-boom"
        text shouldNotContain "Exception in thread"
        val json = outcome.toJson("body")
        json shouldContain "vineflower-boom"
        json shouldContain "javap-boom"
    }

    @Test
    fun `a failing vineflower degrades to disassembly`(@TempDir tempDir: Path) {
        // A Vineflower timeout on the default ladder answers from `javap`
        // (T-073) — exit 0 with javap provenance, never the engine failure.
        assumeTrue(javapPresent(), "no javap on this machine")
        val binary = bareJar(tempDir)
        val roots = RootsSpec(jarSpecs = listOf(binary.toString()), includeJdk = false)
        val vineflower = FailingDecompiler("vineflower timed out after 30s")
        val javap = tempJavapEngine(tempDir)
        val outcome = JdxService.body(
            "dev.jdx.fixtures.Generics#identity(java.lang.Object)",
            roots,
            BodyOptions(decompiler = vineflower, javapDecompiler = javap),
        )
        outcome.exitCode shouldBe 0
        vineflower.calls shouldBe 1
        val text = textOf(outcome)
        text shouldContain "disassembled by javap from bare.jar"
        text shouldContain "Code:"
        text shouldContain "descriptor: (Ljava/lang/Object;)Ljava/lang/Object;"
        text shouldNotContain "Exception in thread"
        val json = outcome.toJson("body")
        json shouldContain "\"origin\":\"decompiled-javap\""
        json shouldContain "Code:"
    }

    @Test
    fun `forced vineflower stays strict on engine failure`(@TempDir tempDir: Path) {
        // Forced `--engine vineflower` never swaps engines silently (T-073):
        // its failure exits 1 naming the hatch, without touching `javap`.
        val binary = bareJar(tempDir)
        val roots = RootsSpec(jarSpecs = listOf(binary.toString()), includeJdk = false)
        val vineflower = FailingDecompiler("boom")
        val javap = FailingJavapDecompiler("unused")
        val outcome = JdxService.body(
            "dev.jdx.fixtures.Generics#identity(java.lang.Object)",
            roots,
            BodyOptions(engine = DecompilerId.VINEFLOWER, decompiler = vineflower, javapDecompiler = javap),
        )
        outcome.exitCode shouldBe 1
        vineflower.calls shouldBe 1
        javap.calls shouldBe 0
        textOf(outcome) shouldContain "--engine javap"
        textOf(outcome) shouldNotContain "Exception in thread"
    }

    @Test
    fun `a member missing from reconstructed text names SOURCES_VERSION_MISMATCH`(@TempDir tempDir: Path) {
        // Forced vineflower is strict (T-073): the member proven in bytecode
        // but absent from the reconstruction exits 1 here.
        val binary = bareJar(tempDir)
        val roots = RootsSpec(jarSpecs = listOf(binary.toString()), includeJdk = false)
        val scripted = ScriptedDecompiler(
            "package dev.jdx.fixtures;\npublic class Generics {\n    public void unrelated() {}\n}\n",
        )
        val outcome = JdxService.body(
            "dev.jdx.fixtures.Generics#identity(java.lang.Object)",
            roots,
            BodyOptions(engine = DecompilerId.VINEFLOWER, decompiler = scripted),
        )
        outcome.exitCode shouldBe 1
        textOf(outcome) shouldContain "SOURCES_VERSION_MISMATCH"
    }

    @Test
    fun `a member missing from reconstructed text falls back to disassembly`(@TempDir tempDir: Path) {
        // The same gap on the default ladder answers from `javap` (T-073):
        // the member exists in bytecode, so disassembly serves it.
        assumeTrue(javapPresent(), "no javap on this machine")
        val binary = bareJar(tempDir)
        val roots = RootsSpec(jarSpecs = listOf(binary.toString()), includeJdk = false)
        val scripted = ScriptedDecompiler(
            "package dev.jdx.fixtures;\npublic class Generics {\n    public void unrelated() {}\n}\n",
        )
        val outcome = JdxService.body(
            "dev.jdx.fixtures.Generics#identity(java.lang.Object)",
            roots,
            BodyOptions(decompiler = scripted, javapDecompiler = tempJavapEngine(tempDir)),
        )
        outcome.exitCode shouldBe 0
        val text = textOf(outcome)
        text shouldContain "disassembled by javap from bare.jar"
        text shouldContain "descriptor: (Ljava/lang/Object;)Ljava/lang/Object;"
        text shouldNotContain "Exception in thread"
        outcome.toJson("body") shouldContain "\"origin\":\"decompiled-javap\""
    }

    @Test
    fun `decompiled bodies are deterministic`(@TempDir tempDir: Path) {
        val binary = bareJar(tempDir)
        val roots = RootsSpec(jarSpecs = listOf(binary.toString()), includeJdk = false)
        val options = BodyOptions(decompiler = tempEngine(tempDir))
        val ref = "dev.jdx.fixtures.Generics#identity(java.lang.Object)"
        textOf(JdxService.body(ref, roots, options)) shouldBe textOf(JdxService.body(ref, roots, options))
        JdxService.body(ref, roots, options).toJson("body") shouldBe
            JdxService.body(ref, roots, options).toJson("body")
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
    fun `with-signature prepends the resolved header`() {
        val plain = JdxService.body("dev.jdx.fixtures.Generics#identity(java.lang.Object)", fixtureRoots())
        plain.exitCode shouldBe 0
        textOf(plain) shouldNotContain "  signature:"
        val dressed = JdxService.body(
            "dev.jdx.fixtures.Generics#identity(java.lang.Object)",
            fixtureRoots(),
            BodyOptions(withSignature = true),
        )
        dressed.exitCode shouldBe 0
        textOf(dressed) shouldContain "  signature: public U identity(U value)"
        dressed.toJson("body") shouldContain "\"signature\":\"public U identity(U value)\""
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
    fun `jdk member without src zip or with sources answers honestly`(@TempDir tempDir: Path) {
        // The JDK ships src.zip on this machine: ArrayList#get answers from sources.
        // Wherever src.zip is absent it reconstructs (T-026) — both honest.
        val outcome = JdxService.body(
            "java.util.ArrayList#get(int)",
            RootsSpec(jarSpecs = emptyList(), includeJdk = true),
            BodyOptions(decompiler = tempEngine(tempDir)),
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
    fun `forced javap disassembles the member with javap provenance`(@TempDir tempDir: Path) {
        assumeTrue(javapPresent(), "no javap on this machine")
        val binary = bareJar(tempDir)
        val roots = RootsSpec(jarSpecs = listOf(binary.toString()), includeJdk = false)
        val options = BodyOptions(engine = DecompilerId.JAVAP, javapDecompiler = tempJavapEngine(tempDir))
        val outcome = JdxService.body("dev.jdx.fixtures.Generics#identity(java.lang.Object)", roots, options)
        outcome.exitCode shouldBe 0
        val text = textOf(outcome)
        text.lines().first() shouldBe "dev.jdx.fixtures.Generics#identity(java.lang.Object)"
        text shouldContain "disassembled by javap from bare.jar"
        text shouldContain "reconstructed"
        text shouldContain "descriptor: (Ljava/lang/Object;)Ljava/lang/Object;"
        text shouldContain "Code:"
        text shouldNotContain "Exception in thread"
        val json = outcome.toJson("body")
        json shouldContain "\"origin\":\"decompiled-javap\""
        json shouldContain "Code:"
    }

    @Test
    fun `forced javap skips paired sources`(@TempDir tempDir: Path) {
        assumeTrue(javapPresent(), "no javap on this machine")
        val options = BodyOptions(engine = DecompilerId.JAVAP, javapDecompiler = tempJavapEngine(tempDir))
        val outcome = JdxService.body("dev.jdx.fixtures.Generics#identity(java.lang.Object)", fixtureRoots(), options)
        outcome.exitCode shouldBe 0
        textOf(outcome) shouldContain "disassembled by javap"
        textOf(outcome) shouldNotContain "return value;"
    }

    @Test
    fun `forced javap keeps bytecode-first ambiguity`(@TempDir tempDir: Path) {
        assumeTrue(javapPresent(), "no javap on this machine")
        val outcome = JdxService.body(
            "dev.jdx.fixtures.CovariantOverrides\$Child#copy",
            fixtureRoots(),
            BodyOptions(engine = DecompilerId.JAVAP, javapDecompiler = tempJavapEngine(tempDir)),
        )
        outcome.exitCode shouldBe 2
    }

    @Test
    fun `a failing javap engine exits 1 naming disassembly`(@TempDir tempDir: Path) {
        val binary = bareJar(tempDir)
        val roots = RootsSpec(jarSpecs = listOf(binary.toString()), includeJdk = false)
        val fake = FailingJavapDecompiler()
        val outcome = JdxService.body(
            "dev.jdx.fixtures.Generics#identity(java.lang.Object)",
            roots,
            BodyOptions(engine = DecompilerId.JAVAP, javapDecompiler = fake),
        )
        outcome.exitCode shouldBe 1
        fake.calls shouldBe 1
        textOf(outcome) shouldContain "could not disassemble"
        textOf(outcome) shouldNotContain "Exception in thread"
    }

    @Test
    fun `a member missing from disassembly names SOURCES_VERSION_MISMATCH`(@TempDir tempDir: Path) {
        val binary = bareJar(tempDir)
        val roots = RootsSpec(jarSpecs = listOf(binary.toString()), includeJdk = false)
        val scripted = ScriptedDecompiler(
            "package dev.jdx.fixtures;\npublic class Generics {\n    public void unrelated() {}\n}\n",
        )
        val outcome = JdxService.body(
            "dev.jdx.fixtures.Generics#identity(java.lang.Object)",
            roots,
            BodyOptions(engine = DecompilerId.JAVAP, javapDecompiler = scripted),
        )
        outcome.exitCode shouldBe 1
        textOf(outcome) shouldContain "SOURCES_VERSION_MISMATCH"
    }

    @Test
    fun `disassembled bodies are deterministic`(@TempDir tempDir: Path) {
        assumeTrue(javapPresent(), "no javap on this machine")
        val binary = bareJar(tempDir)
        val roots = RootsSpec(jarSpecs = listOf(binary.toString()), includeJdk = false)
        val options = BodyOptions(engine = DecompilerId.JAVAP, javapDecompiler = tempJavapEngine(tempDir))
        val ref = "dev.jdx.fixtures.Generics#identity(java.lang.Object)"
        textOf(JdxService.body(ref, roots, options)) shouldBe textOf(JdxService.body(ref, roots, options))
        JdxService.body(ref, roots, options).toJson("body") shouldBe
            JdxService.body(ref, roots, options).toJson("body")
    }
}
