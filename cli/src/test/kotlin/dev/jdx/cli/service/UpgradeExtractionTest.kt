package dev.jdx.cli.service

import dev.jdx.index.maven.MavenFetch
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPOutputStream

/**
 * Pure-Java upgrade extraction (phase 5, tier 1): a hand-built `tar.gz`
 * installs with no system `tar` anywhere near the process, `jdx.exe`
 * layouts stage on Windows semantics, and zip-slip entries throw — all over
 * `@TempDir`, never the network or the real home.
 */
class UpgradeExtractionTest {

    private fun tarEntry(name: String, data: ByteArray, mode: Int = 0x1A4, type: Char = '0'): ByteArray {
        require(name.toByteArray(Charsets.UTF_8).size <= 100) { "name too long: $name" }
        val header = ByteArray(512)
        name.toByteArray(Charsets.UTF_8).copyInto(header, 0)
        mode.toString(8).padStart(7, '0').toByteArray(Charsets.US_ASCII).copyInto(header, 100)
        "0000000\u0000".toByteArray(Charsets.US_ASCII).copyInto(header, 108)
        "0000000\u0000".toByteArray(Charsets.US_ASCII).copyInto(header, 116)
        data.size.toString(8).padStart(11, '0').toByteArray(Charsets.US_ASCII).copyInto(header, 124)
        "00000000000\u0000".toByteArray(Charsets.US_ASCII).copyInto(header, 136)
        "        ".toByteArray(Charsets.US_ASCII).copyInto(header, 148)
        header[156] = type.code.toByte()
        "ustar\u0000".toByteArray(Charsets.US_ASCII).copyInto(header, 257)
        val checksum = header.sumOf { it.toInt() and 0xFF }
        checksum.toString(8).padStart(6, '0').toByteArray(Charsets.US_ASCII).copyInto(header, 148)
        header[154] = 0
        val out = ByteArrayOutputStream()
        out.write(header)
        out.write(data)
        out.write(ByteArray(((512 - data.size % 512) % 512)))
        return out.toByteArray()
    }

    private fun tarGz(vararg entries: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { gzip ->
            for (entry in entries) gzip.write(entry)
            gzip.write(ByteArray(1024))
        }
        return out.toByteArray()
    }

    private fun releaseTarball(executableMode: Boolean = true): ByteArray {
        val mode = if (executableMode) 0x1ED else 0x1A4
        return tarGz(
            tarEntry("jdx/jdx", "#!/bin/sh\necho jdx\n".toByteArray(), mode = mode),
            tarEntry("jdx/libs/jdx-1.1.0-all.jar", "fake-jar".toByteArray()),
        )
    }

    private class FakeFetcher(private val canned: Map<String, ByteArray>) : MavenFetch.Fetcher {
        override fun get(url: String): ByteArray? = canned[url]
    }

    private val noTar: ProcessRunner = ProcessRunner { _, _ ->
        throw AssertionError("system tar must not run when pure-Java extraction succeeds")
    }

    private fun releaseInstall(root: Path): Path {
        Files.createDirectories(root.resolve("libs"))
        Files.writeString(root.resolve("jdx"), "#!/bin/sh\n")
        Files.writeString(root.resolve("libs").resolve("jdx-1.0.0-all.jar"), "old")
        Files.writeString(root.resolve(UpgradeService.MARKER_FILE), "repo=o/n\ntag=v1.0.0\n")
        return root
    }

    private fun serviceFor(
        root: Path,
        tarball: ByteArray,
        runner: ProcessRunner = noTar,
    ): UpgradeService {
        val api = """{"tag_name":"v1.1.0"}""".toByteArray()
        val tarballUrl = "https://github.com/o/n/releases/download/v1.1.0/jdx-1.1.0.tar.gz"
        val fetcher = FakeFetcher(
            mapOf(
                "https://api.github.com/repos/o/n/releases/latest" to api,
                tarballUrl to tarball,
                "$tarballUrl.sha256" to
                    "${UpgradeService.sha256Hex(tarball)}  jdx-1.1.0.tar.gz".toByteArray(),
            ),
        )
        return UpgradeService("v1.0.0", root, fetcher, runner)
    }

    @Test
    fun `a release tarball installs with no system tar`(@TempDir tmp: Path) {
        val root = releaseInstall(tmp.resolve("jdx"))
        val service = serviceFor(root, releaseTarball())

        val outcome = service.run(UpgradeService.UpgradeRequest(repo = "o/n"))

        outcome shouldBe UpgradeService.UpgradeOutcome.Upgraded("v1.0.0", "v1.1.0", root)
        Files.isExecutable(root.resolve("jdx")) shouldBe true
        Files.isRegularFile(root.resolve("libs").resolve("jdx-1.1.0-all.jar")) shouldBe true
    }

    // No POSIX executable bit on Windows: every file reads executable, so a
    // bit-less tarball installs (correct there) and setExecutable(false) is a
    // no-op. Windows proves the shim path instead (`hasLauncher accepts
    // windows shims…` below plus the real jdx.bat smokes in CI).
    @Test
    @DisabledOnOs(OS.WINDOWS)
    fun `a tarball without an executable launcher is refused`(@TempDir tmp: Path) {
        val root = releaseInstall(tmp.resolve("jdx"))
        val service = serviceFor(root, releaseTarball(executableMode = false))

        val outcome = service.run(UpgradeService.UpgradeRequest(repo = "o/n"))

        (outcome is UpgradeService.UpgradeOutcome.Failed) shouldBe true
        (outcome as UpgradeService.UpgradeOutcome.Failed).reason shouldContain "unexpected tarball layout"
    }

    @Test
    fun `a foreign tarball falls back to the external tar`(@TempDir tmp: Path) {
        // Bytes no pure-Java reader parses: the injected `tar` answers instead.
        val root = releaseInstall(tmp.resolve("jdx"))
        val tarball = "not-a-tarball".toByteArray()
        val fakeTar: ProcessRunner = ProcessRunner { _, args ->
            val dest = Path.of(args.last())
            val staged = dest.resolve("jdx")
            Files.createDirectories(staged.resolve("libs"))
            Files.writeString(staged.resolve("jdx"), "#!/bin/sh\n")
            staged.resolve("jdx").toFile().setExecutable(true)
            Files.writeString(staged.resolve("libs").resolve("jdx-9.9.9-all.jar"), "fake")
            ProcessOutcome(0, "", "")
        }
        val service = serviceFor(root, tarball, fakeTar)

        val outcome = service.run(UpgradeService.UpgradeRequest(repo = "o/n"))

        outcome shouldBe UpgradeService.UpgradeOutcome.Upgraded("v1.0.0", "v1.1.0", root)
    }

    @Test
    fun `hasLauncher accepts windows shims without an executable bit`(@TempDir tmp: Path) {
        Files.writeString(tmp.resolve("jdx.exe"), "fake")
        UpgradeService.hasLauncher(tmp) shouldBe true
    }

    // Same no-exec-bit reason as above: setExecutable(false) cannot make a
    // file read non-executable on Windows.
    @Test
    @DisabledOnOs(OS.WINDOWS)
    fun `hasLauncher refuses a non-executable bare launcher`(@TempDir tmp: Path) {
        Files.writeString(tmp.resolve("jdx"), "fake")
        tmp.resolve("jdx").toFile().setExecutable(false)
        UpgradeService.hasLauncher(tmp) shouldBe false
    }

    @Test
    fun `a zip-slip entry throws instead of escaping`(@TempDir tmp: Path) {
        val archive = tmp.resolve("evil.tar.gz")
        Files.write(archive, tarGz(tarEntry("../evil.sh", "evil".toByteArray())))

        shouldThrow<java.io.IOException> {
            extractTarGz(archive, tmp.resolve("out"))
        }
        Files.exists(tmp.resolve("evil.sh")) shouldBe false
    }

    @Test
    fun `an absolute entry throws instead of escaping`(@TempDir tmp: Path) {
        val archive = tmp.resolve("abs.tar.gz")
        Files.write(archive, tarGz(tarEntry("/tmp/jdx-51-abs-evil", "evil".toByteArray())))

        shouldThrow<java.io.IOException> {
            extractTarGz(archive, tmp.resolve("out"))
        }
    }
}
