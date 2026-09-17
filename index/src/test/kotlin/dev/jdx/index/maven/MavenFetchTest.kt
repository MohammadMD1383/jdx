package dev.jdx.index.maven

import com.sun.net.httpserver.HttpServer
import io.kotest.matchers.shouldBe
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Tests for verified downloads (T-019). Byte-level checks run over injected
 * fake fetchers; one test serves bytes from a real loopback HTTP server so
 * the production [MavenFetch.httpGet] path is covered without touching the
 * network. Tier 1: localhost only, milliseconds.
 */
class MavenFetchTest {

    @Test
    fun `sha1Hex matches the known vector`() {
        MavenFetch.sha1Hex("abc".toByteArray(Charsets.UTF_8)) shouldBe "a9993e364706816aba3e25717850c26c9cd0d89d"
    }

    private fun shaFetcher(bytes: ByteArray, shaBody: String?): MavenFetch.Fetcher =
        MavenFetch.Fetcher { url ->
            when {
                url.endsWith(".sha1") -> shaBody?.toByteArray(Charsets.UTF_8)
                else -> bytes
            }
        }

    @Test
    fun `fetchVerified accepts a matching checksum`() {
        val bytes = "jar-bytes".toByteArray(Charsets.UTF_8)
        val sha = MavenFetch.sha1Hex(bytes)
        val result = MavenFetch.fetchVerified("http://x/demo-1.0.jar", shaFetcher(bytes, "$sha  demo-1.0.jar"))
        ((result is MavenFetch.FetchResult.Ok)) shouldBe true
        (result as MavenFetch.FetchResult.Ok).bytes shouldBe bytes
    }

    @Test
    fun `fetchVerified rejects a mismatching checksum`() {
        val bytes = "jar-bytes".toByteArray(Charsets.UTF_8)
        val result = MavenFetch.fetchVerified(
            "http://x/demo-1.0.jar",
            shaFetcher(bytes, "0".repeat(40)),
        )
        ((result is MavenFetch.FetchResult.Failed)) shouldBe true
        ((result as MavenFetch.FetchResult.Failed).failure.reason) shouldBe "checksum mismatch (want ${"0".repeat(40)}, got ${MavenFetch.sha1Hex(bytes)})"
    }

    @Test
    fun `fetchVerified fails closed without a checksum`() {
        val bytes = "jar-bytes".toByteArray(Charsets.UTF_8)
        val result = MavenFetch.fetchVerified("http://x/demo-1.0.jar", shaFetcher(bytes, null))
        ((result is MavenFetch.FetchResult.Failed)) shouldBe true
        (result as MavenFetch.FetchResult.Failed).failure.reason shouldBe
            "no checksum at http://x/demo-1.0.jar.sha1 — refusing an unverified artifact"
    }

    @Test
    fun `fetchVerified rejects a non-hex checksum body`() {
        val bytes = "jar-bytes".toByteArray(Charsets.UTF_8)
        val result = MavenFetch.fetchVerified("http://x/demo-1.0.jar", shaFetcher(bytes, "<html>nope</html>"))
        ((result is MavenFetch.FetchResult.Failed)) shouldBe true
    }

    @Test
    fun `fetchVerified reports a failed download`() {
        val result = MavenFetch.fetchVerified("http://x/demo-1.0.jar", MavenFetch.Fetcher { null })
        ((result is MavenFetch.FetchResult.Failed)) shouldBe true
        (result as MavenFetch.FetchResult.Failed).failure.reason shouldBe "download failed"
    }

    @Test
    fun `writeAtomically creates parents and round-trips bytes`(@TempDir tmp: Path) {
        val target = tmp.resolve("a/b/demo-1.0.jar")
        val bytes = "jar-bytes".toByteArray(Charsets.UTF_8)
        (MavenFetch.writeAtomically(target, bytes) == null) shouldBe true
        Files.readAllBytes(target) shouldBe bytes
    }

    @Test
    fun `httpGet serves a loopback server and refuses a 404`() {
        val bytes = "loopback-jar".toByteArray(Charsets.UTF_8)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        try {
            server.createContext("/repo/demo-1.0.jar") { exchange ->
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            server.createContext("/repo/missing-1.0.jar") { exchange ->
                exchange.sendResponseHeaders(404, -1)
                exchange.close()
            }
            server.start()
            val base = "http://127.0.0.1:${server.address.port}/repo"
            MavenFetch.httpGet("$base/demo-1.0.jar") shouldBe bytes
            (MavenFetch.httpGet("$base/missing-1.0.jar") == null) shouldBe true
            // End to end through fetchVerified over real HTTP.
            val sha = MavenFetch.sha1Hex(bytes)
            val verified = MavenFetch.fetchVerified(
                "$base/demo-1.0.jar",
                MavenFetch.Fetcher { url ->
                    if (url.endsWith(".sha1")) "$sha\n".toByteArray(Charsets.UTF_8)
                    else MavenFetch.httpGet(url)
                },
            )
            ((verified is MavenFetch.FetchResult.Ok)) shouldBe true
        } finally {
            server.stop(0)
        }
    }
}
