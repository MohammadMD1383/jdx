package dev.jdx.index.maven

import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * Downloads bytes over HTTP(S) with checksum verification (T-019).
 *
 * Production downloads go through [httpGet] (timeouts, redirect following, a
 * download cap); tests inject a fake [Fetcher] serving canned bytes, so no test
 * ever touches the network. Every entry point is total — network, checksum and
 * filesystem failures read as [FetchFailure], never as throws (TESTING.md §7).
 */
public object MavenFetch {

    /** Fetches the bytes at [url], or `null` on any failure. Never throws. */
    public fun interface Fetcher {
        public fun get(url: String): ByteArray?
    }

    /** A fetch that failed: [url] names the artifact, [reason] names the problem. */
    public data class FetchFailure(val url: String, val reason: String)

    /**
     * Two-element result: `Ok` bytes, or a `Failed` value an agent branches on —
     * never an exception (CONTRIBUTING.md: failures are values).
     */
    public sealed interface FetchResult {
        public data class Ok(val bytes: ByteArray) : FetchResult
        public data class Failed(val failure: FetchFailure) : FetchResult
    }

    /** Upper bound on one download: a Maven jar bigger than this is pathological. */
    public const val MAX_DOWNLOAD_BYTES: Long = 512L * 1024L * 1024L

    public const val CONNECT_TIMEOUT_MS: Int = 15_000
    public const val READ_TIMEOUT_MS: Int = 30_000

    /** The production [Fetcher]: plain `HttpURLConnection` (no new dependencies). */
    public fun httpFetcher(): Fetcher = Fetcher { url -> httpGet(url) }

    /**
     * Downloads [url] (redirects followed), capped at [MAX_DOWNLOAD_BYTES].
     * Returns `null` on any failure: DNS, refused connection, non-200 status,
     * timeout, over-cap body, or a fired security manager.
     */
    public fun httpGet(url: String): ByteArray? {
        return try {
            val connection = URI.create(url).toURL().openConnection() as? HttpURLConnection
                ?: return null
            try {
                connection.instanceFollowRedirects = true
                connection.connectTimeout = CONNECT_TIMEOUT_MS
                connection.readTimeout = READ_TIMEOUT_MS
                connection.setRequestProperty("Accept", "*/*")
                connection.connect()
                if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
                connection.inputStream.use { stream ->
                    val out = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val read = stream.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_DOWNLOAD_BYTES) return null
                        out.write(buffer, 0, read)
                    }
                    out.toByteArray()
                }
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Downloads [url] and verifies its SHA-1 against the checksum file at
     * `$url.sha1` (first whitespace-separated token, case-insensitive — the
     * Maven Central layout). A missing or mismatching checksum fails closed:
     * an unverified artifact is never written (PROPOSAL.md §17).
     */
    public fun fetchVerified(url: String, fetcher: Fetcher = httpFetcher()): FetchResult {
        val bytes = try {
            fetcher.get(url)
        } catch (e: Exception) {
            null
        } ?: return FetchResult.Failed(FetchFailure(url, "download failed"))
        val checksumUrl = "$url.sha1"
        val checksumBytes = try {
            fetcher.get(checksumUrl)
        } catch (e: Exception) {
            null
        } ?: return FetchResult.Failed(
            FetchFailure(url, "no checksum at $checksumUrl — refusing an unverified artifact"),
        )
        val expected = checksumBytes.toString(Charsets.UTF_8).trim().split(Regex("\\s+")).firstOrNull()
            .orEmpty().lowercase()
        if (expected.isEmpty() || !expected.all { it in '0'..'9' || it in 'a'..'f' }) {
            return FetchResult.Failed(
                FetchFailure(url, "checksum at $checksumUrl is not a SHA-1 hex digest"),
            )
        }
        val actual = sha1Hex(bytes)
        if (actual != expected) {
            return FetchResult.Failed(
                FetchFailure(url, "checksum mismatch (want $expected, got $actual)"),
            )
        }
        return FetchResult.Ok(bytes)
    }

    /**
     * Writes [bytes] to [target] atomically (temp file + move), creating parent
     * directories. Returns the failure message, or `null` on success. Never throws.
     */
    public fun writeAtomically(target: Path, bytes: ByteArray): String? {
        return try {
            val parent = target.parent
            if (parent != null) Files.createDirectories(parent)
            val temp = if (parent != null) {
                Files.createTempFile(parent, ".jdx-download-", ".tmp")
            } else {
                Files.createTempFile(".jdx-download-", ".tmp")
            }
            try {
                Files.write(temp, bytes)
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (e: Exception) {
                // Filesystems without atomic-move support (some `/tmp` mounts): plain replace.
                try {
                    Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
                } catch (e2: Exception) {
                    runCatching { Files.deleteIfExists(temp) }
                    return e2.message ?: e2.javaClass.simpleName
                }
            }
            null
        } catch (e: Exception) {
            e.message ?: e.javaClass.simpleName
        }
    }

    /** Lowercase hex SHA-1 of [bytes]. */
    public fun sha1Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-1")
        return digest.digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
