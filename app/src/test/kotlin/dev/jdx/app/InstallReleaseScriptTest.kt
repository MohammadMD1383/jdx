package dev.jdx.app

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

/**
 * Tests of install-release.sh: per-user install of a release tarball into
 * `~/.local/share/jdx` + a `~/.local/bin/jdx` symlink, refusal to clobber an
 * unrelated `jdx` without --force, and clean errors on bad input.
 *
 * Builds its own fake tarball (a stub launcher + an empty fat jar in the
 * `jdx/` + `jdx/libs/` layout the release workflow publishes) and installs
 * from it via `--tarball`, so no network and no real build output is needed.
 * HOME points at a temp directory, so the developer's own home is untouched.
 */
class InstallReleaseScriptTest {

    private val projectDir = File(System.getProperty("user.dir"))
    private val repoRoot = projectDir.parentFile
    private val installScript = File(repoRoot, "install-release.sh")

    private data class ProcessResult(val exitCode: Int, val stdout: String, val stderr: String)

    private fun runInstallRelease(
        home: File,
        extraArgs: List<String> = emptyList(),
    ): ProcessResult {
        val process = ProcessBuilder(listOf("sh", installScript.absolutePath) + extraArgs)
            .directory(home)
            .apply { environment()["HOME"] = home.absolutePath }
            .start()
        val stdout = process.inputStream.bufferedReader().readText()
        val stderr = process.errorStream.bufferedReader().readText()
        return ProcessResult(process.waitFor(), stdout, stderr)
    }

    private fun tempDir(prefix: String): File = Files.createTempDirectory(prefix).toFile()

    private fun userBin(home: File): File = File(home, ".local/bin").apply { mkdirs() }

    /** Builds a minimal tarball in the release layout and returns its path. */
    private fun fakeTarball(): File {
        val work = tempDir("jdx-reltar-")
        val root = File(work, "jdx").apply { mkdirs() }
        File(root, "jdx").apply {
            writeText("#!/bin/sh\necho \"jdx version 0.0.0-test\"\n")
            setExecutable(true, false)
        }
        File(root, "libs").apply { mkdirs() }
        File(File(root, "libs"), "jdx-0.0.0-all.jar").apply { writeText("fake") }
        val tarball = File(work, "jdx-0.0.0.tar.gz")
        val tar = ProcessBuilder("tar", "-czf", tarball.absolutePath, "-C", work.absolutePath, "jdx")
            .redirectErrorStream(true)
            .start()
        val out = tar.inputStream.bufferedReader().readText()
        require(tar.waitFor() == 0) { "tar failed: $out" }
        return tarball
    }

    @Test
    fun `fresh install unpacks the tarball and symlinks the launcher`() {
        val home = tempDir("jdx-home-")
        val tarball = fakeTarball()

        val result = runInstallRelease(home, listOf("--tarball", tarball.absolutePath))

        result.exitCode shouldBe 0
        val target = File(home, ".local/bin/jdx")
        Files.isSymbolicLink(target.toPath()) shouldBe true
        target.canonicalPath shouldBe File(home, ".local/share/jdx/jdx").canonicalPath
        File(home, ".local/share/jdx/libs/jdx-0.0.0-all.jar").exists() shouldBe true
        result.stdout shouldContain "installed:"
        result.stderr shouldContain "not on your PATH" // fresh HOME is never on PATH
    }

    @Test
    fun `re-running against our own install is idempotent`() {
        val home = tempDir("jdx-home-")
        val tarball = fakeTarball()
        runInstallRelease(home, listOf("--tarball", tarball.absolutePath))

        val result = runInstallRelease(home, listOf("--tarball", tarball.absolutePath))

        result.exitCode shouldBe 0
        File(home, ".local/bin/jdx").canonicalPath shouldBe
            File(home, ".local/share/jdx/jdx").canonicalPath
    }

    @Test
    fun `an unrelated existing jdx is refused without --force and left untouched`() {
        val home = tempDir("jdx-home-")
        val tarball = fakeTarball()
        val existing = File(userBin(home), "jdx").apply { writeText("#!/bin/sh\nsome other tool\n") }

        val result = runInstallRelease(home, listOf("--tarball", tarball.absolutePath))

        result.exitCode shouldBe 1
        result.stderr shouldStartWith("install-release.sh: ")
        result.stderr shouldContain "--force"
        existing.readText() shouldContain "some other tool"
        Files.isSymbolicLink(existing.toPath()) shouldBe false
    }

    @Test
    fun `--force replaces an unrelated existing jdx`() {
        val home = tempDir("jdx-home-")
        val tarball = fakeTarball()
        File(userBin(home), "jdx").apply { writeText("unrelated") }

        val result = runInstallRelease(home, listOf("--tarball", tarball.absolutePath, "--force"))

        result.exitCode shouldBe 0
        File(home, ".local/bin/jdx").canonicalPath shouldBe
            File(home, ".local/share/jdx/jdx").canonicalPath
    }

    @Test
    fun `a directory at the target is refused even with --force`() {
        val home = tempDir("jdx-home-")
        val tarball = fakeTarball()
        File(userBin(home), "jdx").apply { mkdirs() }

        val result = runInstallRelease(home, listOf("--tarball", tarball.absolutePath, "--force"))

        result.exitCode shouldBe 1
        result.stderr shouldContain "is a directory"
        File(home, ".local/bin/jdx").isDirectory shouldBe true
    }

    @Test
    fun `unknown arguments are rejected`() {
        val home = tempDir("jdx-home-")

        val result = runInstallRelease(home, listOf("--nonsense"))

        result.exitCode shouldBe 1
        result.stderr shouldContain "unknown argument"
    }

    @Test
    fun `a missing tarball is a clean error`() {
        val home = tempDir("jdx-home-")

        val result = runInstallRelease(home, listOf("--tarball", "/does/not/exist.tar.gz"))

        result.exitCode shouldBe 1
        result.stderr shouldContain "not found"
    }
}
