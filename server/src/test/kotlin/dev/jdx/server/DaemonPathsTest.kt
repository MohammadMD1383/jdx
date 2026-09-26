package dev.jdx.server

import dev.jdx.core.paths.JdxOs
import dev.jdx.core.rpc.RPC_VERSION
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
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
        // resolveSibling, never string literals: Windows parses /a/b as \a\b.
        val socket = Paths.get("/run/user/1000/jdx/abcdef0123456789-v1.sock")
        DaemonPaths.pidPath(socket) shouldBe socket.resolveSibling("abcdef0123456789-v1.pid")
        DaemonPaths.logPath(socket) shouldBe socket.resolveSibling("abcdef0123456789-v1.log")
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

    @Test
    fun `sun path cap is 104 on macOS and 108 elsewhere`() {
        DaemonPaths.maxSocketPathBytes(JdxOs.MACOS) shouldBe 104
        DaemonPaths.maxSocketPathBytes(JdxOs.LINUX) shouldBe 108
        DaemonPaths.maxSocketPathBytes(JdxOs.WINDOWS) shouldBe 108
    }

    @Test
    fun `socket path length counts UTF-8 bytes`() {
        DaemonPaths.socketPathByteLength(Paths.get("/tmp/jdx.sock")) shouldBe
            "/tmp/jdx.sock".toByteArray(Charsets.UTF_8).size
        // One non-ASCII char is two bytes on the wire, not one.
        DaemonPaths.socketPathByteLength(Paths.get("/tmp/jdx-\u00e9.sock")) shouldBe
            "/tmp/jdx-\u00e9.sock".toByteArray(Charsets.UTF_8).size
    }

    @Test
    fun `socket path too long trips one byte past the cap`() {
        val cap = DaemonPaths.maxSocketPathBytes(JdxOs.LINUX)
        val ok = Paths.get("/" + "a".repeat(cap - 1))
        val over = Paths.get("/" + "a".repeat(cap))
        DaemonPaths.socketPathTooLong(ok, JdxOs.LINUX) shouldBe false
        DaemonPaths.socketPathTooLong(over, JdxOs.LINUX) shouldBe true
        // macOS trips earlier: the same path is fine on Linux but long on macOS.
        val macCap = DaemonPaths.maxSocketPathBytes(JdxOs.MACOS)
        val macOver = Paths.get("/" + "a".repeat(macCap))
        DaemonPaths.socketPathTooLong(macOver, JdxOs.MACOS) shouldBe true
        DaemonPaths.socketPathTooLong(macOver, JdxOs.LINUX) shouldBe false
    }

    @Test
    fun `over-long message names the path and the escape hatch`() {
        val over = Paths.get("/" + "a".repeat(200))
        val message = DaemonPaths.describeSocketPathTooLong(over, JdxOs.LINUX)
        message shouldContain "108"
        message shouldContain "JDX_RUNTIME_DIR"
        message shouldContain over.toString()
    }

    @Test
    fun `lock file is a sibling of the socket with the version stamp`() {
        val socket = Paths.get("/run/user/1000/jdx/abcdef0123456789-v1.sock")
        DaemonPaths.lockPath(socket) shouldBe socket.resolveSibling("abcdef0123456789-v1.lock")
        DaemonPaths.lockFileName("default") shouldBe
            "${DaemonPaths.workspaceHash("default")}-v$RPC_VERSION.lock"
    }
}
