package dev.jdx.cli.commands

import dev.jdx.server.DaemonPaths
import dev.jdx.server.DaemonProbe
import dev.jdx.server.DaemonServer
import dev.jdx.testsupport.paths.shortSocketDir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.nio.file.Files
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * Tier 2: [stopDaemon] against a live socket (issue #50).
 *
 * The pid file can go stale across crashes and pid reuse can hand the number
 * to an unrelated process — these tests pin that `stop` never kills a reused
 * pid and that stale files sweep clean for a restart.
 */
@Tag("tier2")
class DaemonStopRobustnessTest {

    @TempDir
    lateinit var tempDir: Path

    // Short socket scope: sun_path caps binds (104 macOS) while @TempDir
    // reads /var/folders/... there. Lazy — one control per test.
    private val socketScope: Path by lazy { shortSocketDir("stop") }

    private fun control(workspace: String = "stop-ws"): DaemonControl {
        val socket = DaemonPaths.socketPath(socketScope, workspace)
        return DaemonControl(socket, DaemonPaths.pidPath(socket), DaemonPaths.logPath(socket))
    }

    @Test
    fun `stop refuses a reused pid and leaves the daemon running`() {
        val control = control()
        val server = DaemonServer(
            workspace = "stop-ws",
            socketPath = control.socket,
            pidPath = control.pidFile,
            idleTimeout = null,
            appVersion = "test-0.0.0",
        )
        server.start()
        try {
            // The test JVM is alive but is not a `daemon run` child: a pid file
            // pointing at it must never be signalled.
            Files.writeString(control.pidFile, ProcessHandle.current().pid().toString())
            stopDaemon(control) shouldBe StopOutcome.PID_REUSED
            DaemonProbe.health(control.socket) shouldNotBe null
        } finally {
            server.stop()
        }
    }

    @Test
    fun `stop sweeps a dead pid and stale files`() {
        val control = control("stale-ws")
        Files.createDirectories(control.socket.parent)
        Files.writeString(control.socket, "stale")
        // A pid that names no live process: swept, never signalled.
        Files.writeString(control.pidFile, "2147483647")
        val outcome = stopDaemon(control, lookup = { null })
        (outcome == StopOutcome.NOT_RUNNING || outcome == StopOutcome.NO_PROCESS) shouldBe true
        Files.exists(control.socket) shouldBe false
        Files.exists(control.pidFile) shouldBe false
    }
}
