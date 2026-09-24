package dev.jdx.cli

import dev.jdx.cli.commands.DaemonExit
import dev.jdx.core.rpc.RpcCommand
import dev.jdx.core.rpc.RpcRequest
import dev.jdx.index.workspace.InMemoryWorkspaceStore
import dev.jdx.index.workspace.WorkspaceDefinition
import dev.jdx.index.workspace.WorkspaceStore
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/**
 * Tier 1: the transparent daemon client without sockets (T-042).
 *
 * [DaemonClient] owns transport decisions (when a query may leave the
 * process), envelope exit-code parsing, and the flag→param builders. All of
 * that is pure except the one injected [DaemonRoundTrip], so every guard here
 * runs with stub transports — the live socket parity lives in tier 2
 * (`DaemonWarmTest`).
 */
class DaemonClientTest {

    private fun store(active: String? = null): InMemoryWorkspaceStore =
        InMemoryWorkspaceStore().also { it.setActive(active) }

    // -- workspace name resolution: flag > env > active, blanks absent --

    @Test
    fun `the flag beats env and active`() {
        val store = store(active = "active-ws")
        DaemonClient.workspaceNameForDaemon("flag-ws", { "env-ws" }, store) shouldBe "flag-ws"
    }

    @Test
    fun `env beats the stored active workspace`() {
        val store = store(active = "active-ws")
        DaemonClient.workspaceNameForDaemon(null, { "env-ws" }, store) shouldBe "env-ws"
    }

    @Test
    fun `the stored active workspace is the fallback`() {
        val store = store(active = "active-ws")
        DaemonClient.workspaceNameForDaemon(null, { null }, store) shouldBe "active-ws"
    }

    @Test
    fun `blanks read as absent at every level`() {
        DaemonClient.workspaceNameForDaemon("  ", { " " }, store(active = "real")) shouldBe "real"
        DaemonClient.workspaceNameForDaemon(null, { null }, store(active = null)) shouldBe null
        DaemonClient.workspaceNameForDaemon(null, { "   " }, store(active = null)) shouldBe null
    }

    @Test
    fun `a hostile environment or store reads as no named workspace`() {
        val throwingStore = object : WorkspaceStore {
            override fun listNames(): List<String> = throw SecurityException("denied")
            override fun load(name: String): WorkspaceDefinition? = throw SecurityException("denied")
            override fun save(definition: WorkspaceDefinition) = throw SecurityException("denied")
            override fun delete(name: String): Boolean = throw SecurityException("denied")
            override fun activeName(): String? = throw SecurityException("denied")
            override fun setActive(name: String?) = throw SecurityException("denied")
        }
        DaemonClient.workspaceNameForDaemon(null, { throw SecurityException("denied") }, throwingStore) shouldBe null
    }

    // -- shouldAttempt: every clause is a correctness guard --

    @Test
    fun `a clean json query to a named workspace may go warm`() {
        DaemonClient.shouldAttempt(
            noDaemon = false,
            json = true,
            roots = WarmRoots(),
            workspaceName = "fx",
            runtimeDir = Path.of("/run/user/1000"),
        ) shouldBe true
    }

    @Test
    fun `a clean text query to a named workspace may go warm`() {
        DaemonClient.shouldAttempt(
            noDaemon = false,
            json = false,
            roots = WarmRoots(),
            workspaceName = "fx",
            runtimeDir = Path.of("/run/user/1000"),
        ) shouldBe true
    }

    @Test
    fun `no-daemon stays in-process for json and text`() {
        val base = WarmRoots()
        val dir = Path.of("/run/user/1000")
        DaemonClient.shouldAttempt(true, true, base, "fx", dir) shouldBe false
        DaemonClient.shouldAttempt(true, false, base, "fx", dir) shouldBe false
    }

    @Test
    fun `any explicit root override stays in-process`() {
        val dir = Path.of("/run/user/1000")
        val variants = listOf(
            WarmRoots(jars = listOf("a.jar")),
            WarmRoots(coords = listOf("g:a:1")),
            WarmRoots(repos = listOf("https://example.com/m2")),
            WarmRoots(fetch = true),
            WarmRoots(srcs = listOf("src")),
            WarmRoots(noJdk = true),
        )
        for (roots in variants) {
            DaemonClient.shouldAttempt(false, true, roots, "fx", dir) shouldBe false
        }
    }

    @Test
    fun `no named workspace or runtime dir stays in-process`() {
        val dir = Path.of("/run/user/1000")
        DaemonClient.shouldAttempt(false, true, WarmRoots(), null, dir) shouldBe false
        DaemonClient.shouldAttempt(false, true, WarmRoots(), "  ", dir) shouldBe false
        DaemonClient.shouldAttempt(false, true, WarmRoots(), "fx", null) shouldBe false
    }

    // -- envelope exit codes --

    @Test
    fun `ok true reads as exit 0`() {
        val line = "{\"jdx\":1,\"ok\":true,\"command\":\"show\",\"query\":\"Foo\"," +
            "\"result\":{},\"warnings\":[],\"provenance\":[]}"
        DaemonClient.parseExitCode(line, "show") shouldBe 0
    }

    @Test
    fun `ok false reads the error code`() {
        val line = "{\"jdx\":1,\"ok\":false,\"command\":\"members\",\"query\":\"Foo\"," +
            "\"error\":{\"code\":2,\"message\":\"ambiguous\"},\"candidates\":[],\"warnings\":[],\"provenance\":[]}"
        DaemonClient.parseExitCode(line, "members") shouldBe 2
    }

    @Test
    fun `a foreign envelope is refused, not obeyed`() {
        val okShow = "{\"jdx\":1,\"ok\":true,\"command\":\"show\",\"query\":\"Foo\"," +
            "\"result\":{},\"warnings\":[],\"provenance\":[]}"
        // A different command is refused; the query echo is unchecked by
        // design (the service normalises empty ls/tree queries to "*").
        DaemonClient.parseExitCode(okShow, "members") shouldBe null
        DaemonClient.parseExitCode(okShow, "show") shouldBe 0
        DaemonClient.parseExitCode("{garbage", "show") shouldBe null
        DaemonClient.parseExitCode("", "show") shouldBe null
        val wrongVersion = okShow.replace("\"jdx\":1", "\"jdx\":999")
        DaemonClient.parseExitCode(wrongVersion, "show") shouldBe null
        val noCode = "{\"jdx\":1,\"ok\":false,\"command\":\"show\",\"query\":\"Foo\"," +
            "\"candidates\":[],\"warnings\":[],\"provenance\":[]}"
        DaemonClient.parseExitCode(noCode, "show") shouldBe null
    }

    @Test
    fun `builders carry the wire command and query`() {
        DaemonClient.showRequest("com.example.Foo").command shouldBe RpcCommand.SHOW
        DaemonClient.membersRequest("com.example.Foo", declared = true).command shouldBe RpcCommand.MEMBERS
        DaemonClient.membersRequest("com.example.Foo", declared = true).params["declared"] shouldBe "true"
        DaemonClient.outlineRequest("com.example.Foo").command shouldBe RpcCommand.OUTLINE
        DaemonClient.outlineRequest("com.example.Foo").params.containsKey("declared") shouldBe false
        DaemonClient.docRequest("Foo", noInherited = true).params["no-inherited"] shouldBe "true"
        DaemonClient.searchRequest("*Foo*", inArtifact = "gson").params["in"] shouldBe "gson"
        DaemonClient.lsRequest(null).query shouldBe ""
        DaemonClient.treeRequest(null).query shouldBe ""
        DaemonClient.implementorsRequest("Foo").command shouldBe RpcCommand.IMPLEMENTORS
        DaemonClient.callsRequest("Foo#bar", externalOnly = true).params["externalOnly"] shouldBe "true"
        DaemonClient.samplesRequest("Foo#bar").params["limit"] shouldBe "3"
    }

    @Test
    fun `builders are deterministic and decode cleanly`() {
        val first = DaemonClient.membersRequest("Foo", kind = "Method", limit = 5).encode()
        val second = DaemonClient.membersRequest("Foo", kind = "method", limit = 5).encode()
        first shouldBe second
        RpcRequest.decode(first) shouldNotBe null
        RpcRequest.decode(first) shouldBe RpcRequest.decode(second)
    }

    @Test
    fun `serveWarmIfReady degrades without touching the transport`() {
        var probed = false
        val stub: DaemonRoundTrip = { _, _ -> probed = true; null }
        var printed = false
        val served = DaemonClient.serveWarmIfReady(
            request = DaemonClient.showRequest("Foo"),
            flagWorkspace = "fx",
            roots = WarmRoots(jars = listOf("explicit.jar")),
            noDaemon = false,
            json = true,
            terminate = { throw DaemonExit(it) },
            store = store(),
            getenv = { null },
            runtimeDir = Path.of("/run/user/1000"),
            roundTrip = stub,
            printer = { printed = true },
        )
        served shouldBe false
        probed shouldBe false
        printed shouldBe false
    }

    // -- warm text (T-086): the server-rendered "text" field --

    private fun textEnvelope(command: String, text: String, ok: Boolean = true, code: Int = 0): String =
        if (ok) {
            "{\"jdx\":1,\"ok\":true,\"command\":\"$command\",\"query\":\"Foo\"," +
                "\"result\":{},\"text\":" + textEnvelopeQuote(text) + ",\"warnings\":[],\"provenance\":[]}"
        } else {
            "{\"jdx\":1,\"ok\":false,\"command\":\"$command\",\"query\":\"Foo\"," +
                "\"error\":{\"code\":$code,\"message\":\"nope\"},\"candidates\":[]," +
                "\"text\":" + textEnvelopeQuote(text) + ",\"warnings\":[],\"provenance\":[]}"
        }

    private fun textEnvelopeQuote(text: String): String = buildString {
        append('"')
        for (char in text) {
            when (char) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (char < ' ') append("\\u%04x".format(char.code)) else append(char)
            }
        }
        append('"')
    }

    @Test
    fun `warm text prints the server rendering and carries the exit code`() {
        val line = textEnvelope("show", "line one\nline two")
        DaemonClient.parseWarmText(line) shouldBe "line one\nline two"
        val printed = mutableListOf<String>()
        val served = DaemonClient.serveWarmIfReady(
            request = DaemonClient.showRequest("Foo"),
            flagWorkspace = "fx",
            roots = WarmRoots(),
            noDaemon = false,
            json = false,
            terminate = { throw DaemonExit(it) },
            store = store(active = "fx"),
            getenv = { null },
            runtimeDir = Path.of("/run/user/1000"),
            roundTrip = { _, _ -> line },
            printer = { printed.add(it) },
        )
        served shouldBe true
        printed shouldBe listOf("line one\nline two")
    }

    @Test
    fun `warm text carries a non-zero exit code to terminate`() {
        val line = textEnvelope("show", "not found", ok = false, code = 1)
        var code = -1
        val printed = mutableListOf<String>()
        try {
            DaemonClient.serveWarmIfReady(
                request = DaemonClient.showRequest("Foo"),
                flagWorkspace = "fx",
                roots = WarmRoots(),
                noDaemon = false,
                json = false,
                terminate = { throw DaemonExit(it) },
                store = store(active = "fx"),
                getenv = { null },
                runtimeDir = Path.of("/run/user/1000"),
                roundTrip = { _, _ -> line },
                printer = { printed.add(it) },
            )
        } catch (e: DaemonExit) {
            code = e.code
        }
        code shouldBe 1
        printed shouldBe listOf("not found")
    }

    @Test
    fun `warm text without a text field degrades to in-process`() {
        val plain = "{\"jdx\":1,\"ok\":true,\"command\":\"show\",\"query\":\"Foo\"," +
            "\"result\":{},\"warnings\":[],\"provenance\":[]}"
        DaemonClient.parseWarmText(plain) shouldBe null
        DaemonClient.parseWarmText("{garbage") shouldBe null
        DaemonClient.parseWarmText("{\"jdx\":1,\"ok\":true,\"command\":\"show\",\"text\":42}") shouldBe null
        var probed = false
        val printed = mutableListOf<String>()
        val served = DaemonClient.serveWarmIfReady(
            request = DaemonClient.showRequest("Foo"),
            flagWorkspace = "fx",
            roots = WarmRoots(),
            noDaemon = false,
            json = false,
            terminate = { throw DaemonExit(it) },
            store = store(active = "fx"),
            getenv = { null },
            runtimeDir = Path.of("/run/user/1000"),
            roundTrip = { _, _ -> probed = true; plain },
            printer = { printed.add(it) },
        )
        served shouldBe false
        probed shouldBe true
        printed shouldBe emptyList()
    }

    @Test
    fun `warm text forwards brief and max-lines as wire params`() {
        val seen = mutableListOf<RpcRequest>()
        val line = textEnvelope("members", "brief rows")
        DaemonClient.serveWarmIfReady(
            request = DaemonClient.membersRequest("Foo"),
            flagWorkspace = "fx",
            roots = WarmRoots(),
            noDaemon = false,
            json = false,
            terminate = { throw DaemonExit(it) },
            store = store(active = "fx"),
            getenv = { null },
            runtimeDir = Path.of("/run/user/1000"),
            roundTrip = { _, request -> seen.add(request); line },
            printer = { },
            brief = true,
            warmMaxLines = 3,
        )
        seen.size shouldBe 1
        seen.single().params["warmText"] shouldBe "true"
        seen.single().params["warmBrief"] shouldBe "true"
        seen.single().params["warmMaxLines"] shouldBe "3"
    }

    // -- generating family: hostile input never throws, builders stay total --

    @Test
    fun `exit-code parsing never throws on hostile lines`(): Unit = runBlocking {
        checkAll(1_000, Arb.string()) { text ->
            DaemonClient.parseExitCode(text, "show")
        }
    }

    @Test
    fun `warm-text parsing never throws on hostile lines`(): Unit = runBlocking {
        checkAll(1_000, Arb.string()) { text ->
            DaemonClient.parseWarmText(text)
        }
    }

    @Test
    fun `workspace resolution never throws on hostile names`(): Unit = runBlocking {
        checkAll(500, Arb.string(), Arb.string()) { flag, env ->
            DaemonClient.workspaceNameForDaemon(flag, { env }, store()) // a name or null — never raises
        }
    }

    @Test
    fun `builders stay total over generated flags`(): Unit = runBlocking {
        checkAll(500, Arb.string(0..24), Arb.int(-5..200)) { ref, limit ->
            val request = DaemonClient.membersRequest(ref = ref, limit = limit, kind = "method")
            RpcRequest.decode(request.encode()) shouldNotBe null
            DaemonClient.parseExitCode(request.encode(), "members") shouldBe null // a request, not an envelope
        }
    }
}
