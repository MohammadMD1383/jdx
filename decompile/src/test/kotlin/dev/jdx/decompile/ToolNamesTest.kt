package dev.jdx.decompile

import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.filter
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Windows tool probing (phase 5, tier 1): `toolNames` PATHEXT order plus
 * `resolveExecutable` over fake JDK homes — no subprocess, no real JDK.
 */
class ToolNamesTest {

    @TempDir
    lateinit var tempDir: Path

    private fun executable(dir: Path, name: String): Path {
        Files.createDirectories(dir)
        return Files.createFile(dir.resolve(name)).also { it.toFile().setExecutable(true) }
    }

    @Test
    fun `windows probes exe cmd bat then bare`() {
        toolNames("javap", "Windows 11") shouldBe listOf("javap.exe", "javap.cmd", "javap.bat", "javap")
    }

    @Test
    fun `non-windows probes the bare name alone`() {
        toolNames("javap", "Linux") shouldBe listOf("javap")
        toolNames("javap", "Mac OS X") shouldBe listOf("javap")
        toolNames("javap", "") shouldBe listOf("javap")
    }

    @Test
    fun `windows resolves javap dot exe from JAVA_HOME bin`() {
        val home = tempDir.resolve("jdk")
        executable(home.resolve("bin"), "javap.exe")
        val environment = JavapEnvironment(
            javaHomeEnv = home.toString(),
            javaHome = tempDir.resolve("no-such-home").toString(),
            pathDirs = emptyList(),
            osName = "Windows 11",
        )
        environment.resolveExecutable() shouldBe home.resolve("bin/javap.exe").toString()
    }

    @Test
    fun `windows prefers exe over a bare sibling`() {
        val home = tempDir.resolve("jdk")
        executable(home.resolve("bin"), "javap")
        executable(home.resolve("bin"), "javap.exe")
        val environment = JavapEnvironment(
            javaHomeEnv = home.toString(),
            javaHome = tempDir.resolve("no-such-home").toString(),
            pathDirs = emptyList(),
            osName = "Windows 11",
        )
        environment.resolveExecutable() shouldBe home.resolve("bin/javap.exe").toString()
    }

    @Test
    fun `windows falls back to the bare name when no shim exists`() {
        val home = tempDir.resolve("jdk")
        executable(home.resolve("bin"), "javap")
        val environment = JavapEnvironment(
            javaHomeEnv = home.toString(),
            javaHome = tempDir.resolve("no-such-home").toString(),
            pathDirs = emptyList(),
            osName = "Windows 11",
        )
        environment.resolveExecutable() shouldBe home.resolve("bin/javap").toString()
    }

    @Test
    fun `windows probes PATH dirs for exe variants`() {
        val pathDir = tempDir.resolve("pathbin")
        executable(pathDir, "javap.cmd")
        val environment = JavapEnvironment(
            javaHomeEnv = tempDir.resolve("no-home").toString(),
            javaHome = tempDir.resolve("no-home-either").toString(),
            pathDirs = listOf(pathDir.toString()),
            osName = "Windows 10",
        )
        environment.resolveExecutable() shouldBe pathDir.resolve("javap.cmd").toString()
    }

    @Test
    fun `non-windows ignores an exe-only layout`() {
        val home = tempDir.resolve("jdk")
        executable(home.resolve("bin"), "javap.exe")
        val environment = JavapEnvironment(
            javaHomeEnv = home.toString(),
            javaHome = tempDir.resolve("no-such-home").toString(),
            pathDirs = emptyList(),
            osName = "Linux",
        )
        environment.resolveExecutable() shouldBe null
    }

    @Test
    fun `exe probing keeps the suffix order for every tool name`() = runBlocking {
        checkAll(Arb.string(1..12).filter { it.all { c -> c.isLetterOrDigit() } }) { base ->
            val names = toolNames(base, "Windows 11")
            names shouldBe listOf("$base.exe", "$base.cmd", "$base.bat", base)
        }
    }
}
