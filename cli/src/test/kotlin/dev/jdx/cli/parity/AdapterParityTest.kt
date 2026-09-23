package dev.jdx.cli.parity

import com.github.ajalt.clikt.core.parse
import dev.jdx.cli.DaemonClient
import dev.jdx.cli.commands.MembersCommand
import dev.jdx.cli.commands.ProjectDiscoveryFn
import dev.jdx.cli.commands.ShowCommand
import dev.jdx.core.rpc.RpcCommand
import dev.jdx.core.rpc.RpcRequest
import dev.jdx.index.service.JdxService
import dev.jdx.index.service.dispatch
import dev.jdx.index.workspace.InMemoryWorkspaceStore
import dev.jdx.index.workspace.WorkspaceDefinition
import dev.jdx.mcp.McpSession
import dev.jdx.server.JdxHttpServer
import dev.jdx.testsupport.fixtures.FixtureJars
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.property.Arb
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.of
import io.kotest.property.arbitrary.pair
import io.kotest.property.checkAll
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Tier 2: adapter parity across every serving surface (T-046, closes M6).
 *
 * TESTING.md §9 contract: the same query answered via CLI `--json`, HTTP and
 * MCP returns byte-identical `result` payloads. The proof is structural —
 * every adapter serialises the same `JdxService.dispatch` outcome with
 * `ServiceOutcome.toJson(wire)` (D-056 §1) — so this suite pins the structure
 * instead of goldening 17 commands × 3 adapters: one table row per read
 * command over the fixture jar, one JDK sample, two failure branches, two
 * real CLI `--json` spot checks anchoring the `dispatch`-as-CLI reference,
 * and a hostile-input property keeping the proof honest beyond hand-picked
 * queries.
 *
 * Hermetic like the read-command suites (T-070): the fixture corpus alone,
 * an isolated store, no environment, no project discovery.
 */
@Tag("tier2")
class AdapterParityTest {

    private val client: HttpClient = HttpClient.newHttpClient()

    private val noDiscovery: ProjectDiscoveryFn = { _, _, _, _ -> null }
    private val noEnv: (String) -> String? = { null }

    private class TestExit(val code: Int) : RuntimeException()

    private fun fixtureStore(): InMemoryWorkspaceStore =
        InMemoryWorkspaceStore().also {
            val jar = FixtureJars.binaryJar().absolutePath
            it.save(WorkspaceDefinition(name = "fx", jars = listOf(jar), includeJdk = false))
            it.save(WorkspaceDefinition(name = "jdk", jars = emptyList(), includeJdk = true))
        }

    private fun fixtureRoots(): JdxService.RootsSpec =
        JdxService.RootsSpec(jarSpecs = listOf(FixtureJars.binaryJar().absolutePath), includeJdk = false)

    private fun jdkRoots(): JdxService.RootsSpec =
        JdxService.RootsSpec(jarSpecs = emptyList(), includeJdk = true)

    private fun mcpSession(): McpSession =
        McpSession(workspace = "fx", appVersion = "test", store = fixtureStore())

    private fun httpServer(): JdxHttpServer =
        JdxHttpServer(workspace = "fx", bind = "127.0.0.1", port = 0, appVersion = "test", store = fixtureStore())

    private fun enc(text: String): String = URLEncoder.encode(text, StandardCharsets.UTF_8)

    /** One read query plus the roots and workspace every adapter must answer from. */
    private data class ParityCase(
        val request: RpcRequest,
        val roots: JdxService.RootsSpec,
        val workspace: String = "fx",
    )

    /** Every read command once (built with the CLI's own flag→param builders), over the fixture jar. */
    private fun readCases(): List<ParityCase> {
        val roots = fixtureRoots()
        return listOf(
            ParityCase(DaemonClient.showRequest("dev.jdx.fixtures.Generics"), roots),
            ParityCase(DaemonClient.membersRequest("dev.jdx.fixtures.Generics", kind = "method", limit = 5), roots),
            ParityCase(DaemonClient.outlineRequest("dev.jdx.fixtures.Generics"), roots),
            ParityCase(DaemonClient.bodyRequest("dev.jdx.fixtures.Generics#identity(java.lang.Object)"), roots),
            ParityCase(DaemonClient.sourceRequest("dev.jdx.fixtures.Generics"), roots),
            ParityCase(DaemonClient.signatureRequest("dev.jdx.fixtures.Generics#identity"), roots),
            ParityCase(DaemonClient.docRequest("dev.jdx.fixtures.Generics"), roots),
            ParityCase(DaemonClient.searchRequest("*Generic*", kind = "class"), roots),
            ParityCase(DaemonClient.resolveRequest("Generics"), roots),
            ParityCase(DaemonClient.lsRequest("dev.jdx.fixtures"), roots),
            ParityCase(DaemonClient.treeRequest(null), roots),
            ParityCase(DaemonClient.usagesRequest("dev.jdx.fixtures.SealedHierarchy"), roots),
            ParityCase(DaemonClient.hierarchyRequest("dev.jdx.fixtures.SealedHierarchy"), roots),
            ParityCase(DaemonClient.implementorsRequest("dev.jdx.fixtures.SealedHierarchy"), roots),
            ParityCase(DaemonClient.callersRequest("dev.jdx.fixtures.Generics#identity"), roots),
            ParityCase(DaemonClient.callsRequest("dev.jdx.fixtures.Generics#identity(java.lang.Object)"), roots),
            ParityCase(DaemonClient.samplesRequest("dev.jdx.fixtures.Generics#identity"), roots),
        )
    }

    private fun mcpText(session: McpSession, case: ParityCase): McpSession.McpResult {
        val args = buildJsonObject {
            put("query", case.request.query)
            for ((key, value) in case.request.params) put(key, value)
            if (case.workspace != "fx") put("workspace", case.workspace)
        }
        return session.callTool("jdx_${case.request.command.wire}", args)
    }

    private fun httpBody(server: JdxHttpServer, case: ParityCase): String {
        val address = server.localAddress()
        val query = buildString {
            append("query=").append(enc(case.request.query))
            for ((key, value) in case.request.params) append('&').append(enc(key)).append('=').append(enc(value))
            if (case.workspace != "fx") append("&workspace=").append(enc(case.workspace))
        }
        val url = "http://${address.hostString}:${address.port}/v1/${case.request.command.wire}?$query"
        val response = client.send(
            HttpRequest.newBuilder(URI(url)).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        return response.body()
    }

    /** The parity assertion: CLI `--json` bytes == MCP bytes == HTTP bytes, one line, this command. */
    private fun checkCase(session: McpSession, server: JdxHttpServer, case: ParityCase) {
        val expected = JdxService.dispatch(case.request, case.roots).toJson(case.request.command.wire)
        expected shouldNotContain "\n"

        val mcp = mcpText(session, case)
        mcp.text shouldBe expected

        val http = httpBody(server, case)
        http shouldBe expected
    }

    @Test
    fun `all seventeen read commands answer byte-identical envelopes on every adapter`() {
        val cases = readCases()
        cases.size shouldBe 17
        val session = mcpSession()
        val server = httpServer()
        server.start()
        try {
            for (case in cases) checkCase(session, server, case)
        } finally {
            server.stop()
        }
    }

    @Test
    fun `a JDK sample answers byte-identical envelopes on every adapter`() {
        val case = ParityCase(
            request = DaemonClient.showRequest("java.util.HashMap"),
            roots = jdkRoots(),
            workspace = "jdk",
        )
        val session = mcpSession()
        val server = httpServer()
        server.start()
        try {
            checkCase(session, server, case)
        } finally {
            server.stop()
        }
    }

    @Test
    fun `failures ride identical envelopes on every adapter`() {
        val roots = fixtureRoots()
        val cases = listOf(
            // Unknown symbol: exit 1 with did-you-mean.
            ParityCase(DaemonClient.showRequest("no.such.Type"), roots),
            // Usage error: exit 3 naming the param.
            ParityCase(
                RpcRequest(RpcCommand.MEMBERS, "dev.jdx.fixtures.Generics", mapOf("kind" to "bogus")),
                roots,
            ),
        )
        val session = mcpSession()
        val server = httpServer()
        server.start()
        try {
            for (case in cases) {
                checkCase(session, server, case)
                mcpText(session, case).isError shouldBe true
            }
        } finally {
            server.stop()
        }
    }

    /** Real `ShowCommand --json` stdout for the parity reference query. */
    private fun runShowJson(args: List<String>): String {
        val original = System.out
        val buffer = ByteArrayOutputStream()
        System.setOut(PrintStream(buffer))
        try {
            ShowCommand(
                terminate = { throw TestExit(it) },
                store = InMemoryWorkspaceStore(),
                getenv = noEnv,
                discover = noDiscovery,
            ).parse(args)
        } catch (e: TestExit) {
            if (e.code !in 1..2) throw e
        } finally {
            System.setOut(original)
        }
        return buffer.toString(Charsets.UTF_8).trimEnd()
    }

    /** Real `MembersCommand --json` stdout for the parity reference query. */
    private fun runMembersJson(args: List<String>): String {
        val original = System.out
        val buffer = ByteArrayOutputStream()
        System.setOut(PrintStream(buffer))
        try {
            MembersCommand(
                terminate = { throw TestExit(it) },
                store = InMemoryWorkspaceStore(),
                getenv = noEnv,
                discover = noDiscovery,
            ).parse(args)
        } catch (e: TestExit) {
            if (e.code !in 1..2) throw e
        } finally {
            System.setOut(original)
        }
        return buffer.toString(Charsets.UTF_8).trimEnd()
    }

    @Test
    fun `the one-shot CLI json for show and members matches the dispatch reference`() {
        val jar = FixtureJars.binaryJar().absolutePath
        val roots = listOf("--jars", jar, "--no-jdk")

        val showRequest = DaemonClient.showRequest("dev.jdx.fixtures.Generics")
        runShowJson(listOf("dev.jdx.fixtures.Generics") + roots + "--json") shouldBe
            JdxService.dispatch(showRequest, fixtureRoots()).toJson("show")

        val membersRequest = DaemonClient.membersRequest("dev.jdx.fixtures.Generics", kind = "method", limit = 5)
        runMembersJson(
            listOf("dev.jdx.fixtures.Generics") + roots +
                listOf("--json", "--kind", "method", "--limit", "5"),
        ) shouldBe JdxService.dispatch(membersRequest, fixtureRoots()).toJson("members")
    }

    // -- generating family: hostile-request parity across MCP and HTTP --

    private val hostileChars: List<Char> = listOf(
        '"', '\\', '/', '\n', '\r', '\t', '\b', '\u0000', 'a', 'Z', '0', '9',
        '{', '}', '[', ']', ':', ',', '-', 'e', ' ',
    )

    private fun arbHostileString(range: IntRange): Arb<String> =
        Arb.list(Arb.of(hostileChars), range).map { chars -> chars.joinToString("") }

    private fun arbParams(): Arb<Map<String, String>> =
        Arb.list(Arb.pair(arbHostileString(0..8), arbHostileString(0..8)), 0..6)
            .map { pairs ->
                // "query" and "workspace" are routing on the MCP/HTTP side
                // (subject and root selection) but inert wire params to the
                // dispatch — generating them would compare different queries.
                pairs.toMap().filterKeys { it != "query" && it != "workspace" }
            }

    private val readCommands: List<RpcCommand> = RpcCommand.entries.filter {
        it != RpcCommand.VERSION && it != RpcCommand.DOCTOR && it != RpcCommand.HEALTH
    }

    @Test
    fun `parity holds over generated hostile requests`(): Unit = runBlocking {
        readCommands.size shouldBe 17
        val session = mcpSession()
        val server = httpServer()
        server.start()
        try {
            val roots = fixtureRoots()
            checkAll(
                100,
                Arb.bind(Arb.of(readCommands), arbHostileString(0..24), arbParams()) { command, query, params ->
                    RpcRequest(command, query, params)
                },
            ) { request ->
                val expected = JdxService.dispatch(request, roots).toJson(request.command.wire)
                expected shouldNotContain "\n"
                val mcp = session.callTool(
                    "jdx_${request.command.wire}",
                    buildJsonObject {
                        put("query", request.query)
                        for ((key, value) in request.params) put(key, value)
                    },
                )
                mcp.text shouldBe expected
                val address = server.localAddress()
                val queryString = buildString {
                    append("query=").append(enc(request.query))
                    for ((key, value) in request.params) {
                        append('&').append(enc(key)).append('=').append(enc(value))
                    }
                }
                val url = "http://${address.hostString}:${address.port}/v1/${request.command.wire}?$queryString"
                val response = client.send(
                    HttpRequest.newBuilder(URI(url)).GET().build(),
                    HttpResponse.BodyHandlers.ofString(),
                )
                response.body() shouldNotBe null
                response.body() shouldBe expected
            }
        } finally {
            server.stop()
        }
    }
}
