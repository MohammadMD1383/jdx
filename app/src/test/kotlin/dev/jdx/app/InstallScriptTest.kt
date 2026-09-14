package dev.jdx.app

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

/**
 * Tests of install.sh: per-user install into ~/.local/bin, refusal to clobber an unrelated
 * `jdx` without --force, and clean errors when the build is missing. Runs against the real
 * build output (app/build/jdx — the test task depends on :app:installDist) with HOME pointed
 * at a temp directory, so the developer's own ~/.local/bin is never touched.
 */
class InstallScriptTest {

    private val projectDir = File(System.getProperty("user.dir"))
    private val repoRoot = projectDir.parentFile
    private val installScript = File(repoRoot, "install.sh")
    private val builtLauncher = File(projectDir, "build/jdx")

    private data class ProcessResult(val exitCode: Int, val stdout: String, val stderr: String)

    private fun runInstallScript(
        home: File,
        extraArgs: List<String> = emptyList(),
        script: File = installScript,
    ): ProcessResult {
        val process = ProcessBuilder(listOf("sh", script.absolutePath) + extraArgs)
            .directory(home)
            .apply { environment()["HOME"] = home.absolutePath }
            .start()
        val stdout = process.inputStream.bufferedReader().readText()
        val stderr = process.errorStream.bufferedReader().readText()
        return ProcessResult(process.waitFor(), stdout, stderr)
    }

    private fun tempDir(prefix: String): File = Files.createTempDirectory(prefix).toFile()

    private fun userBin(home: File): File = File(home, ".local/bin").apply { mkdirs() }

    @Test
    fun `fresh install symlinks the built launcher into home local bin`() {
        val home = tempDir("jdx-home-")

        val result = runInstallScript(home)

        result.exitCode shouldBe 0
        val target = File(home, ".local/bin/jdx")
        target.exists() shouldBe true
        Files.isSymbolicLink(target.toPath()) shouldBe true
        target.canonicalPath shouldBe builtLauncher.canonicalPath
        result.stdout shouldContain "installed:"
        result.stderr shouldContain "not on your PATH" // fresh HOME is never on PATH
    }

    @Test
    fun `re-running against our own symlink is idempotent`() {
        val home = tempDir("jdx-home-")
        runInstallScript(home)

        val result = runInstallScript(home)

        result.exitCode shouldBe 0
        File(home, ".local/bin/jdx").canonicalPath shouldBe builtLauncher.canonicalPath
    }

    @Test
    fun `an unrelated existing jdx is refused without --force and left untouched`() {
        val home = tempDir("jdx-home-")
        val existing = File(userBin(home), "jdx").apply { writeText("#!/bin/sh\nsome other tool\n") }

        val result = runInstallScript(home)

        result.exitCode shouldBe 1
        result.stderr shouldStartWith("install.sh: ")
        result.stderr shouldContain "--force"
        existing.readText() shouldContain "some other tool"
        Files.isSymbolicLink(existing.toPath()) shouldBe false
    }

    @Test
    fun `--force replaces an unrelated existing jdx`() {
        val home = tempDir("jdx-home-")
        File(userBin(home), "jdx").apply { writeText("unrelated") }

        val result = runInstallScript(home, extraArgs = listOf("--force"))

        result.exitCode shouldBe 0
        File(home, ".local/bin/jdx").canonicalPath shouldBe builtLauncher.canonicalPath
    }

    @Test
    fun `a directory at the target is refused even with --force`() {
        val home = tempDir("jdx-home-")
        File(userBin(home), "jdx").apply { mkdirs() }

        val result = runInstallScript(home, extraArgs = listOf("--force"))

        result.exitCode shouldBe 1
        result.stderr shouldContain "is a directory"
        File(home, ".local/bin/jdx").isDirectory shouldBe true
    }

    @Test
    fun `unknown arguments are rejected`() {
        val home = tempDir("jdx-home-")

        val result = runInstallScript(home, extraArgs = listOf("--nonsense"))

        result.exitCode shouldBe 1
        result.stderr shouldContain "unknown argument"
    }

    @Test
    fun `a repo without a build says which gradle command to run`() {
        val home = tempDir("jdx-home-")
        val fakeRepo = tempDir("jdx-fakerepo-")
        val scriptCopy = File(fakeRepo, "install.sh").apply {
            writeText(installScript.readText())
            setExecutable(true, false)
        }

        val result = runInstallScript(home, script = scriptCopy)

        result.exitCode shouldBe 1
        result.stderr shouldContain ":app:installDist"
    }
}
