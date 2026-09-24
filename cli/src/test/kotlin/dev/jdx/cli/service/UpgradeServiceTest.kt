package dev.jdx.cli.service

import dev.jdx.index.maven.MavenFetch
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * `UpgradeService` (tier 1): version ordering, check/upgrade decisions, and
 * the install swap — all over a fake [MavenFetch.Fetcher] and a fake `tar`
 * runner, so no test touches the network or the real home directory.
 */
class UpgradeServiceTest {

    private val apiLatest: (String) -> ByteArray = { tag ->
        """{"url":"https://api.github.com/repos/o/n/releases/1","tag_name":"$tag","assets":[]}"""
            .toByteArray()
    }

    /** Fake fetcher serving canned URL -> bytes; records every requested URL. */
    private class FakeFetcher(private val canned: Map<String, ByteArray>) : MavenFetch.Fetcher {
        val requested = mutableListOf<String>()
        override fun get(url: String): ByteArray? {
            requested.add(url)
            return canned[url]
        }
    }

    private fun releaseUrls(tag: String, repo: String = "o/n"): Pair<String, String> {
        val bare = tag.removePrefix("v")
        val tarball = "https://github.com/$repo/releases/download/$tag/jdx-$bare.tar.gz"
        return tarball to "$tarball.sha256"
    }

    /** Fake `tar -xzf <archive> -C <dir>`: materialises the release layout instead. */
    private val fakeTar: ProcessRunner = ProcessRunner { _, args ->
        val dest = Path.of(args.last())
        val root = dest.resolve("jdx")
        Files.createDirectories(root.resolve("libs"))
        Files.writeString(root.resolve("jdx"), "#!/bin/sh\n")
        root.resolve("jdx").toFile().setExecutable(true)
        Files.writeString(root.resolve("libs").resolve("jdx-9.9.9-all.jar"), "fake")
        ProcessOutcome(0, "", "")
    }

    private fun releaseInstall(
        root: Path,
        repo: String = "o/n",
        tag: String = "v1.0.0",
    ): Path {
        Files.createDirectories(root.resolve("libs"))
        Files.writeString(root.resolve("jdx"), "#!/bin/sh\n")
        Files.writeString(root.resolve("libs").resolve("jdx-1.0.0-all.jar"), "old")
        Files.writeString(root.resolve(UpgradeService.MARKER_FILE), "repo=$repo\ntag=$tag\n")
        return root
    }

    @Test
    fun `newer dotted versions win numerically, not lexically`() {
        UpgradeService.compareVersions("v1.10.0", "v1.9.0") shouldBe 1
        UpgradeService.compareVersions("v1.9.0", "v1.10.0") shouldBe -1
        UpgradeService.compareVersions("v1.1.0", "v1.0.9") shouldBe 1
    }

    @Test
    fun `equal versions compare zero across spellings`() {
        UpgradeService.compareVersions("v1.0.0", "1.0.0") shouldBe 0
        UpgradeService.compareVersions("v1.0", "v1.0.0") shouldBe 0
        UpgradeService.compareVersions("v1.1.0", "v1.1.0") shouldBe 0
    }

    @Test
    fun `dev and snapshot builds sort below any release`() {
        (UpgradeService.compareVersions("dev", "v0.0.1") < 0) shouldBe true
        (UpgradeService.compareVersions("1.0.0-SNAPSHOT", "v1.0.0") < 0) shouldBe true
    }

    @Test
    fun `comparison is reflexive and antisymmetric`() = runBlocking {
        checkAll(Arb.list(Arb.int(0..20), 1..4), Arb.list(Arb.int(0..20), 1..4)) { aParts, bParts ->
            val a = "v" + aParts.joinToString(".")
            val b = "v" + bParts.joinToString(".")
            UpgradeService.compareVersions(a, a) shouldBe 0
            val ab = UpgradeService.compareVersions(a, b)
            val ba = UpgradeService.compareVersions(b, a)
            (ab == -ba) shouldBe true
        }
    }

    @Test
    fun `--check on the latest reports up to date`() {
        val service = UpgradeService(
            currentVersion = "v1.1.0",
            installRoot = null,
            fetcher = FakeFetcher(
                mapOf("https://api.github.com/repos/o/n/releases/latest" to apiLatest("v1.1.0")),
            ),
        )

        val outcome = service.run(UpgradeService.UpgradeRequest(checkOnly = true, repo = "o/n"))

        outcome shouldBe UpgradeService.UpgradeOutcome.UpToDate("v1.1.0", "v1.1.0")
        exitCodeFor(outcome) shouldBe 0
    }

    @Test
    fun `--check behind latest reports available`() {
        val service = UpgradeService(
            currentVersion = "v1.0.0",
            installRoot = null,
            fetcher = FakeFetcher(
                mapOf("https://api.github.com/repos/o/n/releases/latest" to apiLatest("v1.1.0")),
            ),
        )

        val outcome = service.run(UpgradeService.UpgradeRequest(checkOnly = true, repo = "o/n"))

        outcome shouldBe UpgradeService.UpgradeOutcome.Available("v1.0.0", "v1.1.0")
        exitCodeFor(outcome) shouldBe 0
    }

    @Test
    fun `an explicit version is normalised and existence-checked`() {
        val (_, checksumUrl) = releaseUrls("v1.1.0")
        val fetcher = FakeFetcher(mapOf(checksumUrl to "ab  jdx-1.1.0.tar.gz".toByteArray()))
        val service = UpgradeService(
            currentVersion = "v1.0.0",
            installRoot = null,
            fetcher = fetcher,
        )

        val outcome = service.run(
            UpgradeService.UpgradeRequest(checkOnly = true, version = "1.1.0", repo = "o/n"),
        )

        // Bare "1.1.0" normalises to tag "v1.1.0", whose checksum probe succeeds.
        outcome shouldBe UpgradeService.UpgradeOutcome.Available("v1.0.0", "v1.1.0")
        (checksumUrl in fetcher.requested) shouldBe true
    }

    @Test
    fun `an explicit unknown version reads as release-not-found`() {
        val service = UpgradeService(
            currentVersion = "v1.0.0",
            installRoot = null,
            fetcher = FakeFetcher(emptyMap()),
        )

        val outcome = service.run(
            UpgradeService.UpgradeRequest(checkOnly = true, version = "v9.9.9", repo = "o/n"),
        )

        outcome shouldBe UpgradeService.UpgradeOutcome.ReleaseNotFound("v9.9.9")
        exitCodeFor(outcome) shouldBe 1
    }

    @Test
    fun `an unreachable api reads as failed, never a throw`() {
        val service = UpgradeService(
            currentVersion = "v1.0.0",
            installRoot = null,
            fetcher = FakeFetcher(emptyMap()),
        )

        val outcome = shouldNotThrowAny {
            service.run(UpgradeService.UpgradeRequest(checkOnly = true, repo = "o/n"))
        }

        (outcome is UpgradeService.UpgradeOutcome.Failed) shouldBe true
        exitCodeFor(outcome) shouldBe 5
    }

    @Test
    fun `upgrade on a non-release install is refused`() {
        val service = UpgradeService(
            currentVersion = "v1.0.0",
            installRoot = null,
            fetcher = FakeFetcher(
                mapOf("https://api.github.com/repos/o/n/releases/latest" to apiLatest("v1.1.0")),
            ),
        )

        val outcome = service.run(UpgradeService.UpgradeRequest(repo = "o/n"))

        (outcome is UpgradeService.UpgradeOutcome.NotReleaseInstall) shouldBe true
        exitCodeFor(outcome) shouldBe 3
    }

    @Test
    fun `upgrade swaps the tree and records the new tag`(@TempDir tmp: Path) {
        val root = releaseInstall(tmp.resolve("jdx"))
        val tarball = "fake-tarball-bytes".toByteArray()
        val fetcher = FakeFetcher(
            mapOf(
                "https://api.github.com/repos/o/n/releases/latest" to apiLatest("v1.1.0"),
                releaseUrls("v1.1.0").first to tarball,
                releaseUrls("v1.1.0").second to
                    "${UpgradeService.sha256Hex(tarball)}  jdx-1.1.0.tar.gz".toByteArray(),
            ),
        )
        val service = UpgradeService("v1.0.0", root, fetcher, fakeTar)

        val outcome = service.run(UpgradeService.UpgradeRequest(repo = "o/n"))

        outcome shouldBe UpgradeService.UpgradeOutcome.Upgraded("v1.0.0", "v1.1.0", root)
        exitCodeFor(outcome) shouldBe 0
        Files.isExecutable(root.resolve("jdx")) shouldBe true
        Files.isRegularFile(root.resolve("libs").resolve("jdx-9.9.9-all.jar")) shouldBe true
        Files.readString(root.resolve(UpgradeService.MARKER_FILE)) shouldContain "tag=v1.1.0"
    }

    @Test
    fun `a checksum mismatch never touches the install`(@TempDir tmp: Path) {
        val root = releaseInstall(tmp.resolve("jdx"))
        val tarball = "fake-tarball-bytes".toByteArray()
        val fetcher = FakeFetcher(
            mapOf(
                "https://api.github.com/repos/o/n/releases/latest" to apiLatest("v1.1.0"),
                releaseUrls("v1.1.0").first to tarball,
                releaseUrls("v1.1.0").second to "0".repeat(64).toByteArray(),
            ),
        )
        val service = UpgradeService("v1.0.0", root, fetcher, fakeTar)

        val outcome = service.run(UpgradeService.UpgradeRequest(repo = "o/n"))

        (outcome is UpgradeService.UpgradeOutcome.Failed) shouldBe true
        (outcome as UpgradeService.UpgradeOutcome.Failed).reason shouldContain "checksum mismatch"
        exitCodeFor(outcome) shouldBe 5
        Files.readString(root.resolve("libs").resolve("jdx-1.0.0-all.jar")) shouldBe "old"
        Files.readString(root.resolve(UpgradeService.MARKER_FILE)) shouldContain "tag=v1.0.0"
    }

    @Test
    fun `a missing tarball fails closed`(@TempDir tmp: Path) {
        val root = releaseInstall(tmp.resolve("jdx"))
        val fetcher = FakeFetcher(
            mapOf("https://api.github.com/repos/o/n/releases/latest" to apiLatest("v1.1.0")),
        )
        val service = UpgradeService("v1.0.0", root, fetcher, fakeTar)

        val outcome = service.run(UpgradeService.UpgradeRequest(repo = "o/n"))

        (outcome is UpgradeService.UpgradeOutcome.Failed) shouldBe true
        exitCodeFor(outcome) shouldBe 5
    }

    @Test
    fun `a bad repo name fails fast without network`() {
        val fetcher = FakeFetcher(emptyMap())
        val service = UpgradeService("v1.0.0", null, fetcher, fakeTar)

        val outcome = service.run(UpgradeService.UpgradeRequest(checkOnly = true, repo = "not-a-repo"))

        (outcome is UpgradeService.UpgradeOutcome.Failed) shouldBe true
        (fetcher.requested.isEmpty()) shouldBe true
    }
}
