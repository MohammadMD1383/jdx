package dev.jdx.sources

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.property.Arb
import io.kotest.property.arbitrary.filter
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import dev.jdx.core.paths.JdxOs
import java.nio.file.Files
import java.nio.file.Path
import javax.tools.ToolProvider
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * The D-008 loading seam (T-038, tier 2): sidecar path laws, [probeKotlinToolchain]
 * disk behaviour, and [openKotlinParser] availability — all as values, never throws.
 * Filesystem-touching by construction, so tagged out of the tier-1 loop.
 */
@Tag("tier2")
class KotlinToolchainTest {

    // Pinned resolution context: Linux + empty env resolves under the injected
    // home on every host OS — never the ambient machine's cache (`~/Library`
    // on macOS, `%LOCALAPPDATA%` on Windows), which parallel tests would share.
    private val testOs = JdxOs.LINUX
    private val testEnv: Map<String, String> = emptyMap()

    @Test
    fun `the sidecar path is versioned under the cache root`(@TempDir home: java.nio.file.Path) {
        kotlinSidecarJar(home, testOs, testEnv) shouldBe
            home.resolve(".cache/jdx/kotlin").resolve(KOTLIN_COMPILER_JAR)
    }

    @Test
    fun `the sidecar path follows the per-OS cache root`(@TempDir home: java.nio.file.Path) {
        // D-044: macOS ignores XDG and uses ~/Library/Caches; Windows honours
        // LOCALAPPDATA. Pinned here so a Linux-layout assumption reds on mac/Windows.
        kotlinSidecarDir(home, JdxOs.MACOS, testEnv) shouldBe
            home.resolve("Library/Caches/jdx/kotlin")
        kotlinSidecarDir(
            home,
            JdxOs.WINDOWS,
            mapOf("LOCALAPPDATA" to "C:/Users/ada/AppData/Local"),
        ) shouldBe Path.of("C:/Users/ada/AppData/Local/jdx/cache/kotlin")
    }

    @Test
    fun `the jar name carries the pinned version`(@TempDir home: java.nio.file.Path) {
        KOTLIN_COMPILER_JAR shouldContain KOTLIN_COMPILER_VERSION
    }

    @Test
    fun `an absent sidecar probes Missing at the versioned path`(@TempDir home: java.nio.file.Path) {
        val status = probeKotlinToolchain(home, testOs, testEnv)
        (status is KotlinToolchainStatus.Missing) shouldBe true
        (status as KotlinToolchainStatus.Missing).jar shouldBe kotlinSidecarJar(home, testOs, testEnv)
    }

    @Test
    fun `a non-empty sidecar probes Installed with its size`(@TempDir home: java.nio.file.Path) {
        val jar = kotlinSidecarJar(home, testOs, testEnv).also {
            Files.createDirectories(it.parent)
            Files.write(it, ByteArray(64) { 0x50 })
        }

        val status = probeKotlinToolchain(home, testOs, testEnv)
        (status is KotlinToolchainStatus.Installed) shouldBe true
        status as KotlinToolchainStatus.Installed
        status.jar shouldBe jar
        status.bytes shouldBe 64
    }

    @Test
    fun `a directory at the sidecar path probes Missing`(@TempDir home: java.nio.file.Path) {
        Files.createDirectories(kotlinSidecarJar(home, testOs, testEnv))

        (probeKotlinToolchain(home, testOs, testEnv) is KotlinToolchainStatus.Missing) shouldBe true
    }

    @Test
    fun `an empty file at the sidecar path probes Missing`(@TempDir home: java.nio.file.Path) {
        kotlinSidecarJar(home, testOs, testEnv).also {
            Files.createDirectories(it.parent)
            Files.createFile(it)
        }

        (probeKotlinToolchain(home, testOs, testEnv) is KotlinToolchainStatus.Missing) shouldBe true
    }

    @Test
    fun `probing is deterministic`(@TempDir home: java.nio.file.Path) {
        probeKotlinToolchain(home, testOs, testEnv) shouldBe probeKotlinToolchain(home, testOs, testEnv)
    }

    @Test
    fun `opening the parser without a sidecar is unavailable with the install hint`(
        @TempDir home: java.nio.file.Path,
    ) {
        openKotlinParser(home, testOs, testEnv).use { parser ->
            parser.available shouldBe false
            parser.detail shouldContain "not installed"
            parser.detail shouldContain KOTLIN_COMPILER_VERSION
            parser.detail shouldContain "D-008"
        }
    }

    @Test
    fun `opening the parser over a garbage jar is unavailable, never a throw`(
        @TempDir home: java.nio.file.Path,
    ) {
        kotlinSidecarJar(home, testOs, testEnv).also {
            Files.createDirectories(it.parent)
            Files.write(it, ByteArray(128) { it.toByte() })
        }

        openKotlinParser(home, testOs, testEnv).use { parser ->
            parser.available shouldBe false
            parser.detail shouldContain "unusable"
        }
    }

    @Test
    fun `opening the parser over a non-compiler zip is unavailable`(@TempDir home: java.nio.file.Path) {
        kotlinSidecarJar(home, testOs, testEnv).also {
            Files.createDirectories(it.parent)
            java.util.zip.ZipOutputStream(Files.newOutputStream(it)).use { zip ->
                zip.putNextEntry(java.util.zip.ZipEntry("com/example/NotACompiler.txt"))
                zip.write("hello".toByteArray())
                zip.closeEntry()
            }
        }

        openKotlinParser(home, testOs, testEnv).use { parser ->
            parser.available shouldBe false
            parser.detail shouldContain "unusable"
        }
    }

    @Test
    fun `opening the parser over a stub compiler jar is available`(@TempDir home: java.nio.file.Path) {
        assumeTrue(
            ToolProvider.getSystemJavaCompiler() != null,
            "needs a JDK compiler to stub the presence class",
        )
        craftPresenceStubJar(kotlinSidecarJar(home, testOs, testEnv))

        openKotlinParser(home, testOs, testEnv).use { parser ->
            parser.available shouldBe true
            parser.detail shouldContain KOTLIN_COMPILER_VERSION
        }
    }

    @Test
    fun `closing an unavailable parser never throws`(@TempDir home: java.nio.file.Path) {
        val parser = openKotlinParser(home, testOs, testEnv)
        parser.close()
        parser.close()
    }

    @Test
    fun `the compiler is not on the test classpath, so isolation is real`() {
        // Pins the D-008 premise: if someone adds kotlin-compiler-embeddable as a
        // compile dependency, this reds and the side-load story is already broken.
        var thrown = false
        try {
            Class.forName(KOTLIN_PRESENCE_CLASS)
        } catch (e: ClassNotFoundException) {
            thrown = true
        }
        thrown shouldBe true
    }

    @Test
    fun `hostile home segments never throw the probe`() {
        runBlocking {
            checkAll(200, Arb.string().filter { it.none { c -> c.code == 0 } }) { segment ->
                // `Path.of` itself rejects host-unspellable names (DOS-illegal
                // chars, trailing spaces/dots on Windows): the OS refusing a
                // path is outside the probe contract, so those skip the case.
                val home = runCatching {
                    java.nio.file.Path.of(System.getProperty("java.io.tmpdir"), "jdx-kt-$segment")
                }.getOrNull() ?: return@checkAll
                try {
                    probeKotlinToolchain(home, testOs, testEnv)
                } catch (e: Exception) {
                    throw AssertionError("probe threw on $segment: $e")
                }
            }
        }
    }

    /**
     * Compiles a stub of the [KOTLIN_PRESENCE_CLASS] with the JDK compiler and jars it:
     * the smallest jar the presence check accepts, proving the Available branch without
     * the 55 MB real sidecar.
     */
    private fun craftPresenceStubJar(jar: java.nio.file.Path) {
        val srcDir = Files.createTempDirectory("jdx-kt-stub-src")
        val outDir = Files.createTempDirectory("jdx-kt-stub-out")
        val stub = srcDir.resolve("org/jetbrains/kotlin/cli/jvm/compiler/KotlinCoreEnvironment.java").also {
            Files.createDirectories(it.parent)
            Files.writeString(it, "package org.jetbrains.kotlin.cli.jvm.compiler;\npublic class KotlinCoreEnvironment {}\n")
        }
        val compiler = ToolProvider.getSystemJavaCompiler()
        val code = compiler.run(null, null, null, "-d", outDir.toString(), stub.toString())
        check(code == 0) { "stub compilation failed" }
        Files.createDirectories(jar.parent)
        java.util.zip.ZipOutputStream(Files.newOutputStream(jar)).use { zip ->
            Files.walk(outDir).use { walk ->
                walk.filter { Files.isRegularFile(it) }.forEach { file ->
                    val entry = outDir.relativize(file).joinToString("/")
                    zip.putNextEntry(java.util.zip.ZipEntry(entry))
                    Files.copy(file, zip)
                    zip.closeEntry()
                }
            }
        }
    }
}
