package dev.jdx.app

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

/**
 * Tests of install-release.ps1 (issue #92): per-user Windows release install into
 * `%LOCALAPPDATA%\Programs\jdx`, optional `jdx.cmd` shim under a distinct `-BinDir`,
 * refusal to clobber an unrelated shim without `-Force`, and clean errors on bad input.
 *
 * Builds its own fake `.zip` (a stub `jdx.bat` + empty fat jar in the `jdx/` +
 * `jdx/libs/` layout the release workflow publishes) and installs from it via
 * `-Tarball`, so no network and no real build output is needed. LOCALAPPDATA /
 * install dirs point at temp directories, so the developer's own profile is
 * untouched. `JDX_SKIP_USER_PATH=1` keeps the persistent user-PATH mutation off.
 *
 * Runs under `pwsh` on any host where it is available (Linux CI included — same
 * seam as [WindowsLauncherScriptTest]'s tier-2); skips with a reason otherwise.
 * Windows CI (#52) always has PowerShell.
 */
class InstallReleasePs1Test {

    private val projectDir = File(System.getProperty("user.dir"))
    private val repoRoot = projectDir.parentFile
    private val installScript = File(repoRoot, "install-release.ps1")

    private data class ProcessResult(val exitCode: Int, val stdout: String, val stderr: String)

    private fun pwshIsAvailable(): Boolean =
        try {
            runPwsh(listOf("-Command", "\$PSVersionTable.PSVersion")).exitCode == 0
        } catch (_: Exception) {
            false
        }

    private fun assumePwsh() {
        assumeTrue(installScript.isFile, "install-release.ps1 missing at ${installScript.absolutePath}")
        assumeTrue(pwshIsAvailable(), "pwsh not on PATH")
    }

    private fun runPwsh(
        args: List<String>,
        environment: Map<String, String> = emptyMap(),
        workingDir: File? = null,
    ): ProcessResult {
        val process = ProcessBuilder(listOf("pwsh", "-NoProfile", "-NonInteractive") + args)
            .directory(workingDir ?: repoRoot)
            .redirectErrorStream(false)
            .apply {
                environment().putAll(environment)
            }
            .start()
        val stdout = process.inputStream.bufferedReader().readText()
        val stderr = process.errorStream.bufferedReader().readText()
        return ProcessResult(process.waitFor(), stdout, stderr)
    }

    private fun runInstallRelease(
        localAppData: File,
        extraArgs: List<String> = emptyList(),
        extraEnv: Map<String, String> = emptyMap(),
    ): ProcessResult {
        val env = mutableMapOf(
            "LOCALAPPDATA" to localAppData.absolutePath,
            "JDX_SKIP_USER_PATH" to "1",
        )
        env.putAll(extraEnv)
        return runPwsh(
            listOf("-File", installScript.absolutePath) + extraArgs,
            environment = env,
            workingDir = localAppData,
        )
    }

    private fun tempDir(prefix: String): File = Files.createTempDirectory(prefix).toFile()

    /**
     * The quoted forwarder path inside a `jdx.cmd` shim, canonicalised so
     * macOS `/var` vs `/private/var` (Java realpath vs PowerShell GetFullPath)
     * cannot falsely fail the pin.
     */
    private fun shimTarget(shim: File): File {
        val match = Regex("\"([^\"]+)\"").find(shim.readText().replace("\r\n", "\n"))
            ?: error("no quoted path in shim:\n${shim.readText()}")
        return File(match.groupValues[1]).canonicalFile
    }

    /**
     * Builds a minimal Windows release `.zip` (`jdx/jdx.bat` + fat jar) via
     * `java.util.zip` — no external zip binary needed.
     */
    private fun fakeWindowsZip(name: String = "jdx-0.0.0-windows.zip"): File {
        val work = tempDir("jdx-winzip-")
        val zip = File(work, name)
        java.util.zip.ZipOutputStream(zip.outputStream().buffered()).use { out ->
            out.putNextEntry(java.util.zip.ZipEntry("jdx/jdx.bat"))
            out.write("@echo off\r\necho jdx version 0.0.0-test\r\n".toByteArray())
            out.closeEntry()
            out.putNextEntry(java.util.zip.ZipEntry("jdx/jdx.ps1"))
            out.write("Write-Output 'jdx version 0.0.0-test'\n".toByteArray())
            out.closeEntry()
            out.putNextEntry(java.util.zip.ZipEntry("jdx/libs/jdx-0.0.0-all.jar"))
            out.write("fake".toByteArray())
            out.closeEntry()
        }
        return zip
    }

    @Test
    fun `script is present and documents the Windows defaults`() {
        assumeTrue(installScript.isFile, "install-release.ps1 missing")
        val bytes = installScript.readBytes()
        // Windows PowerShell 5.1 reads .ps1 as the system ANSI code page unless a
        // BOM is present; non-ASCII UTF-8 (em dashes, etc.) corrupts the parse
        // and breaks the windows.yml smoke that runs under `shell: powershell`.
        bytes.any { it < 0 } shouldBe false
        val text = bytes.toString(Charsets.US_ASCII)
        text shouldContain "LOCALAPPDATA"
        text shouldContain "Programs\\jdx"
        text shouldContain "jdx-<version>-windows.zip"
        text shouldContain ".jdx-release"
        text shouldContain "JDX_SKIP_USER_PATH"
        text shouldContain "issue #92"
    }

    @Test
    fun `fresh install unpacks the windows zip into LocalAppData Programs jdx`() {
        assumePwsh()
        val localAppData = tempDir("jdx-lapp-")
        val zip = fakeWindowsZip()

        val result = runInstallRelease(
            localAppData,
            listOf("-Tarball", zip.absolutePath),
        )

        result.exitCode shouldBe 0
        val installDir = File(localAppData, "Programs/jdx")
        File(installDir, "jdx.bat").isFile shouldBe true
        File(installDir, "libs/jdx-0.0.0-all.jar").isFile shouldBe true
        File(installDir, ".jdx-release").readText() shouldContain "tag=v0.0.0"
        File(installDir, ".jdx-release").readText() shouldContain "repo=MohammadMD1383/jdx"
        result.stdout shouldContain "installed:"
        result.stderr shouldContain "not on your PATH"
    }

    @Test
    fun `re-running against our own install is idempotent`() {
        assumePwsh()
        val localAppData = tempDir("jdx-lapp-")
        val zip = fakeWindowsZip()
        runInstallRelease(localAppData, listOf("-Tarball", zip.absolutePath)).exitCode shouldBe 0

        val result = runInstallRelease(localAppData, listOf("-Tarball", zip.absolutePath))

        result.exitCode shouldBe 0
        File(localAppData, "Programs/jdx/jdx.bat").isFile shouldBe true
    }

    @Test
    fun `a distinct BinDir gets a jdx-cmd shim that forwards to the install`() {
        assumePwsh()
        val localAppData = tempDir("jdx-lapp-")
        val binDir = tempDir("jdx-bin-")
        val zip = fakeWindowsZip()

        val result = runInstallRelease(
            localAppData,
            listOf("-Tarball", zip.absolutePath, "-BinDir", binDir.absolutePath),
        )

        result.exitCode shouldBe 0
        val shim = File(binDir, "jdx.cmd")
        shim.isFile shouldBe true
        shimTarget(shim) shouldBe File(localAppData, "Programs/jdx/jdx.bat").canonicalFile
        // cmd shim stays CRLF (*.cmd mirrors *.bat eol=crlf).
        shim.readBytes().let { bytes ->
            val bareLf = bytes.indices.any { i ->
                bytes[i] == '\n'.code.toByte() && (i == 0 || bytes[i - 1] != '\r'.code.toByte())
            }
            bareLf shouldBe false
        }
    }

    @Test
    fun `an unrelated existing shim is refused without Force and left untouched`() {
        assumePwsh()
        val localAppData = tempDir("jdx-lapp-")
        val binDir = tempDir("jdx-bin-")
        val existing = File(binDir, "jdx.cmd").apply { writeText("@echo off\r\nsome other tool\r\n") }
        val zip = fakeWindowsZip()

        val result = runInstallRelease(
            localAppData,
            listOf("-Tarball", zip.absolutePath, "-BinDir", binDir.absolutePath),
        )

        result.exitCode shouldBe 1
        // `shouldContain` (not shouldStartWith): an elevated Windows shell may
        // print the per-user note on stderr before the refusal line.
        result.stderr shouldContain "install-release.ps1: "
        result.stderr shouldContain "-Force"
        existing.readText() shouldContain "some other tool"
    }

    @Test
    fun `Force replaces an unrelated existing shim`() {
        assumePwsh()
        val localAppData = tempDir("jdx-lapp-")
        val binDir = tempDir("jdx-bin-")
        File(binDir, "jdx.cmd").writeText("unrelated")
        val zip = fakeWindowsZip()

        val result = runInstallRelease(
            localAppData,
            listOf("-Tarball", zip.absolutePath, "-BinDir", binDir.absolutePath, "-Force"),
        )

        result.exitCode shouldBe 0
        File(binDir, "jdx.cmd").readText() shouldContain "jdx.bat"
    }

    @Test
    fun `a directory at the shim target is refused even with Force`() {
        assumePwsh()
        val localAppData = tempDir("jdx-lapp-")
        val binDir = tempDir("jdx-bin-")
        File(binDir, "jdx.cmd").mkdirs()
        val zip = fakeWindowsZip()

        val result = runInstallRelease(
            localAppData,
            listOf("-Tarball", zip.absolutePath, "-BinDir", binDir.absolutePath, "-Force"),
        )

        result.exitCode shouldBe 1
        result.stderr shouldContain "is a directory"
        File(binDir, "jdx.cmd").isDirectory shouldBe true
    }

    @Test
    fun `a missing tarball is a clean error`() {
        assumePwsh()
        val localAppData = tempDir("jdx-lapp-")

        val result = runInstallRelease(
            localAppData,
            listOf("-Tarball", "/does/not/exist.zip"),
        )

        result.exitCode shouldBe 1
        result.stderr shouldContain "not found"
    }

    @Test
    fun `a tar-gz tarball is refused with a pointer at the POSIX installer`() {
        assumePwsh()
        val localAppData = tempDir("jdx-lapp-")
        val fakeTar = File(tempDir("jdx-tar-"), "jdx-0.0.0.tar.gz").apply { writeText("not a zip") }

        val result = runInstallRelease(
            localAppData,
            listOf("-Tarball", fakeTar.absolutePath),
        )

        result.exitCode shouldBe 1
        result.stderr shouldContain "install-release.sh"
        result.stderr shouldContain ".zip"
    }

    @Test
    fun `a zip without the launcher is a clean error`() {
        assumePwsh()
        val localAppData = tempDir("jdx-lapp-")
        val work = tempDir("jdx-badzip-")
        val zip = File(work, "jdx-0.0.0-windows.zip")
        java.util.zip.ZipOutputStream(zip.outputStream().buffered()).use { out ->
            out.putNextEntry(java.util.zip.ZipEntry("jdx/libs/jdx-0.0.0-all.jar"))
            out.write("fake".toByteArray())
            out.closeEntry()
        }

        val result = runInstallRelease(
            localAppData,
            listOf("-Tarball", zip.absolutePath),
        )

        result.exitCode shouldBe 1
        result.stderr shouldContain "jdx.bat"
    }

    @Test
    fun `a per-OS zip name still recovers the release tag`() {
        assumePwsh()
        val localAppData = tempDir("jdx-lapp-")
        val zip = fakeWindowsZip("jdx-1.2.3-windows.zip")

        val result = runInstallRelease(
            localAppData,
            listOf("-Tarball", zip.absolutePath),
        )

        result.exitCode shouldBe 0
        File(localAppData, "Programs/jdx/.jdx-release").readText() shouldContain "tag=v1.2.3"
    }

    @Test
    fun `Help prints usage and exits zero`() {
        assumePwsh()
        val localAppData = tempDir("jdx-lapp-")

        val result = runInstallRelease(localAppData, listOf("-Help"))

        result.exitCode shouldBe 0
        result.stdout shouldContain "usage:"
    }
}
