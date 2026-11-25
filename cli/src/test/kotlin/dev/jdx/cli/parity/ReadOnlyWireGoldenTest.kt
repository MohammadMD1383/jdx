package dev.jdx.cli.parity

import dev.jdx.core.rpc.RpcCommand
import dev.jdx.core.rpc.RpcRequest
import dev.jdx.index.workspace.InMemoryWorkspaceStore
import dev.jdx.mcp.McpSession
import dev.jdx.server.DaemonPaths
import dev.jdx.server.DaemonProbe
import dev.jdx.server.DaemonServer
import dev.jdx.server.JdxHttpServer
import dev.jdx.server.jdxServiceHandler
import dev.jdx.server.statusForExit
import dev.jdx.testsupport.golden.GoldenFiles
import dev.jdx.testsupport.paths.shortSocketDir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Tier 2: the read-only v1 wire (issue #19, PROPOSAL.md §14.5).
 *
 * `doctor` is named on the wire and refused by every serving adapter. The
 * envelopes are not byte-identical — each names its own transport and the
 * fix — so [AdapterParityTest] leaves them out and this suite golden-pins
 * them. HTTP status 500 is [statusForExit] of exit 6. `ws` and `cache` are
 * not commands: a request that names them is an unknown command, not a
 * mutation.
 *
 * Rewrite with `./gradlew :cli:tier2Test -Pgolden.update=true` — then read
 * the diff before committing it. A golden updated without reading is a test
 * deleted.
 */
@Tag("tier2")
class ReadOnlyWireGoldenTest {

    private val client: HttpClient = HttpClient.newHttpClient()

    // Short socket scope: sun_path caps binds (104 macOS) while a temp dir
    // can exceed that. Lazy so the path stays stable for the whole test.
    private val socketScope: Path by lazy { shortSocketDir("wire19") }

    private fun socket(): Path = DaemonPaths.socketPath(socketScope, "fx")

    private fun daemon(): DaemonServer {
        val socket = socket()
        lateinit var server: DaemonServer
        val handler = jdxServiceHandler(
            workspace = "fx",
            status = { server.snapshot() },
            appVersion = "test",
            store = InMemoryWorkspaceStore(),
        )
        server = DaemonServer(
            workspace = "fx",
            socketPath = socket,
            pidPath = DaemonPaths.pidPath(socket),
            idleTimeout = null,
            appVersion = "test",
            handler = handler,
        )
        return server
    }

    private fun http(): JdxHttpServer =
        JdxHttpServer(
            workspace = "fx",
            bind = "127.0.0.1",
            port = 0,
            appVersion = "test",
            store = InMemoryWorkspaceStore(),
        )

    private fun base(server: JdxHttpServer): String {
        val address = server.localAddress()
        return "http://${address.hostString}:${address.port}/v1"
    }

    private fun get(server: JdxHttpServer, path: String): HttpResponse<String> =
        client.send(
            HttpRequest.newBuilder(URI(base(server) + path)).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    private fun post(server: JdxHttpServer, path: String, body: String): HttpResponse<String> =
        client.send(
            HttpRequest.newBuilder(URI(base(server) + path))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .header("Content-Type", "application/x-ndjson")
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    @Test
    fun `doctor refusals are golden-pinned on the daemon HTTP and MCP`() {
        val daemon = daemon()
        val http = http()
        daemon.start()
        http.start()
        try {
            val daemonLine = DaemonProbe.roundTrip(socket(), RpcRequest(RpcCommand.DOCTOR))
            daemonLine shouldNotBe null
            daemonLine!! shouldNotContain "\n"
            daemonLine shouldContain "\"ok\":false"
            daemonLine shouldContain "\"command\":\"doctor\""
            daemonLine shouldContain "\"code\":6"
            daemonLine shouldContain "run the same query without the daemon"
            daemonLine shouldContain "jdx doctor"
            daemonLine shouldNotContain "T-082"

            val refused = get(http, "/doctor")
            // Exit 6 is an internal error on the HTTP map: 500, not a 4xx guess.
            statusForExit(6) shouldBe 500
            refused.statusCode() shouldBe statusForExit(6)
            refused.body() shouldNotContain "\n"
            refused.body() shouldContain "\"code\":6"
            refused.body() shouldContain "run `jdx doctor`"

            // The batch wrapper stays 200; the doctor line inside is the same
            // envelope the single-command route returns.
            val batch = post(http, "/batch", RpcRequest(RpcCommand.DOCTOR).encode())
            batch.statusCode() shouldBe 200
            batch.body() shouldBe refused.body()

            val mcp = McpSession(workspace = "fx", appVersion = "test", store = InMemoryWorkspaceStore())
                .callTool("jdx_doctor", buildJsonObject {})
            mcp.isError shouldBe true
            mcp.text shouldNotContain "\n"
            mcp.text shouldContain "\"code\":6"
            mcp.text shouldContain "run `jdx doctor`"

            // A label is echoed; it does not make the refusal a diagnosis.
            val labeled = DaemonProbe.roundTrip(socket(), RpcRequest(RpcCommand.DOCTOR, "probe"))
            labeled shouldNotBe null
            labeled!! shouldContain "\"query\":\"probe\""
            labeled shouldContain "run the same query without the daemon"
            get(http, "/doctor?query=probe").body() shouldContain "\"query\":\"probe\""
            McpSession(workspace = "fx", appVersion = "test", store = InMemoryWorkspaceStore())
                .callTool("jdx_doctor", buildJsonObject { put("query", "probe") })
                .text shouldContain "\"query\":\"probe\""

            GoldenFiles.verifyAll(
                File("src/test/resources/golden/wire"),
                mapOf(
                    "daemon-doctor.json" to daemonLine,
                    "http-doctor.json" to refused.body(),
                    "mcp-doctor.json" to mcp.text,
                ),
            )
        } finally {
            http.stop()
            daemon.stop()
        }
    }

    @Test
    fun `ws and cache are absent from the v1 wire on every adapter`() {
        RpcCommand.fromWire("ws") shouldBe null
        RpcCommand.fromWire("cache") shouldBe null
        RpcCommand.fromWire("doctor") shouldBe RpcCommand.DOCTOR

        val daemon = daemon()
        val http = http()
        daemon.start()
        http.start()
        try {
            for (name in listOf("ws", "cache")) {
                val line = DaemonProbe.rawRoundTrip(socket(), """{"command":"$name"}""")
                line shouldNotBe null
                line!! shouldContain "\"command\":\"unknown\""
                line shouldContain "\"code\":6"
                line shouldContain "malformed request"
                line shouldNotContain "\"command\":\"$name\""

                val response = get(http, "/$name")
                response.statusCode() shouldBe 404
                response.body() shouldContain "\"code\":6"
                response.body() shouldContain "unknown command '$name'"

                val tool = McpSession(workspace = "fx", appVersion = "test", store = InMemoryWorkspaceStore())
                    .callTool("jdx_$name", buildJsonObject {})
                tool.isError shouldBe true
                tool.text shouldContain "\"code\":6"
                tool.text shouldContain "unknown tool 'jdx_$name'"
            }
        } finally {
            http.stop()
            daemon.stop()
        }
    }
}
