package dev.jdx.cli.commands

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * Windows `java` probing for the daemon spawn (phase 5, tier 1): `java.exe`
 * wins on a simulated Windows box, the bare name elsewhere — all over a fake
 * `java.home`, never the real JDK.
 */
class DaemonJavaExeTest {

    @TempDir
    lateinit var tempDir: Path

    private fun regular(dir: Path, name: String): Path {
        Files.createDirectories(dir)
        return Files.createFile(dir.resolve(name))
    }

    @Test
    fun `windows resolves java dot exe`() {
        val home = tempDir.resolve("jdk")
        regular(home.resolve("bin"), "java.exe")
        resolveJavaExe(home.toString(), "Windows 11") shouldBe
            home.resolve("bin/java.exe").toString().replace("/", File.separator)
    }

    @Test
    fun `windows falls back to bare java when no exe exists`() {
        val home = tempDir.resolve("jdk")
        regular(home.resolve("bin"), "java")
        resolveJavaExe(home.toString(), "Windows 11") shouldBe
            home.resolve("bin/java").toString().replace("/", File.separator)
    }

    @Test
    fun `non-windows resolves the bare java`() {
        val home = tempDir.resolve("jdk")
        regular(home.resolve("bin"), "java")
        regular(home.resolve("bin"), "java.exe")
        resolveJavaExe(home.toString(), "Linux") shouldBe
            home.resolve("bin/java").toString().replace("/", File.separator)
    }

    @Test
    fun `a missing home still names the platform default`() {
        val missing = tempDir.resolve("no-such-jdk").toString()
        resolveJavaExe(missing, "Linux") shouldContain "java"
        resolveJavaExe(missing, "Windows 11") shouldContain "java"
    }
}
