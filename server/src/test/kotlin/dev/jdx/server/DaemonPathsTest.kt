package dev.jdx.server

import dev.jdx.core.rpc.RPC_VERSION
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldMatch
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/** Tier 1: socket layout is pure path math — no disk, no sockets. */
class DaemonPathsTest {

    @Test
    fun `workspace hash is 16 lowercase hex chars`() {
        DaemonPaths.workspaceHash("default") shouldMatch Regex("[0-9a-f]{16}")
    }

    @Test
    fun `workspace hash is deterministic`() {
        DaemonPaths.workspaceHash("my-workspace") shouldBe DaemonPaths.workspaceHash("my-workspace")
    }

    @Test
    fun `distinct workspaces hash distinctly`() {
        DaemonPaths.workspaceHash("alpha") shouldNotBe DaemonPaths.workspaceHash("beta")
    }

    @Test
    fun `socket file name carries the rpc version stamp`() {
        val name = DaemonPaths.socketFileName("default")
        name shouldBe "${DaemonPaths.workspaceHash("default")}-v$RPC_VERSION.sock"
    }

    @Test
    fun `a different version stamps a different socket name`() {
        DaemonPaths.socketFileName("default", 999) shouldNotBe DaemonPaths.socketFileName("default")
    }

    @Test
    fun `socket path nests under runtimeDir jdx`() {
        val runtime = Paths.get("/run/user/1000")
        DaemonPaths.socketPath(runtime, "default") shouldBe
            runtime.resolve("jdx").resolve(DaemonPaths.socketFileName("default"))
    }

    @Test
    fun `pid and log files are siblings of the socket`() {
        val socket = Paths.get("/run/user/1000/jdx/abcdef0123456789-v1.sock")
        DaemonPaths.pidPath(socket).toString() shouldBe "/run/user/1000/jdx/abcdef0123456789-v1.pid"
        DaemonPaths.logPath(socket).toString() shouldBe "/run/user/1000/jdx/abcdef0123456789-v1.log"
    }

    @Test
    fun `hostile workspace names cannot escape the socket directory`() {
        // The hash is the whole file stem: slashes and dots never reach the path.
        val socket = DaemonPaths.socketPath(Paths.get("/run/user/1000"), "../../etc/evil")
        socket.parent shouldBe Paths.get("/run/user/1000/jdx")
        socket.fileName.toString() shouldMatch Regex("[0-9a-f]{16}-v\\d+\\.sock")
    }
}
