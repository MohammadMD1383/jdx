package dev.jdx.server

import dev.jdx.core.rpc.RPC_VERSION
import dev.jdx.core.rpc.RpcCommand
import dev.jdx.core.rpc.RpcRequest
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.property.Arb
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.of
import io.kotest.property.arbitrary.pair
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Duration

/**
 * Tier 2: the daemon speaks over a real unix socket in a temp runtime dir —
 * disk + IPC, so tagged out of the fast loop (TESTING.md §2).
 *
 * Covers the T-041 acceptance: lifecycle (start/status/stop), idle shutdown
 * with a short `--idle`, version-stamp mismatch refusal, and the generating
 * family — framing round-trips of hostile requests over the live socket.
 */
@Tag("tier2")
class DaemonServerTest {

    @TempDir
    lateinit var tempDir: Path

    private fun control(workspace: String = "test-ws", version: Int = RPC_VERSION): DaemonControlPaths {
        val socket = DaemonPaths.socketPath(tempDir, workspace, version)
        return DaemonControlPaths(socket, DaemonPaths.pidPath(socket))
    }

    private data class DaemonControlPaths(val socket: Path, val pid: Path)

    private fun testServer(
        workspace: String = "test-ws",
        idle: Duration? = null,
        handler: DaemonHandler? = null,
    ): DaemonServer {
        val paths = control(workspace)
        return DaemonServer(
            workspace = workspace,
            socketPath = paths.socket,
            pidPath = paths.pid,
            idleTimeout = idle,
            appVersion = "test-0.0.0",
            handler = handler,
        )
    }

    @Test
    fun `start answers health then stop silences the socket`() {
        val server = testServer()
        server.start()
        try {
            val snapshot = DaemonProbe.health(control().socket)
            snapshot shouldNotBe null
            snapshot!!.workspace shouldBe "test-ws"
            snapshot.rpcVersion shouldBe RPC_VERSION
            snapshot.appVersion shouldBe "test-0.0.0"
            snapshot.pid shouldBe ProcessHandle.current().pid()
            (snapshot.queryCount >= 1) shouldBe true // the health probe itself counted
        } finally {
            server.stop()
        }
        DaemonProbe.health(control().socket) shouldBe null
    }

    @Test
    fun `version answers the build version`() {
        val server = testServer()
        server.start()
        try {
            val line = DaemonProbe.roundTrip(control().socket, RpcRequest(RpcCommand.VERSION))
            line shouldNotBe null
            line!! shouldContain "\"command\":\"version\""
            line shouldContain "\"version\":\"test-0.0.0\""
        } finally {
            server.stop()
        }
    }

    @Test
    fun `every request bumps the query count`() {
        val server = testServer()
        server.start()
        try {
            val socket = control().socket
            val before = DaemonProbe.health(socket)!!.queryCount
            DaemonProbe.roundTrip(socket, RpcRequest(RpcCommand.VERSION))
            DaemonProbe.roundTrip(socket, RpcRequest(RpcCommand.VERSION))
            val after = DaemonProbe.health(socket)!!.queryCount
            after shouldBe before + 3 // two versions plus the second health probe
        } finally {
            server.stop()
        }
    }

    @Test
    fun `read queries are refused honestly until T-082, exit 6`() {
        val server = testServer()
        server.start()
        try {
            val line = DaemonProbe.roundTrip(
                control().socket,
                RpcRequest(RpcCommand.MEMBERS, "com.example.Point"),
            )
            line shouldNotBe null
            line!! shouldContain "\"ok\":false"
            line shouldContain "\"command\":\"members\""
            line shouldContain "\"code\":6"
            line shouldContain "T-082"
        } finally {
            server.stop()
        }
    }

    @Test
    fun `a malformed line gets an error envelope, not a dropped connection`() {
        val server = testServer()
        server.start()
        try {
            val socket = control().socket
            val bad = DaemonProbe.rawRoundTrip(socket, "{this is not json")
            bad shouldNotBe null
            bad!! shouldContain "\"ok\":false"
            bad shouldContain "\"command\":\"unknown\""
            // The daemon survives: the next line on a fresh connection still answers.
            val good = DaemonProbe.roundTrip(socket, RpcRequest(RpcCommand.HEALTH))
            good shouldNotBe null
            good!! shouldContain "\"ok\":true"
        } finally {
            server.stop()
        }
    }

    @Test
    fun `a wrong wire version is refused`() {
        val server = testServer()
        server.start()
        try {
            val bad = DaemonProbe.rawRoundTrip(
                control().socket,
                "{\"jdx\":999,\"command\":\"health\",\"query\":\"\",\"params\":{}}",
            )
            bad shouldNotBe null
            bad!! shouldContain "\"ok\":false"
        } finally {
            server.stop()
        }
    }

    @Test
    fun `a version-stamped socket from another version is not answered`() {
        val server = testServer()
        server.start()
        try {
            // The v999 socket file was never bound: no daemon answers there.
            DaemonProbe.health(control(version = 999).socket) shouldBe null
            // And a health payload stamped with a foreign version parses to null.
            val foreign = healthResultJson(
                DaemonStatusSnapshot("ws", 1, 1, 0, 999, "x", 1, 1),
            )
            DaemonProbe.parseHealth(
                okEnvelope("health", "", foreign).replace("\"rpcVersion\":999", "\"rpcVersion\":999"),
            ) shouldBe null
        } finally {
            server.stop()
        }
    }

    @Test
    fun `a second server on the same socket refuses to start`() {
        val first = testServer()
        first.start()
        try {
            val second = testServer()
            var refused = false
            try {
                second.start()
            } catch (_: Exception) {
                refused = true
            } finally {
                second.stop()
            }
            refused shouldBe true
        } finally {
            first.stop()
        }
    }

    @Test
    fun `idle shutdown stops the server after a short idle`() {
        val server = testServer(idle = Duration.ofMillis(200))
        server.start()
        val deadline = System.currentTimeMillis() + 5000
        while (server.isAlive() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
        }
        server.isAlive() shouldBe false
        DaemonProbe.health(control().socket) shouldBe null
        server.stop() // idempotent: safe after the idle task already stopped it
    }

    @Test
    fun `requests keep resetting the idle clock`() {
        val server = testServer(idle = Duration.ofMillis(400))
        server.start()
        try {
            val socket = control().socket
            repeat(5) {
                Thread.sleep(150)
                DaemonProbe.roundTrip(socket, RpcRequest(RpcCommand.HEALTH)) shouldNotBe null
            }
            server.isAlive() shouldBe true
        } finally {
            server.stop()
        }
    }

    @Test
    fun `stop deletes the socket and pid files`() {
        val paths = control()
        val server = testServer()
        server.start()
        server.stop()
        java.nio.file.Files.exists(paths.socket) shouldBe false
        java.nio.file.Files.exists(paths.pid) shouldBe false
    }

    @Test
    fun `over-long socket path fails fast naming the escape hatch`() {
        val longDir = tempDir.resolve("a".repeat(200))
        val socket = DaemonPaths.socketPathIn(longDir, "ws-long")
        val server = DaemonServer(
            workspace = "ws-long",
            socketPath = socket,
            pidPath = DaemonPaths.pidPath(socket),
            idleTimeout = null,
            appVersion = "test-0.0.0",
        )
        var failed: Exception? = null
        try {
            server.start()
        } catch (e: Exception) {
            failed = e
        } finally {
            server.stop()
        }
        (failed is SocketPathTooLongException) shouldBe true
        failed!!.message shouldContain "JDX_RUNTIME_DIR"
    }

    @Test
    fun `checkSocketPathLength trips on macOS earlier than Linux`() {
        val path = java.nio.file.Paths.get("/" + "a".repeat(106))
        // 106 bytes binds on Linux (cap 108) but not on macOS (cap 104).
        checkSocketPathLength(path, "Linux")
        var failed = false
        try {
            checkSocketPathLength(path, "Mac OS X")
        } catch (e: SocketPathTooLongException) {
            failed = true
        }
        failed shouldBe true
    }

    @Test
    fun `the lock is held while running and released on stop`() {
        val paths = control()
        val lockFile = DaemonPaths.lockPath(paths.socket)
        val server = testServer()
        server.start()
        try {
            java.nio.file.Files.exists(lockFile) shouldBe true
            var locked = false
            try {
                java.nio.channels.FileChannel.open(lockFile, java.nio.file.StandardOpenOption.WRITE).use { ch ->
                    val attempt = try {
                        ch.tryLock()
                    } catch (_: java.nio.channels.OverlappingFileLockException) {
                        null
                    }
                    locked = attempt == null
                    attempt?.release()
                }
            } catch (_: Exception) {
                locked = true
            }
            locked shouldBe true
        } finally {
            server.stop()
        }
        // Released: an external holder can now lock, and a fresh server binds clean.
        java.nio.channels.FileChannel.open(
            lockFile,
            java.nio.file.StandardOpenOption.CREATE,
            java.nio.file.StandardOpenOption.WRITE,
        ).use { ch ->
            val attempt = ch.tryLock()
            attempt shouldNotBe null
            attempt?.release()
        }
        val second = testServer()
        second.start()
        try {
            DaemonProbe.health(control().socket) shouldNotBe null
        } finally {
            second.stop()
        }
    }

    private val hostileChars: List<Char> = listOf(
        '"', '\\', '/', '\n', '\r', '\t', '\b', '\u000C', '\u0000', '\u0001', '\u001F',
        ' ', 'a', 'Z', '0', '9', '{', '}', '[', ']', ':', ',', '-', 'e',
    )

    private fun arbHostileString(range: IntRange): Arb<String> =
        Arb.list(Arb.of(hostileChars), range).map { chars -> chars.joinToString("") }

    private fun arbParams(): Arb<Map<String, String>> =
        Arb.list(Arb.pair(arbHostileString(0..8), arbHostileString(0..8)), 0..6).map { it.toMap() }

    private fun arbRequest(): Arb<RpcRequest> =
        Arb.bind(Arb.of(RpcCommand.entries), arbHostileString(0..24), arbParams()) { command, query, params ->
            RpcRequest(command, query, params)
        }

    @Test
    fun `framing round-trip over the socket stays one line per request`(): Unit = runBlocking {
        val server = testServer()
        server.start()
        try {
            val socket = control().socket
            checkAll(200, arbRequest()) { request ->
                val response = DaemonProbe.roundTrip(socket, request)
                response shouldNotBe null
                response!! shouldNotContain "\n"
                response shouldNotContain "\r"
                // The response names the command it answers — the 1:1 line mapping.
                response shouldContain "\"command\":\"${request.command.wire}\""
            }
        } finally {
            server.stop()
        }
    }

    @Test
    fun `hostile raw lines never kill the daemon`(): Unit = runBlocking {
        val server = testServer()
        server.start()
        try {
            val socket = control().socket
            checkAll(200, arbHostileString(0..64)) { hostile ->
                // Every hostile line gets a one-line answer (or, when the line has
                // no terminator-safe shape, the probe still returns without hanging).
                DaemonProbe.rawRoundTrip(socket, "{\"jdx\":1,\"command\":\"health\",\"query\":$hostile}")
            }
            server.isAlive() shouldBe true
            DaemonProbe.health(socket) shouldNotBe null
        } finally {
            server.stop()
        }
    }
}
