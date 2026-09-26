package dev.jdx.app

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import java.io.File
import java.nio.file.Files

/**
 * Tests of install.sh: per-user install into ~/.local/bin, refusal to clobber an unrelated
 * `jdx` without --force, and clean errors when the build is missing. Runs against the real
 * build output (app/build/jdx — the test task depends on :app:installDist) with HOME pointed
 * at a temp directory, so the developer's own ~/.local/bin is never touched.
 */
@DisabledOnOs(
    OS.WINDOWS,
    disabledReason = "Drives install.sh through sh with POSIX ln/tar/readlink assumptions " +
        "(C: paths, symlinks); Windows has no shell-installer equivalent to cover",
)
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
        extraEnv: Map<String, String> = emptyMap(),
    ): ProcessResult {
        val process = ProcessBuilder(listOf("sh", script.absolutePath) + extraArgs)
            .directory(home)
            .apply {
                environment()["HOME"] = home.absolutePath
                environment().putAll(extraEnv)
            }
            .start()
        val stdout = process.inputStream.bufferedReader().readText()
        val stderr = process.errorStream.bufferedReader().readText()
        return ProcessResult(process.waitFor(), stdout, stderr)
    }

    private fun tempDir(prefix: String): File = Files.createTempDirectory(prefix).toFile()

    private fun userBin(home: File): File = File(home, ".local/bin").apply { mkdirs() }

    private fun realToolPath(name: String): String {
        val process = ProcessBuilder("sh", "-c", "command -v $name").start()
        val path = process.inputStream.bufferedReader().readText().trim()
        require(process.waitFor() == 0 && path.isNotBlank()) { "$name not found on PATH" }
        return path
    }

    /**
     * A PATH overlay emulating macOS/BSD `readlink`: no `-f`, no `--` — any GNU-only
     * flag is a hard error. Everything else delegates to the real binary, so a passing
     * run proves install.sh resolves symlinks without GNU coreutils (#47).
     */
    private fun bsdStubBin(): File {
        val dir = tempDir("jdx-bsd-")
        val realReadlink = realToolPath("readlink")
        File(dir, "readlink").apply {
            writeText(
                """
                #!/bin/sh
                for a in "${'$'}@"; do
                    case "${'$'}a" in
                        -f|--*) printf 'readlink: illegal option %s\n' "${'$'}a" >&2; exit 1 ;;
                    esac
                done
                exec "$realReadlink" "${'$'}@"
                """.trimIndent(),
            )
            setExecutable(true, false)
        }
        return dir
    }

    private fun bsdPathEnv(): Map<String, String> = mapOf(
        "PATH" to bsdStubBin().absolutePath + File.pathSeparator + System.getenv("PATH"),
    )

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
    fun `fresh install works with BSD-only readlink (no GNU flags)`() {
        val home = tempDir("jdx-home-")

        val result = runInstallScript(home, extraEnv = bsdPathEnv())

        result.exitCode shouldBe 0
        val target = File(home, ".local/bin/jdx")
        Files.isSymbolicLink(target.toPath()) shouldBe true
        target.canonicalPath shouldBe builtLauncher.canonicalPath
    }

    @Test
    fun `re-running against our own symlink is idempotent with BSD-only readlink`() {
        // The idempotence check reads the existing symlink back with `readlink`: under
        // the BSD stub any `--`/`-f` flag is a hard error, so this fails unless the
        // script uses plain `readlink` (#47).
        val home = tempDir("jdx-home-")
        runInstallScript(home)

        val result = runInstallScript(home, extraEnv = bsdPathEnv())

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
