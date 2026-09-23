package dev.jdx.server

import dev.jdx.core.rpc.RPC_VERSION
import dev.jdx.core.rpc.RpcCommand
import dev.jdx.core.rpc.RpcRequest
import dev.jdx.index.service.JdxService
import dev.jdx.index.service.dispatch
import dev.jdx.index.workspace.InMemoryWorkspaceStore
import dev.jdx.index.workspace.WorkspaceDefinition
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.io.File
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail

/**
 * Tier 2: the HTTP server answers over real TCP on an ephemeral port (T-044).
 *
 * The acceptance is body parity: each route returns the same bytes as the
 * in-process [JdxService.dispatch] call, so the T-046 proof stays structural —
 * only the status code is HTTP-new (pinned here via [statusForExit]).
 */
@Tag("tier2")
class HttpServerTest {

    private val client: HttpClient = HttpClient.newHttpClient()

    private fun fixtureJar(): File {
        val dir = System.getProperty("jdx.fixturesDir")?.let { File(it) }
            ?: fail("jdx.fixturesDir not set (server build wires it; see server/build.gradle.kts)")
        return dir.listFiles { file ->
            file.isFile && file.name.startsWith("testfixtures-") && !file.name.endsWith("-sources.jar")
        }?.singleOrNull() ?: fail("expected exactly one binary fixture jar in $dir")
    }

    private fun fixtureStore(name: String = "fx"): InMemoryWorkspaceStore =
        InMemoryWorkspaceStore().also {
            it.save(WorkspaceDefinition(name = name, jars = listOf(fixtureJar().absolutePath), includeJdk = false))
        }

    private fun fixtureRoots(): JdxService.RootsSpec =
        JdxService.RootsSpec(jarSpecs = listOf(fixtureJar().absolutePath), includeJdk = false)

    /** A server on 127.0.0.1 with an ephemeral port; the caller stops it. */
    private fun httpServer(workspace: String = "fx", store: InMemoryWorkspaceStore = fixtureStore()): JdxHttpServer =
        JdxHttpServer(workspace = workspace, bind = "127.0.0.1", port = 0, appVersion = "test-0.0.0", store = store)

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

    private fun enc(text: String): String = URLEncoder.encode(text, StandardCharsets.UTF_8)

    @Test
    fun `bind and port defaults stay localhost-only`() {
        DEFAULT_HTTP_BIND shouldBe "127.0.0.1"
        DEFAULT_HTTP_PORT shouldBe 7070
    }

    @Test
    fun `exit codes map to their http statuses`() {
        statusForExit(0) shouldBe 200
        statusForExit(1) shouldBe 404
        statusForExit(2) shouldBe 300
        statusForExit(3) shouldBe 400
        statusForExit(4) shouldBe 404
        statusForExit(5) shouldBe 502
        statusForExit(6) shouldBe 500
        statusForExit(99) shouldBe 500
    }

    @Test
    fun `query strings parse total and last-wins`() {
        parseQueryString(null) shouldBe emptyMap()
        parseQueryString("") shouldBe emptyMap()
        parseQueryString("query=a&limit=5") shouldBe mapOf("query" to "a", "limit" to "5")
        parseQueryString("limit=5&limit=7") shouldBe mapOf("limit" to "7")
        parseQueryString("bare") shouldBe mapOf("bare" to "")
        parseQueryString("a+b=c%20d") shouldBe mapOf("a b" to "c d")
        parseQueryString("a=%ZZ") shouldBe null
    }

    @Test
    fun `health answers the daemon-shaped envelope`() {
        val server = httpServer()
        server.start()
        try {
            val response = get(server, "/health")
            response.statusCode() shouldBe 200
            response.body() shouldContain "\"command\":\"health\""
            response.body() shouldContain "\"ok\":true"
            response.body() shouldContain "\"rpcVersion\":$RPC_VERSION"
            response.body() shouldContain "\"workspace\":\"fx\""
            response.body() shouldNotContain "\n"
        } finally {
            server.stop()
        }
    }

    @Test
    fun `one query GET returns the same bytes as the in-process call`() {
        val server = httpServer()
        server.start()
        try {
            val query = "dev.jdx.fixtures.Generics"
            val response = get(server, "/members?query=${enc(query)}&limit=5")
            val expected = JdxService.dispatch(
                RpcRequest(RpcCommand.MEMBERS, query, mapOf("limit" to "5")),
                fixtureRoots(),
            ).toJson("members")
            response.statusCode() shouldBe 200
            response.body() shouldBe expected
        } finally {
            server.stop()
        }
    }

    @Test
    fun `a not-found query carries its 404 status with the identical envelope`() {
        val server = httpServer()
        server.start()
        try {
            val query = "dev.jdx.fixtures.NoSuchType"
            val response = get(server, "/show?query=${enc(query)}")
            val expected = JdxService.dispatch(RpcRequest(RpcCommand.SHOW, query), fixtureRoots()).toJson("show")
            response.statusCode() shouldBe 404
            response.body() shouldBe expected
            response.body() shouldContain "\"code\":1"
        } finally {
            server.stop()
        }
    }

    @Test
    fun `a batch POST answers one envelope line per request`() {
        val server = httpServer()
        server.start()
        try {
            val show = RpcRequest(RpcCommand.SHOW, "dev.jdx.fixtures.Generics")
            val members = RpcRequest(RpcCommand.MEMBERS, "dev.jdx.fixtures.Generics", mapOf("limit" to "3"))
            val response = post(server, "/batch", show.frame() + members.frame())
            val expected = listOf(
                JdxService.dispatch(show, fixtureRoots()).toJson("show"),
                JdxService.dispatch(members, fixtureRoots()).toJson("members"),
            ).joinToString("\n")
            response.statusCode() shouldBe 200
            response.body() shouldBe expected
        } finally {
            server.stop()
        }
    }

    @Test
    fun `a batch survives a malformed line and an empty body is usage`() {
        val server = httpServer()
        server.start()
        try {
            val good = RpcRequest(RpcCommand.SHOW, "dev.jdx.fixtures.Generics")
            val mixed = post(server, "/batch", "{not json\n" + good.frame())
            mixed.statusCode() shouldBe 200
            val lines = mixed.body().split('\n')
            lines.size shouldBe 2
            lines[0] shouldContain "\"command\":\"unknown\""
            lines[0] shouldContain "\"code\":6"
            lines[1] shouldBe JdxService.dispatch(good, fixtureRoots()).toJson("show")

            val empty = post(server, "/batch", "\n  \n")
            empty.statusCode() shouldBe 400
            empty.body() shouldContain "\"code\":3"
        } finally {
            server.stop()
        }
    }

    @Test
    fun `a missing workspace reads as exit 4, with a per-call override`() {
        val store = fixtureStore()
        val server = httpServer(workspace = "missing", store = store)
        server.start()
        try {
            val query = "dev.jdx.fixtures.Generics"
            val missing = get(server, "/show?query=${enc(query)}")
            missing.statusCode() shouldBe 404
            missing.body() shouldContain "\"code\":4"
            missing.body() shouldContain "no such workspace 'missing'"

            val viaParam = client.send(
                HttpRequest.newBuilder(URI("${base(server)}/show?query=${enc(query)}&workspace=fx")).GET().build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            viaParam.statusCode() shouldBe 200
            viaParam.body() shouldBe JdxService.dispatch(
                RpcRequest(RpcCommand.SHOW, query),
                fixtureRoots(),
            ).toJson("show")
        } finally {
            server.stop()
        }
    }

    @Test
    fun `doctor is refused honestly and unknown paths and methods are envelopes`() {
        val server = httpServer()
        server.start()
        try {
            val doctor = get(server, "/show?query=x")
            doctor.statusCode() shouldBe 404 // sanity: the server answers (x is not a known type)

            val refused = get(server, "/doctor")
            refused.body() shouldContain "\"code\":6"
            refused.body() shouldContain "doctor"
            refused.statusCode() shouldBe 500

            val unknown = get(server, "/frobnicate?query=x")
            unknown.statusCode() shouldBe 404
            unknown.body() shouldContain "\"command\":\"unknown\""

            val wrongMethod = post(server, "/members?query=x", "")
            wrongMethod.statusCode() shouldBe 405

            val getBatch = get(server, "/batch")
            getBatch.statusCode() shouldBe 405
        } finally {
            server.stop()
        }
    }

    @Test
    fun `version answers the build version`() {
        val server = httpServer()
        server.start()
        try {
            val response = get(server, "/version")
            response.statusCode() shouldBe 200
            response.body() shouldContain "\"command\":\"version\""
            response.body() shouldContain "\"version\":\"test-0.0.0\""
        } finally {
            server.stop()
        }
    }
}
