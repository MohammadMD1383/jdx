package dev.jdx.cli.service

import dev.jdx.index.maven.MavenFetch
import kotlinx.serialization.Serializable
import java.io.EOFException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

/**
 * Self-update from GitHub Releases (`jdx upgrade`).
 *
 * Release installs (see `install-release.sh`) unpack `jdx-<version>.tar.gz`
 * into an install root holding the `jdx` launcher plus `libs/` and a
 * `.jdx-release` marker naming the repo and tag. This service resolves the
 * latest (or an explicit) tag through the GitHub API, downloads the tarball
 * with SHA-256 verification, and swaps it into the install root — the
 * `~/.local/bin/jdx` symlink keeps pointing at the same path, so it survives.
 *
 * Everything external is injected ([fetcher] for HTTP, [processRunner] for
 * `tar`, [installRoot] for the install location), so tests run fully offline:
 * no test ever touches the network or the real home directory. Never throws —
 * every failure reads as an [UpgradeOutcome] value the adapter maps to an
 * exit code (0 ok · 1 release not found · 3 not a release install · 5
 * download/checksum/IO failure).
 */
class UpgradeService(
    private val currentVersion: String,
    private val installRoot: Path? = detectInstallRoot(),
    private val fetcher: MavenFetch.Fetcher = MavenFetch.httpFetcher(),
    private val processRunner: ProcessRunner = RealProcessRunner,
) {

    /** What `jdx upgrade` was asked to do. */
    data class UpgradeRequest(
        val checkOnly: Boolean = false,
        val version: String? = null,
        val repo: String = DEFAULT_REPO,
    )

    /** Outcome values; see [exitCodeFor]. */
    sealed interface UpgradeOutcome {
        data class UpToDate(val current: String, val latest: String) : UpgradeOutcome
        data class Available(val current: String, val latest: String) : UpgradeOutcome
        data class Upgraded(val from: String, val to: String, val installDir: Path) : UpgradeOutcome
        data class NotReleaseInstall(val reason: String) : UpgradeOutcome
        data class ReleaseNotFound(val tag: String) : UpgradeOutcome
        data class Failed(val reason: String) : UpgradeOutcome
    }

    fun run(request: UpgradeRequest): UpgradeOutcome {
        val repo = request.repo.trim()
        if (repo.isEmpty() || "/" !in repo) {
            return UpgradeOutcome.Failed("bad repo '$repo' — expected OWNER/NAME")
        }
        val target = request.version?.trim()?.ifEmpty { null }
        val latest = if (target != null) {
            normaliseTag(target)
        } else {
            latestTag(repo) ?: return UpgradeOutcome.Failed(
                "could not resolve the latest release of '$repo' — check the network and repo name",
            )
        }
        if (target != null && !releaseExists(repo, latest)) {
            return UpgradeOutcome.ReleaseNotFound(latest)
        }
        val comparison = compareVersions(currentVersion, latest)
        if (request.checkOnly) {
            return if (comparison >= 0) {
                UpgradeOutcome.UpToDate(currentVersion, latest)
            } else {
                UpgradeOutcome.Available(currentVersion, latest)
            }
        }
        if (comparison >= 0) {
            return UpgradeOutcome.UpToDate(currentVersion, latest)
        }
        val root = installRoot
            ?: return UpgradeOutcome.NotReleaseInstall(
                "this jdx is not a release install (no .jdx-release marker beside the launcher) — " +
                    "install a release with install-release.sh first, or rebuild from source",
            )
        val marker = readMarker(root)
            ?: return UpgradeOutcome.NotReleaseInstall(
                "no .jdx-release marker in '$root' — refusing to touch a source or manual install",
            )
        if (!Files.isWritable(root)) {
            return UpgradeOutcome.Failed("install dir '$root' is not writable")
        }
        return installRelease(repo, latest, root, marker)
    }

    private fun installRelease(
        repo: String,
        tag: String,
        root: Path,
        marker: ReleaseMarker,
    ): UpgradeOutcome {
        if (marker.repo != repo) {
            return UpgradeOutcome.Failed(
                "install was created from '${marker.repo}', not '$repo' — " +
                    "pass --repo ${marker.repo} or reinstall explicitly",
            )
        }
        val bare = tag.removePrefix("v")
        val tarballUrl = "$GITHUB_BASE/$repo/releases/download/$tag/jdx-$bare.tar.gz"
        val tarball = try {
            fetcher.get(tarballUrl)
        } catch (_: Exception) {
            null
        } ?: return UpgradeOutcome.Failed("download failed: $tarballUrl")
        val checksumUrl = "$tarballUrl.sha256"
        val checksumBytes = try {
            fetcher.get(checksumUrl)
        } catch (_: Exception) {
            null
        } ?: return UpgradeOutcome.Failed(
            "no checksum at $checksumUrl — refusing an unverified artifact",
        )
        val expected = checksumBytes.toString(Charsets.UTF_8).trim().split(Regex("\\s+")).firstOrNull()
            .orEmpty().lowercase()
        if (expected.isEmpty() || !expected.all { it in '0'..'9' || it in 'a'..'f' }) {
            return UpgradeOutcome.Failed("checksum at $checksumUrl is not a hex digest")
        }
        val actual = sha256Hex(tarball)
        if (actual != expected) {
            return UpgradeOutcome.Failed("checksum mismatch for jdx-$bare.tar.gz — refusing to install")
        }
        val work = Files.createTempDirectory("jdx-upgrade-")
        return try {
            val archive = work.resolve("jdx-$bare.tar.gz")
            Files.write(archive, tarball)
            val unpacked = work.resolve("unpacked")
            Files.createDirectories(unpacked)
            // Pure-Java extraction first (no system `tar` on Windows); the
            // external `tar` stays as a fallback for tarballs the minimal
            // reader cannot parse (sparse/longlink extensions).
            val pureOk = runCatching { extractTarGz(archive, unpacked) }.isSuccess
            if (!pureOk) {
                val outcome = processRunner.run(Path.of("tar"), listOf("-xzf", "$archive", "-C", "$unpacked"))
                if (outcome.exitCode != 0) {
                    return UpgradeOutcome.Failed("could not extract jdx-$bare.tar.gz (tar exits ${outcome.exitCode})")
                }
            }
            val staged = unpacked.resolve("jdx")
            if (!hasLauncher(staged) || !hasFatJar(staged)) {
                return UpgradeOutcome.Failed("unexpected tarball layout — expected jdx/jdx + jdx/libs/jdx-*-all.jar")
            }
            swapTree(root, staged)
            Files.writeString(root.resolve(MARKER_FILE), "repo=$repo\ntag=$tag\n")
            UpgradeOutcome.Upgraded(currentVersion, tag, root)
        } catch (e: Exception) {
            UpgradeOutcome.Failed("install failed: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            try {
                deleteRecursively(work)
            } catch (_: Exception) {
                // Best effort: a stale temp dir never fails the upgrade.
            }
        }
    }

    /**
     * Atomically-ish replaces [root]'s contents with [staged]: the old tree
     * moves to a sibling backup first, so a failed copy restores the previous
     * install instead of leaving a half-written one.
     */
    private fun swapTree(root: Path, staged: Path) {
        val backup = root.resolveSibling("${root.fileName}.backup")
        deleteRecursively(backup)
        Files.move(root, backup)
        try {
            Files.move(staged, root)
        } catch (e: Exception) {
            Files.move(backup, root)
            throw e
        }
        deleteRecursively(backup)
    }

    private fun latestTag(repo: String): String? {
        val body = try {
            fetcher.get("$API_BASE/repos/$repo/releases/latest")?.toString(Charsets.UTF_8)
        } catch (_: Exception) {
            null
        } ?: return null
        return TAG_NAME_REGEX.find(body)?.groupValues?.getOrNull(1)?.takeIf { it.isNotEmpty() }
    }

    private fun releaseExists(repo: String, tag: String): Boolean {
        val bare = tag.removePrefix("v")
        val probe = try {
            fetcher.get("$GITHUB_BASE/$repo/releases/download/$tag/jdx-$bare.tar.gz.sha256")
        } catch (_: Exception) {
            null
        }
        return probe != null
    }

    companion object {
        const val DEFAULT_REPO: String = "MohammadMD1383/jdx"
        const val MARKER_FILE: String = ".jdx-release"

        private const val GITHUB_BASE: String = "https://github.com"
        private const val API_BASE: String = "https://api.github.com"

        private val TAG_NAME_REGEX: Regex = Regex("\"tag_name\"\\s*:\\s*\"([^\"]+)\"")

        /** `v1.1.0` stays `v1.1.0`; bare `1.1.0` gains the `v`. */
        fun normaliseTag(version: String): String =
            if (version.startsWith("v")) version else "v$version"

        /**
         * Total order over versions: leading `v` ignored, dotted runs compared
         * numerically, other runs lexically — except a non-numeric run always
         * sorts below a numeric one, and a non-zero tail only wins when it is
         * numeric. Net effect: `1.10` beats `1.9`, `1.0` equals `1.0.0`, and
         * `dev`/`*-SNAPSHOT` sort below any release (a dev build is always
         * offered the upgrade).
         */
        fun compareVersions(a: String, b: String): Int {
            val aParts = a.removePrefix("v").split(".", "-")
            val bParts = b.removePrefix("v").split(".", "-")
            val shared = minOf(aParts.size, bParts.size)
            for (i in 0 until shared) {
                val cmp = compareVersionPart(aParts[i], bParts[i])
                if (cmp != 0) return cmp
            }
            return compareVersionTail(aParts.drop(shared), bParts.drop(shared))
        }

        private fun compareVersionPart(x: String, y: String): Int {
            val xn = x.toIntOrNull()
            val yn = y.toIntOrNull()
            return when {
                xn != null && yn != null -> xn.compareTo(yn)
                xn == null && yn == null -> x.compareTo(y)
                xn == null -> -1
                else -> 1
            }
        }

        private fun compareVersionTail(aTail: List<String>, bTail: List<String>): Int {
            // An all-zero numeric tail adds nothing ("1.0" == "1.0.0"); any
            // other tail marks a prerelease/dev build, which sorts below.
            val aZero = aTail.all { it.toIntOrNull() == 0 }
            val bZero = bTail.all { it.toIntOrNull() == 0 }
            if (aZero && bZero) return 0
            if (aZero) return 1
            if (bZero) return -1
            val shared = minOf(aTail.size, bTail.size)
            for (i in 0 until shared) {
                val cmp = compareVersionPart(aTail[i], bTail[i])
                if (cmp != 0) return cmp
            }
            return 0
        }

        /** The install root of a release install, or null (source builds, tests, IDE runs). */
        fun detectInstallRoot(): Path? {
            return try {
                val code = UpgradeService::class.java.protectionDomain?.codeSource?.location?.toURI()
                    ?.let { Path.of(it) } ?: return null
                // Release layout: <root>/libs/jdx-<version>-all.jar next to the marker.
                val libs = code.parent ?: return null
                if (libs.fileName?.toString() != "libs") return null
                val root = libs.parent ?: return null
                if (Files.isRegularFile(root.resolve(MARKER_FILE))) root else null
            } catch (_: Exception) {
                null
            }
        }

        internal fun readMarker(root: Path): ReleaseMarker? {
            return try {
                val lines = Files.readAllLines(root.resolve(MARKER_FILE))
                val repo = lines.firstOrNull { it.startsWith("repo=") }?.removePrefix("repo=")?.trim()
                val tag = lines.firstOrNull { it.startsWith("tag=") }?.removePrefix("tag=")?.trim()
                if (repo.isNullOrEmpty() || tag.isNullOrEmpty()) null else ReleaseMarker(repo, tag)
            } catch (_: Exception) {
                null
            }
        }

        internal fun hasFatJar(staged: Path): Boolean {
            return try {
                Files.list(staged.resolve("libs")).use { stream ->
                    stream.anyMatch {
                        it.fileName.toString().startsWith("jdx-") &&
                            it.fileName.toString().endsWith("-all.jar")
                    }
                }
            } catch (_: Exception) {
                false
            }
        }

        internal fun deleteRecursively(path: Path) {
            if (!Files.exists(path)) return
            Files.walk(path).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }

        /**
         * The staged release launcher: `jdx` on Unix (which needs its
         * executable bit), `jdx.exe`/`.cmd`/`.bat` on Windows (which has no
         * executable bit — a regular file is enough). Pure — unit-tested.
         */
        internal fun hasLauncher(staged: Path): Boolean {
            if (isRunnableLauncher(staged.resolve("jdx"))) return true
            return listOf("jdx.exe", "jdx.cmd", "jdx.bat").any { name ->
                runCatching { Files.isRegularFile(staged.resolve(name)) }.getOrDefault(false)
            }
        }

        private fun isRunnableLauncher(file: Path): Boolean = runCatching {
            Files.isRegularFile(file) && Files.isExecutable(file)
        }.getOrDefault(false)

        internal fun sha256Hex(bytes: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            return digest.joinToString("") { "%02x".format(it) }
        }
    }
}

/** The `.jdx-release` marker: which repo this install tracks. */
internal data class ReleaseMarker(val repo: String, val tag: String)

/**
 * Extracts a gzip-compressed tar [archive] under [dest] in pure Java — the
 * no-`tar`-on-Windows path for `upgrade`. Handles regular files and
 * directories (the release layout); anything else (longname/sparse
 * extensions, malformed headers) throws so the caller falls back to the
 * external `tar`. Entry paths are confined to [dest] (zip-slip throws).
 * Never returns partially on failure — throws, and the caller discards [dest].
 */
internal fun extractTarGz(archive: Path, dest: Path) {
    Files.createDirectories(dest)
    val base = dest.toAbsolutePath().normalize()
    GZIPInputStream(Files.newInputStream(archive)).use { gzip ->
        while (true) {
            val header = readBlock(gzip) ?: break
            if (header.all { it == 0.toByte() }) break
            val name = headerName(header)
            val size = headerSize(header)
            val type = header[156].toInt().toChar()
            val target = base.resolve(name).normalize()
            if (!target.startsWith(base)) {
                throw java.io.IOException("tar entry escapes its directory: '$name'")
            }
            when (type) {
                '0', '\u0000' -> {
                    Files.createDirectories(target.parent)
                    Files.newOutputStream(target).use { out ->
                        var remaining = size
                        val buf = ByteArray(8192)
                        while (remaining > 0) {
                            val want = minOf(buf.size.toLong(), remaining).toInt()
                            val read = gzip.read(buf, 0, want)
                            if (read < 0) throw EOFException("truncated tar entry '$name'")
                            out.write(buf, 0, read)
                            remaining -= read
                        }
                    }
                    // Faithful tar behaviour: the release `jdx` launcher ships
                    // mode 0755, and the staged check below needs its bit.
                    if (headerMode(header) and 0x40 != 0) {
                        target.toFile().setExecutable(true)
                    }
                    skipPadding(gzip, size)
                }
                '5' -> {
                    Files.createDirectories(target)
                }
                else -> throw java.io.IOException("unsupported tar entry '$name' (type '$type')")
            }
        }
    }
}

private const val TAR_BLOCK: Int = 512

private fun readBlock(input: java.io.InputStream): ByteArray? {
    val block = ByteArray(TAR_BLOCK)
    var filled = 0
    while (filled < TAR_BLOCK) {
        val read = input.read(block, filled, TAR_BLOCK - filled)
        if (read < 0) {
            if (filled == 0) return null
            throw EOFException("truncated tar header")
        }
        filled += read
    }
    return block
}

private fun headerName(header: ByteArray): String {
    val end = header.indexOfFirst { it == 0.toByte() }.takeIf { it >= 0 } ?: 100
    return header.copyOfRange(0, end).toString(Charsets.UTF_8)
}

private fun headerSize(header: ByteArray): Long {
    val raw = header.copyOfRange(124, 136).toString(Charsets.US_ASCII).trim().trimEnd('\u0000').trim()
    if (raw.isEmpty()) return 0
    return raw.toLongOrNull(8)
        ?: throw java.io.IOException("malformed tar size '$raw'")
}

private fun headerMode(header: ByteArray): Int {
    val raw = header.copyOfRange(100, 108).toString(Charsets.US_ASCII).trim().trimEnd('\u0000').trim()
    return raw.toIntOrNull(8) ?: 0
}

private fun skipPadding(input: java.io.InputStream, size: Long) {
    val pad = ((TAR_BLOCK - size % TAR_BLOCK) % TAR_BLOCK).toInt()
    var remaining = pad
    val buf = ByteArray(512)
    while (remaining > 0) {
        val read = input.read(buf, 0, minOf(buf.size, remaining))
        if (read < 0) throw EOFException("truncated tar padding")
        remaining -= read
    }
}

/** Exit-code mapping (D-015): 0 ok · 1 release not found · 3 not a release install · 5 fetch/IO failure. */
internal fun exitCodeFor(outcome: UpgradeService.UpgradeOutcome): Int = when (outcome) {
    is UpgradeService.UpgradeOutcome.UpToDate -> 0
    is UpgradeService.UpgradeOutcome.Available -> 0
    is UpgradeService.UpgradeOutcome.Upgraded -> 0
    is UpgradeService.UpgradeOutcome.NotReleaseInstall -> 3
    is UpgradeService.UpgradeOutcome.ReleaseNotFound -> 1
    is UpgradeService.UpgradeOutcome.Failed -> 5
}

/** One JSON shape for every upgrade outcome: `status` names it, the rest carries the facts. */
@Serializable
data class UpgradePayload(
    val status: String,
    val current: String? = null,
    val latest: String? = null,
    val installDir: String? = null,
    val message: String,
)
