package dev.jdx.cli.service

import dev.jdx.server.DaemonPaths
import dev.jdx.server.DaemonServer
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Tier 2: `doctor` daemon aliveness over live sockets (T-084).
 *
 * A real [DaemonServer] answers `health`, so the default probe must report it
 * running; a dead `.sock` file answers nothing and reads stale. Disk + IPC, so
 * tagged out of the fast loop (TESTING.md §2).
 */
@Tag("tier2")
class DoctorDaemonLiveTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `a live daemon socket is running and a dead file is stale`() {
        val home = tempDir.resolve("home").also { Files.createDirectories(it) }
        val runtime = tempDir.resolve("run").also { Files.createDirectories(it) }
        val socket = DaemonPaths.socketPath(runtime, "live-ws")
        val server = DaemonServer(
            workspace = "live-ws",
            socketPath = socket,
            pidPath = DaemonPaths.pidPath(socket),
            idleTimeout = null,
            appVersion = "test-0.0.0",
        )
        server.start()
        try {
            Files.createFile(runtime.resolve("jdx").resolve("dead.sock"))
            val base = fakeEnvironment(tempDir.resolve("env"))
            val env = base.copy(userHome = home, runtimeDir = runtime)
            // Point the fake home's runtime dir at the live sockets: the probe
            // must find one running daemon plus one stale file.
            val report = DoctorService(env).probe()

            val daemon = report.checks.first { it.name == "daemon" }
            daemon.status shouldBe DoctorStatus.WARN
            daemon.detail shouldContain "1 running"
            daemon.detail shouldContain "live-ws"
            daemon.detail shouldContain "1 stale"
            daemon.detail shouldContain "dead.sock"
        } finally {
            server.stop()
        }
    }

    @Test
    fun `after the daemon stops its socket reads stale`() {
        val runtime = tempDir.resolve("run2").also { Files.createDirectories(it) }
        val socket = DaemonPaths.socketPath(runtime, "gone-ws")
        val server = DaemonServer(
            workspace = "gone-ws",
            socketPath = socket,
            pidPath = DaemonPaths.pidPath(socket),
            idleTimeout = null,
            appVersion = "test-0.0.0",
        )
        server.start()
        server.stop()
        // `stop` sweeps the socket file; leave a dead one behind to prove the
        // stale path with the real default probe (no fakes).
        Files.createFile(runtime.resolve("jdx").resolve("leftover.sock"))
        val base = fakeEnvironment(tempDir.resolve("env2"))
        val env = base.copy(runtimeDir = runtime)
        val report = DoctorService(env).probe()

        val daemon = report.checks.first { it.name == "daemon" }
        daemon.status shouldBe DoctorStatus.WARN
        daemon.detail shouldContain "stale"
        daemon.detail shouldContain "leftover.sock"
        daemon.detail shouldNotContain "running"
    }
}
