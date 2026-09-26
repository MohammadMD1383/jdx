package dev.jdx.server

import dev.jdx.core.paths.JdxOs
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

    @Test
    fun `socketPathIn resolves directly under the socket dir`() {
        val dir = Paths.get("/tmp/jdx-1000")
        DaemonPaths.socketPathIn(dir, "default") shouldBe
            dir.resolve(DaemonPaths.socketFileName("default"))
    }

    @Test
    fun `socket dir falls back when XDG_RUNTIME_DIR is unset on Linux`() {
        val home = Paths.get("/home/alice")
        val cache = home.resolve(".cache/jdx")
        // TMPDIR row wins over the cache fallback.
        DaemonPaths.socketDir(
            home, JdxOs.LINUX, mapOf("TMPDIR" to "/tmp", "UID" to "1000"), cache, null,
        ) shouldBe Paths.get("/tmp/jdx-1000")
        // No TMPDIR either: the cache `run` child is the last fallback, never an error.
        DaemonPaths.socketDir(home, JdxOs.LINUX, emptyMap(), cache, null) shouldBe
            cache.resolve("run")
    }

    @Test
    fun `socket dir prefers TMPDIR on macOS and LOCALAPPDATA on Windows`() {
        val home = Paths.get("/Users/alice")
        val macCache = home.resolve("Library/Caches/jdx")
        DaemonPaths.socketDir(
            home, JdxOs.MACOS, mapOf("TMPDIR" to "/var/folders/tmp"), macCache, "alice",
        ) shouldBe Paths.get("/var/folders/tmp/jdx-alice")
        val winHome = Paths.get("C:\\Users\\alice")
        val winCache = Paths.get("C:\\Users\\alice\\AppData\\Local").resolve("jdx/cache")
        DaemonPaths.socketDir(
            winHome, JdxOs.WINDOWS,
            mapOf("LOCALAPPDATA" to "C:\\Users\\alice\\AppData\\Local"), winCache, null,
        ) shouldBe Paths.get("C:\\Users\\alice\\AppData\\Local").resolve("jdx/run")
    }

    @Test
    fun `JDX_RUNTIME_DIR overrides every platform default`() {
        val home = Paths.get("/home/alice")
        val cache = home.resolve(".cache/jdx")
        DaemonPaths.socketDir(
            home, JdxOs.LINUX,
            mapOf("XDG_RUNTIME_DIR" to "/run/user/1000", "JDX_RUNTIME_DIR" to "/custom/run"),
            cache, null,
        ) shouldBe Paths.get("/custom/run")
    }
}
