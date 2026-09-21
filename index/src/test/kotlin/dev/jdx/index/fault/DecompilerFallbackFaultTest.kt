package dev.jdx.index.fault

import dev.jdx.decompile.DecompileResult
import dev.jdx.decompile.DecompilerEngine
import dev.jdx.decompile.DecompilerId
import dev.jdx.index.service.JdxService
import dev.jdx.index.service.JdxService.BodyOptions
import dev.jdx.index.service.JdxService.RootsSpec
import dev.jdx.index.service.JdxService.SourceOptions
import dev.jdx.testsupport.fixtures.FixtureJars
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Decompiler fault-injection suite (T-073, TESTING.md §7): Vineflower timeouts
 * and crashes must degrade to `javap` disassembly, never a trace.
 *
 * Every case runs the default ladder over a sources-less jar with a scripted
 * Vineflower failure (or unparseable text) in front of a scripted `javap`
 * engine, then asserts the fault law: a documented D-015 exit code, output
 * naming the cause, and no stack trace on any channel (via [FaultSupport]).
 * The failure messages mirror the production wordings pinned in
 * `VineflowerDecompilerTest` (`timed out`, `failed decompiling`), so the
 * cases fault the shape the real engine produces. No `javap` binary, no real
 * Vineflower, no goldens (TESTING.md §5.3 forbids pinning engine text — the
 * assertions are structural: provenance, descriptors, both causes named).
 */
@Tag("tier2")
class DecompilerFallbackFaultTest {

    /** A scripted Vineflower failure standing in for a timeout or crash. */
    private class FailingVineflower(val message: String) : DecompilerEngine {
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

    /** Scripted Vineflower text (used for the unparseable-output faults). */
    private class ScriptedVineflower(val text: String) : DecompilerEngine {
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

    /** A scripted `javap` engine serving canned disassembly. */
    private class ScriptedJavap(val text: String) : DecompilerEngine {
        override val id: DecompilerId = DecompilerId.JAVAP
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

    private fun bareJar(tempDir: Path, name: String = "bare.jar"): Path {
        val binary = tempDir.resolve(name)
        val classBytes = ZipFile(FixtureJars.binaryJar()).use { zip ->
            zip.getInputStream(zip.getEntry("dev/jdx/fixtures/Generics.class")).readBytes()
        }
        ZipOutputStream(java.nio.file.Files.newOutputStream(binary)).use { zip ->
            zip.putNextEntry(ZipEntry("dev/jdx/fixtures/Generics.class"))
            zip.write(classBytes)
            zip.closeEntry()
        }
        return binary
    }

    private companion object {
        /** Vineflower faults in production wording: timeouts and crashes. */
        val VINEFLOWER_FAULTS: List<String> = listOf(
            "vineflower timed out after 30s decompiling dev.jdx.fixtures.Generics",
            "vineflower decompilation of dev.jdx.fixtures.Generics was interrupted",
            "vineflower failed decompiling dev.jdx.fixtures.Generics: boom",
            "vineflower produced no output for dev.jdx.fixtures.Generics",
        )

        /** `javap` faults for the double-failure matrix. */
        val JAVAP_FAULTS: List<String> = listOf(
            "javap timed out after 30s disassembling dev.jdx.fixtures.Generics",
            "javap exits 1 for dev.jdx.fixtures.Generics: error: file not found",
        )

        /** Texts no Java parser accepts, standing in for corrupt reconstructions. */
        val UNPARSEABLE_TEXTS: List<String> = listOf(
            "this is not java {{{",
            "public class Generics { public void identity( {{{ ",
            "\u0000\u0001\u0002 binary garbage",
        )

        /** Canned `javap -c -p -s` disassembly of the fixture `Generics` class. */
        val JAVAP_GENERICS_TEXT: String = """
            |Compiled from "Generics.java"
            |public class dev.jdx.fixtures.Generics {
            |  public dev.jdx.fixtures.Generics();
            |    descriptor: ()V
            |    flags: (0x0001) ACC_PUBLIC
            |    Code:
            |      stack=1, locals=1, args_size=1
            |         0: aload_0
            |         1: invokespecial #1
            |         4: return
            |
            |  public <U> U identity(U);
            |    descriptor: (Ljava/lang/Object;)Ljava/lang/Object;
            |    flags: (0x0001) ACC_PUBLIC
            |    Code:
            |      stack=1, locals=2, args_size=2
            |         0: aload_1
            |         1: areturn
            |
            |}
            """.trimMargin()
    }

    private val bodyRef: String = "dev.jdx.fixtures.Generics#identity(java.lang.Object)"
    private val typeRef: String = "dev.jdx.fixtures.Generics"

    // -- timeout/crash degrade to disassembly ------------------------------------

    @Test
    fun `vineflower timeouts and crashes degrade body to disassembly`(@TempDir tempDir: Path) {
        val roots = FaultSupport.rootsOf(bareJar(tempDir))
        for (fault in VINEFLOWER_FAULTS) {
            withClue("vineflower fault: $fault") {
                val vineflower = FailingVineflower(fault)
                val javap = ScriptedJavap(JAVAP_GENERICS_TEXT)
                val captured = FaultSupport.captureStreams {
                    JdxService.body(bodyRef, roots, BodyOptions(decompiler = vineflower, javapDecompiler = javap))
                }
                FaultSupport.assertNoStackTrace(captured.stdout, "body stdout")
                FaultSupport.assertNoStackTrace(captured.stderr, "body stderr")
                val outcome = captured.value
                withClue("expected exit 0, got ${outcome.exitCode}") { outcome.exitCode shouldBe 0 }
                vineflower.calls shouldBe 1
                javap.calls shouldBe 1
                val rendered = FaultSupport.renderBoth(outcome, "body")
                rendered.text shouldContain "disassembled by javap"
                rendered.text shouldContain "descriptor: (Ljava/lang/Object;)Ljava/lang/Object;"
                rendered.text shouldContain "Code:"
                rendered.json shouldContain "\"origin\":\"decompiled-javap\""
                rendered.json shouldContain "Code:"
            }
        }
    }

    @Test
    fun `vineflower timeouts and crashes degrade source to disassembly`(@TempDir tempDir: Path) {
        val roots = FaultSupport.rootsOf(bareJar(tempDir))
        for (fault in VINEFLOWER_FAULTS) {
            withClue("vineflower fault: $fault") {
                val vineflower = FailingVineflower(fault)
                val javap = ScriptedJavap(JAVAP_GENERICS_TEXT)
                val captured = FaultSupport.captureStreams {
                    JdxService.source(typeRef, roots, SourceOptions(decompiler = vineflower, javapDecompiler = javap))
                }
                FaultSupport.assertNoStackTrace(captured.stdout, "source stdout")
                FaultSupport.assertNoStackTrace(captured.stderr, "source stderr")
                val outcome = captured.value
                withClue("expected exit 0, got ${outcome.exitCode}") { outcome.exitCode shouldBe 0 }
                vineflower.calls shouldBe 1
                javap.calls shouldBe 1
                val rendered = FaultSupport.renderBoth(outcome, "source")
                rendered.text shouldContain "disassembled by javap"
                rendered.text shouldContain "Compiled from"
                rendered.json shouldContain "\"origin\":\"decompiled-javap\""
            }
        }
    }

    @Test
    fun `vineflower timeouts and crashes degrade source around to disassembly`(@TempDir tempDir: Path) {
        val roots = FaultSupport.rootsOf(bareJar(tempDir))
        for (fault in VINEFLOWER_FAULTS) {
            withClue("vineflower fault: $fault") {
                val vineflower = FailingVineflower(fault)
                val javap = ScriptedJavap(JAVAP_GENERICS_TEXT)
                val options = SourceOptions(
                    aroundRef = bodyRef,
                    decompiler = vineflower,
                    javapDecompiler = javap,
                )
                val captured = FaultSupport.captureStreams { JdxService.source(typeRef, roots, options) }
                FaultSupport.assertNoStackTrace(captured.stdout, "around stdout")
                FaultSupport.assertNoStackTrace(captured.stderr, "around stderr")
                val outcome = captured.value
                withClue("expected exit 0, got ${outcome.exitCode}") { outcome.exitCode shouldBe 0 }
                val rendered = FaultSupport.renderBoth(outcome, "source")
                rendered.text shouldContain "disassembled by javap"
                rendered.text shouldContain "descriptor: (Ljava/lang/Object;)Ljava/lang/Object;"
                rendered.json shouldContain "\"origin\":\"decompiled-javap\""
            }
        }
    }

    // -- unparseable reconstruction degrades too -----------------------------------

    @Test
    fun `unparseable vineflower text degrades body to disassembly`(@TempDir tempDir: Path) {
        val roots = FaultSupport.rootsOf(bareJar(tempDir))
        for (text in UNPARSEABLE_TEXTS) {
            withClue("unparseable text: ${text.take(24)}") {
                val vineflower = ScriptedVineflower(text)
                val javap = ScriptedJavap(JAVAP_GENERICS_TEXT)
                val captured = FaultSupport.captureStreams {
                    JdxService.body(bodyRef, roots, BodyOptions(decompiler = vineflower, javapDecompiler = javap))
                }
                FaultSupport.assertNoStackTrace(captured.stdout, "body stdout")
                FaultSupport.assertNoStackTrace(captured.stderr, "body stderr")
                val outcome = captured.value
                withClue("expected exit 0, got ${outcome.exitCode}") { outcome.exitCode shouldBe 0 }
                vineflower.calls shouldBe 1
                javap.calls shouldBe 1
                val rendered = FaultSupport.renderBoth(outcome, "body")
                rendered.text shouldContain "disassembled by javap"
                rendered.text shouldContain "Code:"
                rendered.json shouldContain "\"origin\":\"decompiled-javap\""
            }
        }
    }

    // -- double failure names both causes --------------------------------------------

    @Test
    fun `double failure exits 1 naming both causes for body`(@TempDir tempDir: Path) {
        val roots = FaultSupport.rootsOf(bareJar(tempDir))
        for (vineFault in VINEFLOWER_FAULTS) {
            for (javapFault in JAVAP_FAULTS) {
                withClue("vineflower fault: $vineFault; javap fault: $javapFault") {
                    val vineflower = FailingVineflower(vineFault)
                    var javapCalls = 0
                    val javapFailing = object : DecompilerEngine {
                        override val id: DecompilerId = DecompilerId.JAVAP
                        override fun decompileClass(
                            classBytes: ByteArray,
                            binaryName: String,
                            classpath: List<Path>,
                        ): DecompileResult {
                            javapCalls++
                            return DecompileResult.Failed(javapFault)
                        }
                    }
                    val captured = FaultSupport.captureStreams {
                        JdxService.body(
                            bodyRef,
                            roots,
                            BodyOptions(decompiler = vineflower, javapDecompiler = javapFailing),
                        )
                    }
                    FaultSupport.assertNoStackTrace(captured.stdout, "body stdout")
                    FaultSupport.assertNoStackTrace(captured.stderr, "body stderr")
                    val outcome = captured.value
                    withClue("expected exit 1, got ${outcome.exitCode}") { outcome.exitCode shouldBe 1 }
                    vineflower.calls shouldBe 1
                    javapCalls shouldBe 1
                    val rendered = FaultSupport.renderBoth(outcome, "body")
                    rendered.text shouldContain vineFault
                    rendered.text shouldContain javapFault
                    rendered.json shouldContain vineFault
                    rendered.json shouldContain javapFault
                }
            }
        }
    }

    @Test
    fun `double failure exits 1 naming both causes for source`(@TempDir tempDir: Path) {
        val roots = FaultSupport.rootsOf(bareJar(tempDir))
        for (vineFault in VINEFLOWER_FAULTS) {
            for (javapFault in JAVAP_FAULTS) {
                withClue("vineflower fault: $vineFault; javap fault: $javapFault") {
                    val vineflower = FailingVineflower(vineFault)
                    val javapFailing = object : DecompilerEngine {
                        override val id: DecompilerId = DecompilerId.JAVAP
                        override fun decompileClass(
                            classBytes: ByteArray,
                            binaryName: String,
                            classpath: List<Path>,
                        ): DecompileResult = DecompileResult.Failed(javapFault)
                    }
                    val captured = FaultSupport.captureStreams {
                        JdxService.source(
                            typeRef,
                            roots,
                            SourceOptions(decompiler = vineflower, javapDecompiler = javapFailing),
                        )
                    }
                    FaultSupport.assertNoStackTrace(captured.stdout, "source stdout")
                    FaultSupport.assertNoStackTrace(captured.stderr, "source stderr")
                    val outcome = captured.value
                    withClue("expected exit 1, got ${outcome.exitCode}") { outcome.exitCode shouldBe 1 }
                    vineflower.calls shouldBe 1
                    val rendered = FaultSupport.renderBoth(outcome, "source")
                    rendered.text shouldContain vineFault
                    rendered.text shouldContain javapFault
                    rendered.json shouldContain vineFault
                    rendered.json shouldContain javapFault
                }
            }
        }
    }
}
