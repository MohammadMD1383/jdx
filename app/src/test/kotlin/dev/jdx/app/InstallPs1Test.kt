package dev.jdx.app

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

/**
 * Tests of install.ps1 (issue #92): per-user Windows source-build shim into
 * `%USERPROFILE%\.local\bin\jdx.cmd`, refusal to clobber an unrelated shim
 * without `-Force`, and clean errors when the build is missing.
 *
 * Runs against a fake `app/build/jdx.bat` + fat jar layout under a temp copy of
 * the script (the real `:app:installDist` output is not required). USERPROFILE
 * / `-BinDir` point at temp dirs; `JDX_SKIP_USER_PATH=1` keeps the persistent
 * user-PATH mutation off. Skips when `pwsh` is absent.
 */
class InstallPs1Test {

    private val projectDir = File(System.getProperty("user.dir"))
    private val repoRoot = projectDir.parentFile
    private val installScript = File(repoRoot, "install.ps1")

    private data class ProcessResult(val exitCode: Int, val stdout: String, val stderr: String)

    private fun pwshIsAvailable(): Boolean =
        try {
            runPwsh(listOf("-Command", "\$PSVersionTable.PSVersion")).exitCode == 0
        } catch (_: Exception) {
            false
        }

    private fun assumePwsh() {
        assumeTrue(installScript.isFile, "install.ps1 missing at ${installScript.absolutePath}")
        assumeTrue(pwshIsAvailable(), "pwsh not on PATH")
    }

    private fun runPwsh(
        args: List<String>,
        environment: Map<String, String> = emptyMap(),
        workingDir: File? = null,
    ): ProcessResult {
        val process = ProcessBuilder(listOf("pwsh", "-NoProfile", "-NonInteractive") + args)
            .directory(workingDir ?: repoRoot)
            .apply { environment().putAll(environment) }
            .start()
        val stdout = process.inputStream.bufferedReader().readText()
        val stderr = process.errorStream.bufferedReader().readText()
        return ProcessResult(process.waitFor(), stdout, stderr)
    }

    private fun tempDir(prefix: String): File = Files.createTempDirectory(prefix).toFile()

    /**
     * A mini repo root: copies install.ps1 and lays down `app/build/jdx.bat` +
     * a fat jar so the script's existence checks pass without a real build.
     */
    private fun fakeRepo(withCds: Boolean = false, withLauncher: Boolean = true): File {
        val root = tempDir("jdx-src-")
        installScript.copyTo(File(root, "install.ps1"))
        val build = File(root, "app/build").apply { mkdirs() }
        val libs = File(build, "libs").apply { mkdirs() }
        if (withLauncher) {
            File(build, "jdx.bat").writeText("@echo off\r\necho jdx version 0.0.0-test\r\n")
            File(libs, "jdx-0.0.0-all.jar").writeText("fake")
        }
        if (withCds) {
            File(libs, "jdx.jsa").writeBytes(byteArrayOf(0x50, 0x4b))
        }
        return root
    }

    private fun runInstall(
        repo: File,
        home: File,
        extraArgs: List<String> = emptyList(),
    ): ProcessResult {
        // Prefer USERPROFILE only — pwsh's automatic $Home is derived from HOME
        // and assigning/shadowing it is what install.ps1 must avoid; tests should
        // not also fight the automatic variable by injecting HOME.
        val env = mapOf(
            "USERPROFILE" to home.absolutePath,
            "JDX_SKIP_USER_PATH" to "1",
        )
        return runPwsh(
            listOf("-File", File(repo, "install.ps1").absolutePath) + extraArgs,
            environment = env,
            workingDir = repo,
        )
    }

    @Test
    fun `script is present and documents the source-build defaults`() {
        assumeTrue(installScript.isFile, "install.ps1 missing")
        val text = installScript.readText()
        text shouldContain "issue #92"
        text shouldContain ".local\\bin"
        text shouldContain "jdx.cmd"
        text shouldContain "JDX_SKIP_USER_PATH"
        text shouldContain "gradlew.bat :app:installDist"
    }

    @Test
    fun `fresh install writes a jdx-cmd shim into home local bin`() {
        assumePwsh()
        val repo = fakeRepo(withCds = true)
        val home = tempDir("jdx-home-")

        val result = runInstall(repo, home)

        result.exitCode shouldBe 0
        val shim = File(home, ".local/bin/jdx.cmd")
        shim.isFile shouldBe true
        shim.readText() shouldContain File(repo, "app/build/jdx.bat").canonicalPath
        result.stdout shouldContain "installed:"
        // CDS present → no warning about the archive.
        result.stderr.contains("no AppCDS archive") shouldBe false
    }

    @Test
    fun `missing AppCDS archive prints a note only`() {
        assumePwsh()
        val repo = fakeRepo(withCds = false)
        val home = tempDir("jdx-home-")

        val result = runInstall(repo, home)

        result.exitCode shouldBe 0
        result.stderr shouldContain "no AppCDS archive"
    }

    @Test
    fun `an unrelated existing shim is refused without Force`() {
        assumePwsh()
        // withCds so the AppCDS note does not precede the error on stderr
        // (same shape as InstallScriptTest's force-refusal pin).
        val repo = fakeRepo(withCds = true)
        val home = tempDir("jdx-home-")
        val bin = File(home, ".local/bin").apply { mkdirs() }
        val existing = File(bin, "jdx.cmd").apply { writeText("@echo off\r\nother\r\n") }

        val result = runInstall(repo, home)

        result.exitCode shouldBe 1
        result.stderr shouldStartWith "install.ps1: "
        result.stderr shouldContain "-Force"
        existing.readText() shouldContain "other"
    }

    @Test
    fun `Force replaces an unrelated existing shim`() {
        assumePwsh()
        val repo = fakeRepo()
        val home = tempDir("jdx-home-")
        File(home, ".local/bin").mkdirs()
        File(home, ".local/bin/jdx.cmd").writeText("unrelated")

        val result = runInstall(repo, home, listOf("-Force"))

        result.exitCode shouldBe 0
        File(home, ".local/bin/jdx.cmd").readText() shouldContain "jdx.bat"
    }

    @Test
    fun `a missing build is a clean error`() {
        assumePwsh()
        val repo = fakeRepo(withLauncher = false)
        val home = tempDir("jdx-home-")

        val result = runInstall(repo, home)

        result.exitCode shouldBe 1
        result.stderr shouldContain "no built launcher"
        result.stderr shouldContain "gradlew.bat :app:installDist"
    }

    @Test
    fun `BinDir override writes the shim there and notes PATH`() {
        assumePwsh()
        val repo = fakeRepo()
        val home = tempDir("jdx-home-")
        val binDir = tempDir("jdx-bin-")

        val result = runInstall(repo, home, listOf("-BinDir", binDir.absolutePath))

        result.exitCode shouldBe 0
        File(binDir, "jdx.cmd").isFile shouldBe true
        result.stderr shouldContain "not on your PATH"
    }
}
