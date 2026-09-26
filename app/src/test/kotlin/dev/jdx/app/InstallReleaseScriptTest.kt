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
 * Tests of install-release.sh: per-user install of a release tarball into
 * `~/.local/share/jdx` + a `~/.local/bin/jdx` symlink, refusal to clobber an
 * unrelated `jdx` without --force, and clean errors on bad input.
 *
 * Builds its own fake tarball (a stub launcher + an empty fat jar in the
 * `jdx/` + `jdx/libs/` layout the release workflow publishes) and installs
 * from it via `--tarball`, so no network and no real build output is needed.
 * HOME points at a temp directory, so the developer's own home is untouched.
 */
@DisabledOnOs(
    OS.WINDOWS,
    disabledReason = "Drives install-release.sh through sh with POSIX ln/tar/readlink assumptions " +
        "(tar treats C: as a remote host); Windows installs from the -windows.zip asset instead",
)
class InstallReleaseScriptTest {

    private val projectDir = File(System.getProperty("user.dir"))
    private val repoRoot = projectDir.parentFile
    private val installScript = File(repoRoot, "install-release.sh")

    private data class ProcessResult(val exitCode: Int, val stdout: String, val stderr: String)

    private fun runInstallRelease(
        home: File,
        extraArgs: List<String> = emptyList(),
        extraEnv: Map<String, String> = emptyMap(),
    ): ProcessResult {
        val process = ProcessBuilder(listOf("sh", installScript.absolutePath) + extraArgs)
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
     * A PATH overlay emulating macOS/BSD tools (#47): `readlink` and `basename` reject
     * GNU-only `-f`/`--` flags, and `mktemp` requires a `-t` template the way older
     * macOS releases do. Anything else delegates to the real binary, so a passing run
     * proves install-release.sh uses no GNU-only flag.
     *
     * `mkdir`, `cp`, `chmod`, `rm` and `ln` reject a `--` operand separator the same
     * way: BSD takes it only inconsistently (rm/ln/dirname do, mkdir/cp/chmod do
     * not — proven by the macOS CI run that closed #47), so installer scripts use
     * no `--` at all and these shims forbid it.
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
        val realBasename = realToolPath("basename")
        File(dir, "basename").apply {
            writeText(
                """
                #!/bin/sh
                for a in "${'$'}@"; do
                    case "${'$'}a" in
                        --*) printf 'basename: illegal option %s\n' "${'$'}a" >&2; exit 1 ;;
                    esac
                done
                exec "$realBasename" "${'$'}@"
                """.trimIndent(),
            )
            setExecutable(true, false)
        }
        val realMktemp = realToolPath("mktemp")
        File(dir, "mktemp").apply {
            writeText(
                """
                #!/bin/sh
                # Old-macOS mktemp: bare `mktemp` and `mktemp -d` fail; `-t prefix` required.
                tmpl=
                is_dir=0
                prev=
                for a in "${'$'}@"; do
                    if [ "${'$'}prev" = "-t" ]; then tmpl=${'$'}a; prev=; continue; fi
                    case "${'$'}a" in
                        -t) prev=-t ;;
                        -d) is_dir=1 ;;
                    esac
                done
                if [ -z "${'$'}tmpl" ]; then
                    printf 'mktemp: BSD mktemp requires -t prefix\n' >&2
                    exit 1
                fi
                dest=${'$'}{TMPDIR:-/tmp}/${'$'}tmpl.XXXXXX
                if [ "${'$'}is_dir" -eq 1 ]; then exec "$realMktemp" -d "${'$'}dest"
                else exec "$realMktemp" "${'$'}dest"; fi
                """.trimIndent(),
            )
            setExecutable(true, false)
        }
        // BSD mkdir/cp/chmod/rm/ln take `--` only inconsistently: a shim per tool
        // that fails on `--` and delegates otherwise, so Linux runs prove the
        // installer stays `--`-free.
        for (tool in listOf("mkdir", "cp", "chmod", "rm", "ln")) {
            val realTool = realToolPath(tool)
            File(dir, tool).apply {
                writeText(
                    """
                    #!/bin/sh
                    for a in "${'$'}@"; do
                        case "${'$'}a" in
                            --) printf '$tool: illegal option --\n' >&2; exit 1 ;;
                        esac
                    done
                    exec "$realTool" "${'$'}@"
                    """.trimIndent(),
                )
                setExecutable(true, false)
            }
        }
        return dir
    }

    private fun bsdPathEnv(): Map<String, String> = mapOf(
        "PATH" to bsdStubBin().absolutePath + File.pathSeparator + System.getenv("PATH"),
    )

    private fun sha256Hex(file: File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(file.readBytes())
        return digest.joinToString("") { "%02x".format(it) }
    }

    /**
     * A fake `curl` serving release assets from [serveDir] by URL basename. Understands
     * `-o <dest> <url>` and ignores the hardening flags (`--proto`, `--tlsv1.2`,
     * `--retry`), so the download path runs offline.
     */
    private fun fakeCurlScript(serveDir: File): String =
        """
        #!/bin/sh
        dest=
        url=
        prev=
        for a in "${'$'}@"; do
            if [ "${'$'}prev" = "-o" ]; then dest=${'$'}a; prev=; continue; fi
            case "${'$'}a" in -o) prev=-o;; -*) ;; *) url=${'$'}a;; esac
        done
        case "${'$'}url" in
            *.sha256) cp "${serveDir.absolutePath}/jdx-0.0.0.tar.gz.sha256" "${'$'}dest" ;;
            *.tar.gz) cp "${serveDir.absolutePath}/jdx-0.0.0.tar.gz" "${'$'}dest" ;;
            *) exit 1 ;;
        esac
        """.trimIndent()

    /**
     * A fake Windows `CertUtil` hashing via the real sha256sum but printing the
     * two-line `CertUtil -hashfile` layout (upper-case hex on line 2), so the
     * checksum branch for Git-Bash is exercised without Windows.
     */
    private fun fakeCertUtilScript(): String {
        val realSha256sum = realToolPath("sha256sum")
        val realCut = realToolPath("cut")
        val realTr = realToolPath("tr")
        return """
            #!/bin/sh
            hex=$("$realSha256sum" "${'$'}2" | "$realCut" -d ' ' -f1 | "$realTr" 'a-z' 'A-Z')
            printf 'SHA256 hash of %s:\r\n%s\r\nCertUtil: -hashfile command completed successfully.\r\n' "${'$'}2" "${'$'}hex"
            """.trimIndent()
    }

    /**
     * A hermetic PATH dir: symlinks to the real tools the installer needs, plus fake
     * entries from [fakes]. Anything not symlinked or faked (notably sha256sum/shasum
     * unless the caller adds them) is invisible — `command -v` fails for it.
     * Symlink creation needs privilege on Windows (Developer Mode) — without it
     * the suite skips with a reason instead of erroring (issue #52).
     */
    private fun hermeticToolPath(fakes: Map<String, String>): File {
        val dir = tempDir("jdx-tools-")
        val tools = listOf(
            "sh", "id", "tar", "gzip", "mkdir", "rm", "cp", "chmod", "grep", "head",
            "sed", "cut", "tr", "mktemp", "readlink", "ln", "basename",
        )
        for (tool in tools) {
            try {
                Files.createSymbolicLink(File(dir, tool).toPath(), File(realToolPath(tool)).toPath())
            } catch (e: UnsupportedOperationException) {
                org.junit.jupiter.api.Assumptions.assumeTrue(false, "symlinks unsupported: ${e.message}")
            } catch (e: java.io.IOException) {
                org.junit.jupiter.api.Assumptions.assumeTrue(false, "symlinks need privilege (Windows Developer Mode): ${e.message}")
            } catch (e: SecurityException) {
                org.junit.jupiter.api.Assumptions.assumeTrue(false, "symlinks blocked: ${e.message}")
            }
        }
        for ((name, content) in fakes) {
            File(dir, name).apply {
                writeText(content)
                setExecutable(true, false)
            }
        }
        return dir
    }

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

    /**
     * Builds the same release layout as a `.zip` (issue #48) via
     * `java.util.zip` — no external `zip` binary needed, and the entries
     * deliberately carry no POSIX exec bit (like an Explorer-repacked zip),
     * so a passing install proves the installer re-applies `+x` itself.
     */
    private fun fakeZipTarball(name: String = "jdx-0.0.0.zip"): File {
        val work = tempDir("jdx-relzip-")
        val zip = File(work, name)
        java.util.zip.ZipOutputStream(zip.outputStream().buffered()).use { out ->
            out.putNextEntry(java.util.zip.ZipEntry("jdx/jdx"))
            out.write("#!/bin/sh\necho \"jdx version 0.0.0-test\"\n".toByteArray())
            out.closeEntry()
            out.putNextEntry(java.util.zip.ZipEntry("jdx/libs/jdx-0.0.0-all.jar"))
            out.write("fake".toByteArray())
            out.closeEntry()
        }
        return zip
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

    @Test
    fun `fresh install unpacks a zip archive and symlinks the launcher`() {
        // The per-OS `.zip` bundle (issue #48): same `jdx/` layout as the
        // tarball, installed through the same `--tarball` seam. The fixture
        // zip stores no exec bit, so success also proves the installer
        // re-applies `+x` to the POSIX launcher itself.
        val home = tempDir("jdx-home-")
        val tarball = fakeZipTarball()

        val result = runInstallRelease(home, listOf("--tarball", tarball.absolutePath))

        result.exitCode shouldBe 0
        val target = File(home, ".local/bin/jdx")
        Files.isSymbolicLink(target.toPath()) shouldBe true
        target.canonicalPath shouldBe File(home, ".local/share/jdx/jdx").canonicalPath
        File(home, ".local/share/jdx/jdx").canExecute() shouldBe true
        File(home, ".local/share/jdx/libs/jdx-0.0.0-all.jar").exists() shouldBe true
        result.stdout shouldContain "installed:"
    }

    @Test
    fun `a per-OS zip name still recovers the release tag`() {
        // `jdx-<bare>-<os>.zip` offline installs must stamp the bare version
        // into `.jdx-release`, or `jdx upgrade` later compares against garbage.
        val home = tempDir("jdx-home-")
        val tarball = fakeZipTarball("jdx-0.0.0-windows.zip")

        val result = runInstallRelease(home, listOf("--tarball", tarball.absolutePath))

        result.exitCode shouldBe 0
        File(home, ".local/share/jdx/.jdx-release").readText() shouldContain "tag=v0.0.0"
    }

    @Test
    fun `a zip without the launcher is a clean error`() {
        val home = tempDir("jdx-home-")
        val work = tempDir("jdx-relzip-")
        val tarball = File(work, "jdx-0.0.0.zip")
        java.util.zip.ZipOutputStream(tarball.outputStream().buffered()).use { out ->
            out.putNextEntry(java.util.zip.ZipEntry("jdx/libs/jdx-0.0.0-all.jar"))
            out.write("fake".toByteArray())
            out.closeEntry()
        }

        val result = runInstallRelease(home, listOf("--tarball", tarball.absolutePath))

        result.exitCode shouldBe 1
        result.stderr shouldContain "no executable 'jdx/jdx' launcher"
    }

    @Test
    fun `a --tarball that is neither tar gz nor zip is rejected`() {
        val home = tempDir("jdx-home-")
        val other = tempDir("jdx-other-")
        val notArchive = File(other, "jdx-0.0.0.tar.bz2").apply { writeText("fake") }

        val result = runInstallRelease(home, listOf("--tarball", notArchive.absolutePath))

        result.exitCode shouldBe 1
        result.stderr shouldContain "must end in .tar.gz or .zip"
    }

    @Test
    fun `offline install works with BSD-only mktemp, basename and readlink`() {
        // The stub dir shadows the real tools: bare `mktemp`/`mktemp -d` fail (old
        // macOS needs `-t`), and any `--`/`-f` passed to readlink/basename is a hard
        // error — so this fails unless the script avoids every GNU-only flag (#47).
        val home = tempDir("jdx-home-")
        val tarball = fakeTarball()

        val result =
            runInstallRelease(home, listOf("--tarball", tarball.absolutePath), extraEnv = bsdPathEnv())

        result.exitCode shouldBe 0
        val target = File(home, ".local/bin/jdx")
        Files.isSymbolicLink(target.toPath()) shouldBe true
        target.canonicalPath shouldBe File(home, ".local/share/jdx/jdx").canonicalPath
        File(home, ".local/share/jdx/libs/jdx-0.0.0-all.jar").exists() shouldBe true
    }

    @Test
    fun `re-running against our own install is idempotent with BSD-only readlink`() {
        val home = tempDir("jdx-home-")
        val tarball = fakeTarball()
        runInstallRelease(home, listOf("--tarball", tarball.absolutePath))

        val result =
            runInstallRelease(home, listOf("--tarball", tarball.absolutePath), extraEnv = bsdPathEnv())

        result.exitCode shouldBe 0
        File(home, ".local/bin/jdx").canonicalPath shouldBe
            File(home, ".local/share/jdx/jdx").canonicalPath
    }

    @Test
    fun `a matching published checksum installs cleanly`() {
        val home = tempDir("jdx-home-")
        val tarball = fakeTarball()
        val serveDir = tarball.parentFile
        File(serveDir, "jdx-0.0.0.tar.gz.sha256")
            .writeText("${sha256Hex(tarball)}  jdx-0.0.0.tar.gz\n")
        val curlDir = tempDir("jdx-fakecurl-")
        File(curlDir, "curl").apply {
            writeText(fakeCurlScript(serveDir))
            setExecutable(true, false)
        }
        val pathEnv = mapOf(
            "PATH" to curlDir.absolutePath + File.pathSeparator + System.getenv("PATH"),
        )

        val result = runInstallRelease(home, listOf("--version", "0.0.0"), extraEnv = pathEnv)

        result.exitCode shouldBe 0
        File(home, ".local/share/jdx/libs/jdx-0.0.0-all.jar").exists() shouldBe true
    }

    @Test
    fun `a published checksum that mismatches fails loudly`() {
        val home = tempDir("jdx-home-")
        val tarball = fakeTarball()
        val serveDir = tarball.parentFile
        File(serveDir, "jdx-0.0.0.tar.gz.sha256")
            .writeText("${"0".repeat(64)}  jdx-0.0.0.tar.gz\n")
        val curlDir = tempDir("jdx-fakecurl-")
        File(curlDir, "curl").apply {
            writeText(fakeCurlScript(serveDir))
            setExecutable(true, false)
        }
        val pathEnv = mapOf(
            "PATH" to curlDir.absolutePath + File.pathSeparator + System.getenv("PATH"),
        )

        val result = runInstallRelease(home, listOf("--version", "0.0.0"), extraEnv = pathEnv)

        result.exitCode shouldBe 1
        result.stderr shouldContain "checksum mismatch"
    }

    @Test
    fun `a published checksum with no verifier on PATH is a loud failure, not a silent skip`() {
        // Hermetic PATH: every tool the installer needs except sha256sum/shasum/
        // CertUtil — so a correct published checksum still cannot be checked (#47).
        val home = tempDir("jdx-home-")
        val tarball = fakeTarball()
        val serveDir = tarball.parentFile
        File(serveDir, "jdx-0.0.0.tar.gz.sha256")
            .writeText("${sha256Hex(tarball)}  jdx-0.0.0.tar.gz\n")
        val tools = hermeticToolPath(mapOf("curl" to fakeCurlScript(serveDir)))

        val result = runInstallRelease(
            home,
            listOf("--version", "0.0.0"),
            extraEnv = mapOf("PATH" to tools.absolutePath),
        )

        result.exitCode shouldBe 1
        result.stderr shouldContain "cannot verify"
        result.stderr shouldStartWith("install-release.sh: ")
    }

    @Test
    fun `a published checksum verifies through the CertUtil branch`() {
        // Hermetic PATH with no sha256sum/shasum but a fake Windows CertUtil: success
        // proves the Git-Bash branch parses CertUtil output and compares digests (#47).
        val home = tempDir("jdx-home-")
        val tarball = fakeTarball()
        val serveDir = tarball.parentFile
        File(serveDir, "jdx-0.0.0.tar.gz.sha256")
            .writeText("${sha256Hex(tarball)}  jdx-0.0.0.tar.gz\n")
        val tools = hermeticToolPath(
            mapOf(
                "curl" to fakeCurlScript(serveDir),
                "certutil" to fakeCertUtilScript(),
            ),
        )

        val result = runInstallRelease(
            home,
            listOf("--version", "0.0.0"),
            extraEnv = mapOf("PATH" to tools.absolutePath),
        )

        result.exitCode shouldBe 0
        File(home, ".local/share/jdx/libs/jdx-0.0.0-all.jar").exists() shouldBe true
    }
}
