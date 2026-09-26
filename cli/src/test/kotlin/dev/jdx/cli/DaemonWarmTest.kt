package dev.jdx.cli

import com.github.ajalt.clikt.core.parse
import com.github.ajalt.clikt.core.subcommands
import dev.jdx.cli.commands.DaemonExit
import dev.jdx.cli.commands.ShowCommand
import dev.jdx.core.rpc.RpcCommand
import dev.jdx.core.rpc.RpcRequest
import dev.jdx.index.service.JdxService
import dev.jdx.index.service.dispatch
import dev.jdx.index.service.dispatchJson
import dev.jdx.index.workspace.InMemoryWorkspaceStore
import dev.jdx.index.workspace.WorkspaceDefinition
import dev.jdx.server.DaemonPaths
import dev.jdx.server.DaemonProbe
import dev.jdx.server.DaemonServer
import dev.jdx.server.jdxServiceHandler
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.property.Arb
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.of
import io.kotest.property.arbitrary.pair
import io.kotest.property.checkAll
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail
import org.junit.jupiter.api.io.TempDir

/**
 * Tier 2: the transparent daemon client over a live socket (T-042).
 *
 * A real [DaemonServer] runs the production `jdxServiceHandler` over the
 * fixture-jar workspace `fx`; [DaemonClient.serveWarmIfReady] must print
 * bytes identical to the in-process call when the daemon answers, and stay
 * silent (degrade to in-process) when it must not forward. The generating
 * family is the socket parity property over hostile requests.
 */
@Tag("tier2")
class DaemonWarmTest {

    @TempDir
    lateinit var tempDir: Path

    private fun fixtureJar(): File {
        val dir = System.getProperty("jdx.fixturesDir")?.let { File(it) }
            ?: fail("jdx.fixturesDir not set (cli build wires it; see cli/build.gradle.kts)")
        return dir.listFiles { file ->
            file.isFile && file.name.startsWith("testfixtures-") && !file.name.endsWith("-sources.jar")
        }?.singleOrNull() ?: fail("expected exactly one binary fixture jar in $dir")
    }

    private fun fixtureStore(): InMemoryWorkspaceStore =
        InMemoryWorkspaceStore().also {
            it.save(WorkspaceDefinition(name = "fx", jars = listOf(fixtureJar().absolutePath), includeJdk = false))
        }

    private fun fixtureRoots(): JdxService.RootsSpec =
        JdxService.RootsSpec(jarSpecs = listOf(fixtureJar().absolutePath), includeJdk = false)

    private fun socket(): Path = DaemonPaths.socketPathIn(tempDir, "fx")

    /** A server running the production dispatch handler over the `fx` workspace. */
    private fun warmServer(store: InMemoryWorkspaceStore = fixtureStore()): DaemonServer {
        val socket = socket()
        lateinit var server: DaemonServer
        val handler = jdxServiceHandler(
            workspace = "fx",
            status = { server.snapshot() },
            appVersion = "test-0.0.0",
            store = store,
        )
        server = DaemonServer(
            workspace = "fx",
            socketPath = socket,
            pidPath = DaemonPaths.pidPath(socket),
            idleTimeout = null,
            appVersion = "test-0.0.0",
            handler = handler,
        )
        return server
    }

    private fun warmRequest(): RpcRequest =
        DaemonClient.membersRequest(ref = "dev.jdx.fixtures.Generics", kind = "method", limit = 5)

    /** Runs [block] with [DaemonClient]-level capture; returns printed lines. */
    private fun servedLines(block: (printer: (String) -> Unit) -> Boolean): Pair<Boolean, List<String>> {
        val printed = mutableListOf<String>()
        val served = block { printed.add(it) }
        return served to printed
    }

    @Test
    fun `every read command served warm is byte-identical to in-process`() {
        val requests = listOf(
            DaemonClient.showRequest("dev.jdx.fixtures.Generics"),
            DaemonClient.membersRequest("dev.jdx.fixtures.Generics", kind = "method", limit = 5),
            DaemonClient.outlineRequest("dev.jdx.fixtures.Generics"),
            DaemonClient.bodyRequest("dev.jdx.fixtures.Generics#identity(java.lang.Object)"),
            DaemonClient.sourceRequest("dev.jdx.fixtures.Generics", maxLines = 20),
            DaemonClient.signatureRequest("dev.jdx.fixtures.Generics#identity"),
            DaemonClient.docRequest("dev.jdx.fixtures.Generics"),
            DaemonClient.searchRequest("*Generic*"),
            DaemonClient.resolveRequest("Generics"),
            DaemonClient.lsRequest("dev.jdx.fixtures"),
            DaemonClient.treeRequest(null),
            DaemonClient.usagesRequest("dev.jdx.fixtures.SealedHierarchy"),
            DaemonClient.hierarchyRequest("dev.jdx.fixtures.SealedHierarchy"),
            DaemonClient.implementorsRequest("dev.jdx.fixtures.SealedHierarchy"),
            DaemonClient.callersRequest("dev.jdx.fixtures.Generics#identity"),
            DaemonClient.callsRequest("dev.jdx.fixtures.Generics#identity(java.lang.Object)"),
            DaemonClient.samplesRequest("dev.jdx.fixtures.Generics#identity"),
        )
        val server = warmServer()
        server.start()
        try {
            val roots = fixtureRoots()
            for (request in requests) {
                // Some fixture queries honestly exit non-zero (no callers, no
                // samples); the warm path must carry that exit code, not just
                // the bytes.
                val expected = JdxService.dispatch(request, roots)
                val printed = mutableListOf<String>()
                var code = 0
                try {
                    val served = DaemonClient.serveWarmIfReady(
                        request = request,
                        flagWorkspace = "fx",
                        roots = WarmRoots(),
                        noDaemon = false,
                        json = true,
                        terminate = { throw DaemonExit(it) },
                        store = InMemoryWorkspaceStore(),
                        getenv = { null },
                        runtimeDir = tempDir,
                        printer = { printed.add(it) },
                    )
                    assert(served) { "warm failed for ${request.command} ${request.query} ${request.params}" }
                } catch (e: DaemonExit) {
                    code = e.code
                }
                code shouldBe expected.exitCode
                printed.size shouldBe 1
                printed.single() shouldBe expected.toJson(request.command.wire)
            }
        } finally {
            server.stop()
        }
    }

    @Test
    fun `an unknown symbol is served warm with its exit code`() {
        val server = warmServer()
        server.start()
        try {
            var code = -1
            val printed = mutableListOf<String>()
            try {
                DaemonClient.serveWarmIfReady(
                    request = DaemonClient.showRequest("no.such.Type"),
                    flagWorkspace = "fx",
                    roots = WarmRoots(),
                    noDaemon = false,
                    json = true,
                    terminate = { throw DaemonExit(it) },
                    store = InMemoryWorkspaceStore(),
                    getenv = { null },
                    runtimeDir = tempDir,
                    printer = { printed.add(it) },
                )
                fail("warm exit 1 must terminate the caller")
            } catch (e: DaemonExit) {
                code = e.code
            }
            code shouldBe 1
            printed.size shouldBe 1
            printed.single() shouldContain "\"ok\":false"
            printed.single() shouldBe JdxService.dispatch(
                DaemonClient.showRequest("no.such.Type"), fixtureRoots(),
            ).toJson("show")
        } finally {
            server.stop()
        }
    }

    @Test
    fun `a stopped daemon degrades to in-process without printing`() {
        val (served, printed) = servedLines { printer ->
            DaemonClient.serveWarmIfReady(
                request = warmRequest(),
                flagWorkspace = "fx",
                roots = WarmRoots(),
                noDaemon = false,
                json = true,
                terminate = { throw DaemonExit(it) },
                store = InMemoryWorkspaceStore(),
                getenv = { null },
                runtimeDir = tempDir,
                printer = printer,
            )
        }
        served shouldBe false
        printed shouldBe emptyList()
    }

    @Test
    fun `no-daemon bypasses a live daemon`() {
        val server = warmServer()
        server.start()
        try {
            var probed = false
            val served = DaemonClient.serveWarmIfReady(
                request = warmRequest(),
                flagWorkspace = "fx",
                roots = WarmRoots(),
                noDaemon = true,
                json = true,
                terminate = { throw DaemonExit(it) },
                store = InMemoryWorkspaceStore(),
                getenv = { null },
                runtimeDir = tempDir,
                roundTrip = { socket, request -> probed = true; DaemonProbe.roundTrip(socket, request) },
                printer = { fail("warm must not print under --no-daemon") },
            )
            served shouldBe false
            probed shouldBe false
        } finally {
            server.stop()
        }
    }

    @Test
    fun `text is served warm with the plain rendering, explicit roots stay cold`() {
        val server = warmServer()
        server.start()
        try {
            // Warm text prints the daemon's plain rendering: byte-identical to
            // the cold plain text (the daemon has no TTY, so no ANSI on either
            // side when piped).
            val request = warmRequest()
            val expected = JdxService.dispatch(request, fixtureRoots())
            val printed = mutableListOf<String>()
            var code = 0
            try {
                val served = DaemonClient.serveWarmIfReady(
                    request = request, flagWorkspace = "fx", roots = WarmRoots(),
                    noDaemon = false, json = false, terminate = { throw DaemonExit(it) },
                    store = InMemoryWorkspaceStore(), getenv = { null }, runtimeDir = tempDir,
                    printer = { printed.add(it) },
                )
                assert(served) { "warm text failed for ${request.command} ${request.query}" }
            } catch (e: DaemonExit) {
                code = e.code
            }
            code shouldBe expected.exitCode
            printed.size shouldBe 1
            printed.single() shouldBe expected.renderText(false)

            var probed = false
            val explicit = DaemonClient.serveWarmIfReady(
                request = request, flagWorkspace = "fx", roots = WarmRoots(jars = listOf("extra.jar")),
                noDaemon = false, json = true, terminate = { throw DaemonExit(it) },
                store = InMemoryWorkspaceStore(), getenv = { null }, runtimeDir = tempDir,
                roundTrip = { socket, req -> probed = true; DaemonProbe.roundTrip(socket, req) },
                printer = { fail("explicit roots must stay cold") },
            )
            explicit shouldBe false
            probed shouldBe false
        } finally {
            server.stop()
        }
    }

    @Test
    fun `every read command served warm-text is byte-identical to cold plain text`() {
        val requests = listOf(
            DaemonClient.showRequest("dev.jdx.fixtures.Generics"),
            DaemonClient.membersRequest("dev.jdx.fixtures.Generics", kind = "method", limit = 5),
            DaemonClient.bodyRequest("dev.jdx.fixtures.Generics#identity(java.lang.Object)"),
            DaemonClient.showRequest("no.such.Type"),
        )
        val server = warmServer()
        server.start()
        try {
            val roots = fixtureRoots()
            for (request in requests) {
                val expected = JdxService.dispatch(request, roots)
                val printed = mutableListOf<String>()
                var code = 0
                try {
                    val served = DaemonClient.serveWarmIfReady(
                        request = request,
                        flagWorkspace = "fx",
                        roots = WarmRoots(),
                        noDaemon = false,
                        json = false,
                        terminate = { throw DaemonExit(it) },
                        store = InMemoryWorkspaceStore(),
                        getenv = { null },
                        runtimeDir = tempDir,
                        printer = { printed.add(it) },
                    )
                    assert(served) { "warm text failed for ${request.command} ${request.query}" }
                } catch (e: DaemonExit) {
                    code = e.code
                }
                code shouldBe expected.exitCode
                printed.size shouldBe 1
                printed.single() shouldBe expected.renderText(false)
            }
            // Brief + cap ride the text path: the daemon shapes the text alone.
            val briefExpected = JdxService.dispatch(warmRequest(), roots)
            val briefPrinted = mutableListOf<String>()
            val briefServed = DaemonClient.serveWarmIfReady(
                request = warmRequest(), flagWorkspace = "fx", roots = WarmRoots(),
                noDaemon = false, json = false, terminate = { throw DaemonExit(it) },
                store = InMemoryWorkspaceStore(), getenv = { null }, runtimeDir = tempDir,
                printer = { briefPrinted.add(it) }, brief = true, warmMaxLines = 2,
            )
            briefServed shouldBe true
            val briefBase = briefExpected.renderBriefText(false)
            briefPrinted.single() shouldBe dev.jdx.core.render.TokenBudget.capLines(briefBase, 2)
        } finally {
            server.stop()
        }
    }

    @Test
    fun `show honors a warm daemon and --no-daemon through the command`() {
        val canned = "{\"jdx\":1,\"ok\":true,\"command\":\"show\",\"query\":\"com.example.Foo\"," +
            "\"result\":{},\"warnings\":[],\"provenance\":[]}"
        var queries = 0
        var probes = 0
        fun command(store: InMemoryWorkspaceStore): ShowCommand = ShowCommand(
            query = { _, _ -> queries++; JdxService.show("no.such.Type", fixtureRoots()) },
            terminate = { throw DaemonExit(it) },
            store = store,
            getenv = { null },
            discover = { _, _, _, _ -> null },
            daemonRuntimeDir = tempDir,
            daemonRoundTrip = { _, _ -> probes++; canned },
        )
        val warmOut = capturedStdout {
            command(InMemoryWorkspaceStore()).parse(listOf("com.example.Foo", "--json", "-w", "fx"))
        }
        warmOut.trim() shouldBe canned
        queries shouldBe 0
        probes shouldBe 1

        // --no-daemon bypasses the probe and runs the stub query in-process
        // (fixtureStore resolves -w fx, so the cold path reaches the query;
        // the stub answers not-found, exit 1).
        queries = 0
        probes = 0
        var code = -1
        capturedStdout {
            try {
                command(fixtureStore()).parse(listOf("com.example.Foo", "--json", "-w", "fx", "--no-daemon"))
            } catch (e: DaemonExit) {
                code = e.code
            }
        }
        queries shouldBe 1
        probes shouldBe 0
        code shouldBe 1
    }

    @Test
    fun `the root --no-daemon flag forces every query in-process`() {
        var probes = 0
        val show = ShowCommand(
            query = { _, _ -> JdxService.show("no.such.Type", fixtureRoots()) },
            terminate = { throw DaemonExit(it) },
            store = InMemoryWorkspaceStore(),
            getenv = { null },
            discover = { _, _, _, _ -> null },
            daemonRuntimeDir = tempDir,
            daemonRoundTrip = { _, _ -> probes++; null },
        )
        capturedStdout {
            try {
                JdxCli().subcommands(show).parse(listOf("--no-daemon", "show", "Foo", "--json"))
            } catch (_: DaemonExit) {
                // Cold path outcome only — the assertion is the probe count.
            }
        }
        probes shouldBe 0
    }

    // -- generating family: socket parity over hostile read requests --

    private val hostileChars: List<Char> = listOf(
        '"', '\\', '/', '\n', '\r', '\t', '\b', '\u0000', 'a', 'Z', '0', '9',
        '{', '}', '[', ']', ':', ',', '-', 'e', ' ',
    )

    private fun arbHostileString(range: IntRange): Arb<String> =
        Arb.list(Arb.of(hostileChars), range).map { chars -> chars.joinToString("") }

    private fun arbParams(): Arb<Map<String, String>> =
        Arb.list(Arb.pair(arbHostileString(0..8), arbHostileString(0..8)), 0..6).map { it.toMap() }

    private val readCommands: List<RpcCommand> = RpcCommand.entries.filter {
        it != RpcCommand.VERSION && it != RpcCommand.DOCTOR && it != RpcCommand.HEALTH
    }

    @Test
    fun `warm answers stay byte-identical to in-process over generated requests`(): Unit = runBlocking {
        readCommands.size shouldBe 17
        val server = warmServer()
        server.start()
        try {
            val roots = fixtureRoots()
            checkAll(
                200,
                Arb.bind(Arb.of(readCommands), arbHostileString(0..24), arbParams()) { command, query, params ->
                    RpcRequest(command, query, params)
                },
            ) { request ->
                // Same roots, same serialisation on both sides: any request —
                // hostile or not — answers identically warm and cold. The probe
                // goes through the production handler, so warm-text params are
                // honoured on both sides via dispatchJson.
                val warm = DaemonProbe.roundTrip(socket(), request)
                warm shouldNotBe null
                warm shouldBe JdxService.dispatchJson(request, roots)
            }
        } finally {
            server.stop()
        }
    }

    /** Runs [block] with stdout captured; returns what it printed. */
    private fun capturedStdout(block: () -> Unit): String {
        val sink = ByteArrayOutputStream()
        val previous = System.out
        System.setOut(PrintStream(sink))
        try {
            block()
        } finally {
            System.setOut(previous)
        }
        return sink.toString(Charsets.UTF_8)
    }
}
