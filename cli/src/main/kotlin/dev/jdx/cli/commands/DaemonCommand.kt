package dev.jdx.cli.commands

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.CoreCliktCommand
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import dev.jdx.cli.BuildInfo
import dev.jdx.cli.effectiveJson
import dev.jdx.cli.render.JdxJson
import dev.jdx.cli.render.envelopeJson
import dev.jdx.server.DEFAULT_IDLE_TEXT
import dev.jdx.server.DaemonPaths
import dev.jdx.server.DaemonProbe
import dev.jdx.server.DaemonServer
import dev.jdx.server.DaemonStatusSnapshot
import dev.jdx.server.jdxServiceHandler
import dev.jdx.server.parseIdleDuration
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.encodeToJsonElement
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.system.exitProcess

/**
 * `jdx daemon start|stop|status|restart|run` (T-041; PROPOSAL.md §14.3).
 *
 * A background JVM holding a hot process, listening on a version-stamped
 * unix-domain socket (`$XDG_RUNTIME_DIR/jdx/<workspace-hash>-v1.sock`), with
 * idle shutdown after `--idle` (default 5m, `0` disables). `run` is the
 * foreground server the spawned child executes; the other four are control
 * commands. Thin by rule (D-004): socket, framing and lifecycle live in
 * `:server`; this file only parses flags, prints, and maps exit codes.
 *
 * Exits: 0 ok (including idempotent `start` on a running daemon) · 1 valid
 * query with nothing to report (`status`/`stop` on a stopped daemon) ·
 * 3 usage error (no `XDG_RUNTIME_DIR`, bad `--idle`) · 6 spawn/IO failure.
 */
class DaemonCommand : CoreCliktCommand(name = "daemon") {
    override fun help(context: Context): String =
        "Manage the background daemon: a hot JVM answering queries over a unix socket " +
            "(start, stop, status, restart). The daemon shuts itself down after --idle " +
            "without requests (default 5m, 0 disables)."

    override fun run() = Unit
}

/** Everything `daemon` reads from the outside world, in one injectable value. */
data class DaemonEnv(
    val runtimeDir: Path?,
    val javaExe: String,
    val classpath: String,
) {
    companion object {
        fun system(): DaemonEnv {
            val javaHome = System.getProperty("java.home")
            return DaemonEnv(
                runtimeDir = DaemonPaths.systemRuntimeDir(),
                javaExe = "$javaHome${File.separator}bin${File.separator}java",
                classpath = System.getProperty("java.class.path", ""),
            )
        }
    }
}

/**
 * Spawns the daemon child. Returns the child's pid, or `null` when the spawn
 * itself failed. A `fun interface` so tests script the lifecycle without
 * forking a JVM.
 */
fun interface DaemonSpawner {
    fun spawn(argv: List<String>, logFile: Path): Long?
}

/** [DaemonSpawner] over [ProcessBuilder]: output appended to the log sibling, stdin detached. */
val RealDaemonSpawner: DaemonSpawner = DaemonSpawner { argv, logFile ->
    Files.createDirectories(logFile.parent)
    val process = ProcessBuilder(argv)
        .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()))
        .redirectErrorStream(true)
        .redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
        .start()
    process.pid()
}

/** Thrown by [testDaemonGroup]'s terminator instead of killing the test JVM (L-026). */
class DaemonExit(val code: Int) : RuntimeException("exit $code")

/** Builds the `daemon` group; production defaults spawn real processes. */
fun daemonGroup(
    env: DaemonEnv = DaemonEnv.system(),
    spawner: DaemonSpawner = RealDaemonSpawner,
    terminate: (Int) -> Nothing = ::exitProcess,
): DaemonCommand = DaemonCommand().subcommands(
    DaemonStartCommand(env, spawner, terminate),
    DaemonStopCommand(env, terminate),
    DaemonStatusCommand(env, terminate),
    DaemonRestartCommand(env, spawner, terminate),
    DaemonRunCommand(env, terminate),
)

/** Test seam: an isolated runtime dir that never touches the real `$XDG_RUNTIME_DIR`. */
fun testDaemonGroup(
    runtimeDir: Path,
    spawner: DaemonSpawner = DaemonSpawner { _, _ -> null },
    terminate: (Int) -> Nothing = { throw DaemonExit(it) },
): DaemonCommand = daemonGroup(DaemonEnv(runtimeDir, "java-stub", "cp-stub"), spawner, terminate)

@Serializable
private data class DaemonPayload(
    val workspace: String? = null,
    val running: Boolean = false,
    val uptimeSeconds: Long? = null,
    val memoryUsedMb: Long? = null,
    val indexedArtifacts: Int? = null,
    val queryCount: Long? = null,
    val pid: Long? = null,
    val message: String,
)

private fun DaemonPayload.toJson(ok: Boolean): String =
    envelopeJson("daemon", ok, JdxJson.encodeToJsonElement(this))

private fun snapshotPayload(snapshot: DaemonStatusSnapshot, message: String): DaemonPayload =
    DaemonPayload(
        workspace = snapshot.workspace,
        running = true,
        uptimeSeconds = snapshot.uptimeSeconds,
        memoryUsedMb = snapshot.memoryUsedMb,
        indexedArtifacts = snapshot.indexedArtifacts,
        queryCount = snapshot.queryCount,
        pid = snapshot.pid,
        message = message,
    )

/** Prints text + optional JSON, then terminates on non-zero exit (L-026). */
private fun finishDaemon(
    text: String,
    payload: DaemonPayload,
    json: Boolean,
    exitCode: Int,
    terminate: (Int) -> Nothing,
) {
    if (json) {
        println(payload.toJson(ok = exitCode == 0))
    } else {
        println(text)
    }
    if (exitCode != 0) terminate(exitCode)
}

/** `12s`, `3m 04s`, `2h 05m` — the only non-deterministic numbers `status` prints stay short. */
internal fun formatUptime(totalSeconds: Long): String {
    val seconds = totalSeconds.coerceAtLeast(0)
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val rest = seconds % 60
    return when {
        hours > 0 -> "${hours}h ${minutes.toString().padStart(2, '0')}m"
        minutes > 0 -> "${minutes}m ${rest.toString().padStart(2, '0')}s"
        else -> "${rest}s"
    }
}

/** Socket + pid + log for one workspace. `null` after reporting exit 3 (no runtime dir). */
internal data class DaemonControl(val socket: Path, val pidFile: Path, val logFile: Path)

internal fun controlPaths(
    env: DaemonEnv,
    workspace: String,
    json: Boolean,
    terminate: (Int) -> Nothing,
): DaemonControl? {
    val runtimeDir = env.runtimeDir
        ?: run {
            finishDaemon(
                "usage error: XDG_RUNTIME_DIR is unset — the daemon needs it for its socket",
                DaemonPayload(message = "XDG_RUNTIME_DIR is unset"),
                json, 3, terminate,
            )
            return null
        }
    val socket = DaemonPaths.socketPath(runtimeDir, workspace)
    return DaemonControl(socket, DaemonPaths.pidPath(socket), DaemonPaths.logPath(socket))
}

/** Parses `--idle`, reporting exit 3 on garbage. `null` means "already reported, stop". */
internal fun parseIdleFlag(
    idle: String,
    workspace: String,
    json: Boolean,
    terminate: (Int) -> Nothing,
): Duration? = parseIdleDuration(idle)
    ?: run {
        finishDaemon(
            "usage error: invalid --idle '$idle': use 0, <n>s, <n>m or <n>h",
            DaemonPayload(workspace = workspace, message = "invalid --idle '$idle'"),
            json, 3, terminate,
        )
        null
    }

/** Polls `health` until it answers or the budget runs out. */
internal fun waitForHealth(socket: Path, timeoutMs: Long = 5000): DaemonStatusSnapshot? {
    val deadline = System.currentTimeMillis() + timeoutMs
    var snapshot: DaemonStatusSnapshot? = null
    while (System.currentTimeMillis() < deadline) {
        snapshot = DaemonProbe.health(socket)
        if (snapshot != null) break
        Thread.sleep(100)
    }
    return snapshot
}

/**
 * Best-effort stop used by `stop` and `restart`: destroys the pid-file
 * process when there is one, sweeps stale files, and reports whether a
 * daemon answered at the end. Never throws — callers decide the exit code.
 */
internal fun stopDaemon(control: DaemonControl): StopOutcome {
    if (DaemonProbe.health(control.socket) == null) {
        Files.deleteIfExists(control.socket)
        Files.deleteIfExists(control.pidFile)
        return StopOutcome.NOT_RUNNING
    }
    val pid = runCatching { Files.readString(control.pidFile).trim().toLong() }.getOrNull()
        ?: return StopOutcome.NO_PID
    val handle = ProcessHandle.of(pid).orElse(null)
    if (handle == null || !handle.destroy()) return StopOutcome.NO_PROCESS
    val deadline = System.currentTimeMillis() + 5000
    while (System.currentTimeMillis() < deadline) {
        if (DaemonProbe.health(control.socket) == null) break
        Thread.sleep(100)
    }
    Files.deleteIfExists(control.socket)
    Files.deleteIfExists(control.pidFile)
    return if (DaemonProbe.health(control.socket) == null) StopOutcome.STOPPED else StopOutcome.TIMED_OUT
}

internal enum class StopOutcome {
    NOT_RUNNING,
    STOPPED,
    NO_PID,
    NO_PROCESS,
    TIMED_OUT,
}

/** Spawns `daemon run` and waits for health. `null` after reporting the failure. */
internal fun startDaemon(
    env: DaemonEnv,
    spawner: DaemonSpawner,
    control: DaemonControl,
    workspace: String,
    idle: String,
    json: Boolean,
    terminate: (Int) -> Nothing,
): DaemonStatusSnapshot? {
    DaemonProbe.health(control.socket)?.let { return it }
    val argv = listOf(
        env.javaExe, "-cp", env.classpath, "dev.jdx.cli.JdxCliKt",
        "daemon", "run", "--workspace", workspace, "--idle", idle,
    )
    val childPid = runCatching { spawner.spawn(argv, control.logFile) }.getOrNull()
    if (childPid == null) {
        finishDaemon(
            "cannot start daemon: spawner failed (see ${control.logFile})",
            DaemonPayload(workspace = workspace, message = "spawner failed"),
            json, 6, terminate,
        )
        return null
    }
    val snapshot = waitForHealth(control.socket)
    if (snapshot == null) {
        finishDaemon(
            "cannot start daemon: socket did not answer within 5s " +
                "(child pid $childPid, see ${control.logFile})",
            DaemonPayload(workspace = workspace, message = "socket did not answer", pid = childPid),
            json, 6, terminate,
        )
        return null
    }
    return snapshot
}

private fun statusText(snapshot: DaemonStatusSnapshot): String = buildString {
    appendLine("jdx daemon: running")
    appendLine("  workspace: ${snapshot.workspace}")
    appendLine("  uptime: ${formatUptime(snapshot.uptimeSeconds)}")
    appendLine("  memory: ${snapshot.memoryUsedMb} MB used")
    appendLine("  indexed artifacts: ${snapshot.indexedArtifacts}")
    append("  queries served: ${snapshot.queryCount}")
}.trimEnd()

class DaemonStartCommand(
    private val env: DaemonEnv = DaemonEnv.system(),
    private val spawner: DaemonSpawner = RealDaemonSpawner,
    private val terminate: (Int) -> Nothing = ::exitProcess,
) : CoreCliktCommand(name = "start") {
    override fun help(context: Context): String =
        "Start the daemon for a workspace (idempotent: already running exits 0). " +
            "Spawns a background JVM and waits until its socket answers health."

    private val workspace by option("--workspace", help = "Workspace the daemon serves.").default("default")

    private val idle by option(
        "--idle",
        help = "Idle shutdown after this long without requests (e.g. 30s, 5m, 2h; 0 disables).",
    ).default(DEFAULT_IDLE_TEXT)

    private val json by option(
        "--json",
        help = "Emit machine-readable JSON instead of human-readable text (same information, D-007).",
    ).flag()

    override fun run() {
        val json = effectiveJson(json)
        parseIdleFlag(idle, workspace, json, terminate) ?: return
        val control = controlPaths(env, workspace, json, terminate) ?: return
        val alreadyRunning = DaemonProbe.health(control.socket) != null
        val snapshot = startDaemon(env, spawner, control, workspace, idle, json, terminate) ?: return
        if (alreadyRunning) {
            finishDaemon(
                "daemon already running: workspace '${snapshot.workspace}' " +
                    "(uptime ${formatUptime(snapshot.uptimeSeconds)}, pid ${snapshot.pid})",
                snapshotPayload(snapshot, "already running"),
                json, 0, terminate,
            )
        } else {
            finishDaemon(
                "daemon started: workspace '$workspace' (pid ${snapshot.pid})",
                snapshotPayload(snapshot, "started"),
                json, 0, terminate,
            )
        }
    }
}

class DaemonStopCommand(
    private val env: DaemonEnv = DaemonEnv.system(),
    private val terminate: (Int) -> Nothing = ::exitProcess,
) : CoreCliktCommand(name = "stop") {
    override fun help(context: Context): String =
        "Stop the daemon for a workspace. Exits 1 when no daemon is running."

    private val workspace by option("--workspace", help = "Workspace the daemon serves.").default("default")

    private val json by option(
        "--json",
        help = "Emit machine-readable JSON instead of human-readable text (same information, D-007).",
    ).flag()

    override fun run() {
        val json = effectiveJson(json)
        val control = controlPaths(env, workspace, json, terminate) ?: return
        when (stopDaemon(control)) {
            StopOutcome.NOT_RUNNING -> finishDaemon(
                "daemon not running (workspace '$workspace')",
                DaemonPayload(workspace = workspace, message = "not running"),
                json, 1, terminate,
            )
            StopOutcome.STOPPED -> finishDaemon(
                "daemon stopped (workspace '$workspace')",
                DaemonPayload(workspace = workspace, message = "stopped"),
                json, 0, terminate,
            )
            StopOutcome.NO_PID -> finishDaemon(
                "cannot stop daemon: pid file is missing or corrupt — kill the process by hand",
                DaemonPayload(workspace = workspace, message = "pid file unreadable"),
                json, 6, terminate,
            )
            StopOutcome.NO_PROCESS -> finishDaemon(
                "cannot stop daemon: no live process for the recorded pid",
                DaemonPayload(workspace = workspace, message = "no live process"),
                json, 6, terminate,
            )
            StopOutcome.TIMED_OUT -> finishDaemon(
                "cannot stop daemon: the process ignores the stop signal",
                DaemonPayload(workspace = workspace, message = "stop timed out"),
                json, 6, terminate,
            )
        }
    }
}

class DaemonStatusCommand(
    private val env: DaemonEnv = DaemonEnv.system(),
    private val terminate: (Int) -> Nothing = ::exitProcess,
) : CoreCliktCommand(name = "status") {
    override fun help(context: Context): String =
        "Report the daemon for a workspace: uptime, memory, indexed artifacts, query count. " +
            "Exits 1 when no daemon is running."

    private val workspace by option("--workspace", help = "Workspace the daemon serves.").default("default")

    private val json by option(
        "--json",
        help = "Emit machine-readable JSON instead of human-readable text (same information, D-007).",
    ).flag()

    override fun run() {
        val json = effectiveJson(json)
        val control = controlPaths(env, workspace, json, terminate) ?: return
        val snapshot = DaemonProbe.health(control.socket)
        if (snapshot == null) {
            finishDaemon(
                "daemon not running (workspace '$workspace')",
                DaemonPayload(workspace = workspace, message = "not running"),
                json, 1, terminate,
            )
            return
        }
        finishDaemon(statusText(snapshot), snapshotPayload(snapshot, "running"), json, 0, terminate)
    }
}

class DaemonRestartCommand(
    private val env: DaemonEnv = DaemonEnv.system(),
    private val spawner: DaemonSpawner = RealDaemonSpawner,
    private val terminate: (Int) -> Nothing = ::exitProcess,
) : CoreCliktCommand(name = "restart") {
    override fun help(context: Context): String =
        "Restart the daemon for a workspace: stop when running, then start."

    private val workspace by option("--workspace", help = "Workspace the daemon serves.").default("default")

    private val idle by option(
        "--idle",
        help = "Idle shutdown after this long without requests (e.g. 30s, 5m, 2h; 0 disables).",
    ).default(DEFAULT_IDLE_TEXT)

    private val json by option(
        "--json",
        help = "Emit machine-readable JSON instead of human-readable text (same information, D-007).",
    ).flag()

    override fun run() {
        val json = effectiveJson(json)
        parseIdleFlag(idle, workspace, json, terminate) ?: return
        val control = controlPaths(env, workspace, json, terminate) ?: return
        stopDaemon(control) // best effort: a missing daemon is the normal case here
        val snapshot = startDaemon(env, spawner, control, workspace, idle, json, terminate) ?: return
        finishDaemon(
            "daemon restarted: workspace '$workspace' (pid ${snapshot.pid})",
            snapshotPayload(snapshot, "restarted"),
            json, 0, terminate,
        )
    }
}

/**
 * `jdx daemon run` — the foreground server. Internal: `start` spawns exactly
 * this, and operators never type it by hand. Blocks until idle shutdown or
 * `daemon stop`; exits 1 when another daemon already holds the socket.
 */
class DaemonRunCommand(
    private val env: DaemonEnv = DaemonEnv.system(),
    private val terminate: (Int) -> Nothing = ::exitProcess,
) : CoreCliktCommand(name = "run") {
    override fun help(context: Context): String =
        "Run the daemon in the foreground (internal: daemon start spawns this)."

    private val workspace by option("--workspace", help = "Workspace the daemon serves.").default("default")

    private val idle by option(
        "--idle",
        help = "Idle shutdown after this long without requests (e.g. 30s, 5m, 2h; 0 disables).",
    ).default(DEFAULT_IDLE_TEXT)

    override fun run() {
        val idleTimeout = parseIdleDuration(idle)
        if (idleTimeout == null) {
            echo("usage error: invalid --idle '$idle': use 0, <n>s, <n>m or <n>h", err = true)
            terminate(3)
            return
        }
        val runtimeDir = env.runtimeDir
        if (runtimeDir == null) {
            echo("usage error: XDG_RUNTIME_DIR is unset — the daemon needs it for its socket", err = true)
            terminate(3)
            return
        }
        val socket = DaemonPaths.socketPath(runtimeDir, workspace)
        // The dispatch handler (T-082) answers every read query through the same
        // JdxService the one-shot CLI calls; health/version stay on the transport
        // internals. Roots resolve per request, so `ws create` while the daemon
        // runs is picked up. The handler captures this server for `status` —
        // assigned before any connection is served, read only afterwards.
        lateinit var server: DaemonServer
        val handler = jdxServiceHandler(
            workspace = workspace,
            status = { server.snapshot() },
            appVersion = BuildInfo.version,
        )
        server = DaemonServer(
            workspace = workspace,
            socketPath = socket,
            pidPath = DaemonPaths.pidPath(socket),
            idleTimeout = idleTimeout.takeUnless { it.isZero },
            appVersion = BuildInfo.version,
            handler = handler,
        )
        Runtime.getRuntime().addShutdownHook(Thread({ server.stop() }, "jdx-daemon-shutdown"))
        try {
            server.start()
        } catch (e: Exception) {
            echo("cannot start daemon: ${e.message}", err = true)
            terminate(1)
            return
        }
        echo("serving workspace '$workspace' (pid ${ProcessHandle.current().pid()})", err = true)
        server.join()
    }
}
