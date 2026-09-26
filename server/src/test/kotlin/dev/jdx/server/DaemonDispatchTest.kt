package dev.jdx.server

import dev.jdx.core.rpc.RPC_VERSION
import dev.jdx.core.rpc.RpcCommand
import dev.jdx.core.rpc.RpcRequest
import dev.jdx.index.service.JdxService
import dev.jdx.index.service.dispatch
import dev.jdx.index.workspace.InMemoryWorkspaceStore
import dev.jdx.index.workspace.WorkspaceDefinition
import dev.jdx.testsupport.paths.shortSocketDir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.io.File
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * Tier 2: the daemon answers every read query over the live socket (T-082) —
 * the `jdxServiceHandler` wired the way `daemon run` wires it.
 *
 * The acceptance is socket parity: each [RpcCommand] round-tripped through the
 * unix socket returns the same bytes as the in-process
 * [JdxService.dispatch] call, so T-046 parity stays structural. `health` and
 * `version` stay on the transport internals; a missing workspace reads as an
 * exit-4 envelope, never a drop.
 */
@Tag("tier2")
class DaemonDispatchTest {

    @TempDir
    lateinit var tempDir: Path

    // Short socket scope: sun_path caps binds (104 macOS) while @TempDir
    // reads /var/folders/... there. Lazy — socket() must stay stable in-test.
    private val socketScope: Path by lazy { shortSocketDir("dispatch") }

    private fun fixtureJar(): File {
        val dir = System.getProperty("jdx.fixturesDir")?.let { File(it) }
            ?: fail("jdx.fixturesDir not set (server build wires it; see server/build.gradle.kts)")
        return dir.listFiles { file ->
            file.isFile && file.name.startsWith("testfixtures-") && !file.name.endsWith("-sources.jar")
        }?.singleOrNull() ?: fail("expected exactly one binary fixture jar in $dir")
    }

    private fun fixtureRoots(): JdxService.RootsSpec =
        JdxService.RootsSpec(jarSpecs = listOf(fixtureJar().absolutePath), includeJdk = false)

    /** A server running the production dispatch handler over the `fx` workspace. */
    private fun dispatchServer(store: InMemoryWorkspaceStore = fixtureStore()): DaemonServer {
        val socket = DaemonPaths.socketPath(socketScope, "fx")
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

    private fun fixtureStore(): InMemoryWorkspaceStore =
        InMemoryWorkspaceStore().also {
            it.save(WorkspaceDefinition(name = "fx", jars = listOf(fixtureJar().absolutePath), includeJdk = false))
        }

    private fun socket(): Path = DaemonPaths.socketPath(socketScope, "fx")

    private fun req(command: RpcCommand, query: String = "", vararg params: Pair<String, String>): RpcRequest =
        RpcRequest(command, query, params.toMap())

    @Test
    fun `every read command round-trips the same bytes as the in-process call`() {
        val requests = listOf(
            req(RpcCommand.SHOW, "dev.jdx.fixtures.Generics"),
            req(RpcCommand.MEMBERS, "dev.jdx.fixtures.Generics", "kind" to "method", "limit" to "5"),
            req(RpcCommand.OUTLINE, "dev.jdx.fixtures.Generics"),
            req(RpcCommand.BODY, "dev.jdx.fixtures.Generics#identity(java.lang.Object)"),
            req(RpcCommand.SOURCE, "dev.jdx.fixtures.Generics", "maxLines" to "20"),
            req(RpcCommand.SIGNATURE, "dev.jdx.fixtures.Generics#identity"),
            req(RpcCommand.DOC, "dev.jdx.fixtures.Generics"),
            req(RpcCommand.SEARCH, "*Generic*"),
            req(RpcCommand.RESOLVE, "Generics"),
            req(RpcCommand.LS, "dev.jdx.fixtures"),
            req(RpcCommand.TREE, ""),
            req(RpcCommand.USAGES, "dev.jdx.fixtures.SealedHierarchy"),
            req(RpcCommand.HIERARCHY, "dev.jdx.fixtures.SealedHierarchy"),
            req(RpcCommand.IMPLEMENTORS, "dev.jdx.fixtures.SealedHierarchy"),
            req(RpcCommand.CALLERS, "dev.jdx.fixtures.Generics#identity"),
            req(RpcCommand.CALLS, "dev.jdx.fixtures.Generics#identity(java.lang.Object)"),
            req(RpcCommand.SAMPLES, "dev.jdx.fixtures.Generics#identity"),
        )
        val server = dispatchServer()
        server.start()
        try {
            val roots = fixtureRoots()
            for (request in requests) {
                val line = DaemonProbe.roundTrip(socket(), request)
                line shouldNotBe null
                line!! shouldNotContain "\n"
                line shouldBe JdxService.dispatch(request, roots).toJson(request.command.wire)
            }
        } finally {
            server.stop()
        }
    }

    @Test
    fun `warm answers carry the query command and exit-0 successes`() {
        val server = dispatchServer()
        server.start()
        try {
            val line = DaemonProbe.roundTrip(socket(), req(RpcCommand.SHOW, "dev.jdx.fixtures.Generics"))
            line shouldNotBe null
            line!! shouldContain "\"command\":\"show\""
            line shouldContain "\"ok\":true"
            val members = DaemonProbe.roundTrip(
                socket(), req(RpcCommand.MEMBERS, "dev.jdx.fixtures.Generics", "limit" to "3"),
            )
            members shouldNotBe null
            members!! shouldContain "\"command\":\"members\""
            members shouldContain "\"ok\":true"
        } finally {
            server.stop()
        }
    }

    @Test
    fun `a missing workspace reads as exit 4, not a drop`() {
        val server = dispatchServer(InMemoryWorkspaceStore())
        server.start()
        try {
            val line = DaemonProbe.roundTrip(socket(), req(RpcCommand.SHOW, "dev.jdx.fixtures.Generics"))
            line shouldNotBe null
            line!! shouldContain "\"ok\":false"
            line shouldContain "\"code\":4"
            line shouldContain "no such workspace 'fx'"
            // The daemon survives: health still answers on the next connection.
            DaemonProbe.health(socket()) shouldNotBe null
        } finally {
            server.stop()
        }
    }

    @Test
    fun `doctor is refused honestly, health and version stay internal`() {
        val server = dispatchServer()
        server.start()
        try {
            val doctor = DaemonProbe.roundTrip(socket(), req(RpcCommand.DOCTOR))
            doctor shouldNotBe null
            doctor!! shouldContain "\"ok\":false"
            doctor shouldContain "\"code\":6"
            doctor shouldContain "doctor"
            val version = DaemonProbe.roundTrip(socket(), req(RpcCommand.VERSION))
            version shouldNotBe null
            version!! shouldContain "\"version\":\"test-0.0.0\""
            val snapshot = DaemonProbe.health(socket())
            snapshot shouldNotBe null
            snapshot!!.rpcVersion shouldBe RPC_VERSION
        } finally {
            server.stop()
        }
    }

    @Test
    fun `daemon status counts dispatched queries`() {
        val server = dispatchServer()
        server.start()
        try {
            val before = DaemonProbe.health(socket())!!.queryCount
            DaemonProbe.roundTrip(socket(), req(RpcCommand.SHOW, "dev.jdx.fixtures.Generics"))
            DaemonProbe.roundTrip(socket(), req(RpcCommand.SEARCH, "*Generic*"))
            val after = DaemonProbe.health(socket())!!.queryCount
            after shouldBe before + 3 // two reads plus the second health probe
        } finally {
            server.stop()
        }
    }
}
