package dev.jdx.server

import dev.jdx.core.render.JsonEscape
import dev.jdx.core.rpc.RPC_VERSION
import dev.jdx.core.rpc.RpcCommand
import dev.jdx.core.rpc.RpcRequest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.ClosedChannelException
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * What `daemon status` reports (T-041): uptime, workspace, memory, indexed
 * artifacts, query count. Produced server-side so every client reads the same
 * numbers; rendered to text by the CLI and to JSON by `--json`.
 */
public data class DaemonStatusSnapshot(
    public val workspace: String,
    public val uptimeSeconds: Long,
    public val queryCount: Long,
    public val indexedArtifacts: Int,
    public val rpcVersion: Int,
    public val appVersion: String,
    public val pid: Long,
    public val memoryUsedMb: Long,
)

/**
 * Answers one decoded request with one complete envelope line (no trailing
 * newline — framing is the transport's job). Injectable so tests can script a
 * daemon without a [DaemonServer], and so the `JdxService` dispatch (T-082)
 * can replace the default without touching the socket loop.
 */
public fun interface DaemonHandler {
    public fun handle(request: RpcRequest): String
}

/**
 * The default handler (T-041): `health` and `version` are answered from the
 * live [DaemonStatusSnapshot]; every read query is refused with an exit-6
 * envelope naming T-082, where the `JdxService` dispatch lands. Refusing
 * honestly beats guessing — the CLI stays in-process until T-042 wires the
 * transparent client, so no user query can take this path by accident.
 */
public fun defaultDaemonHandler(
    status: () -> DaemonStatusSnapshot,
    appVersion: String,
): DaemonHandler = DaemonHandler { request ->
    when (request.command) {
        RpcCommand.HEALTH ->
            okEnvelope("health", request.query, healthResultJson(status()))
        RpcCommand.VERSION ->
            okEnvelope("version", request.query, "{\"version\":" + JsonEscape.quote(appVersion) + "}")
        else ->
            errorEnvelope(
                command = request.command.wire,
                query = request.query,
                code = 6,
                message = "daemon cannot answer '${request.command.wire}' yet: " +
                    "query dispatch lands in T-082; run the same query without the daemon",
            )
    }
}

/** `{"jdx":1,"ok":true,"command":…,"query":…,"result":…,"warnings":[],"provenance":[]}`. */
internal fun okEnvelope(command: String, query: String, resultJson: String): String = buildString {
    append("{\"jdx\":").append(RPC_VERSION)
    append(",\"ok\":true")
    append(",\"command\":").append(JsonEscape.quote(command))
    append(",\"query\":").append(JsonEscape.quote(query))
    append(",\"result\":").append(resultJson)
    append(",\"warnings\":[],\"provenance\":[]}")
}

/** `{"jdx":1,"ok":false,…,"error":{"code":…,"message":…},"candidates":[],"warnings":[],"provenance":[]}`. */
internal fun errorEnvelope(command: String, query: String, code: Int, message: String): String =
    buildString {
        append("{\"jdx\":").append(RPC_VERSION)
        append(",\"ok\":false")
        append(",\"command\":").append(JsonEscape.quote(command))
        append(",\"query\":").append(JsonEscape.quote(query))
        append(",\"error\":{\"code\":").append(code)
        append(",\"message\":").append(JsonEscape.quote(message)).append("}")
        append(",\"candidates\":[],\"warnings\":[],\"provenance\":[]}")
    }

/** The `health` result payload, fixed key order like every other renderer. */
internal fun healthResultJson(snapshot: DaemonStatusSnapshot): String = buildString {
    append("{\"workspace\":").append(JsonEscape.quote(snapshot.workspace))
    append(",\"uptimeSeconds\":").append(snapshot.uptimeSeconds)
    append(",\"queryCount\":").append(snapshot.queryCount)
    append(",\"indexedArtifacts\":").append(snapshot.indexedArtifacts)
    append(",\"rpcVersion\":").append(snapshot.rpcVersion)
    append(",\"appVersion\":").append(JsonEscape.quote(snapshot.appVersion))
    append(",\"pid\":").append(snapshot.pid)
    append(",\"memoryUsedMb\":").append(snapshot.memoryUsedMb)
    append("}")
}

/**
 * A background JVM holding a hot process, listening on a version-stamped
 * unix-domain socket (T-041; PROPOSAL.md §14.3).
 *
 * The wire is the T-040 contract: one NDJSON [RpcRequest] per line in, one
 * envelope line out — a strict 1:1 line mapping, so a client always knows
 * which response belongs to which request. Malformed lines get an exit-6
 * envelope, never a dropped connection: a daemon parses whatever a socket
 * hands it (D-056 §6).
 *
 * Idle shutdown is a single-shot [ScheduledExecutorService] task, reset on
 * every request; a `null` or zero [idleTimeout] disables it. Live-index
 * acceleration is deliberately out (D-043 precedent): [indexedArtifacts]
 * reports the supplier's count, `0` until T-082 wires the store.
 *
 * Threading: one accept thread, one virtual thread per connection, one
 * scheduler thread. [stop] is idempotent and safe to call from any thread,
 * including the idle task itself.
 */
public class DaemonServer(
    public val workspace: String,
    public val socketPath: Path,
    public val pidPath: Path,
    public val idleTimeout: Duration?,
    public val appVersion: String,
    public val indexedArtifacts: () -> Int = { 0 },
    handler: DaemonHandler? = null,
) : AutoCloseable {
    private val startNanos: Long = System.nanoTime()
    private val queryCount: AtomicLong = AtomicLong(0)
    private val running: AtomicBoolean = AtomicBoolean(false)
    private val stoppedLatch: CountDownLatch = CountDownLatch(1)
    private val effectiveHandler: DaemonHandler =
        handler ?: defaultDaemonHandler(::snapshot, appVersion)

    private var channel: ServerSocketChannel? = null
    private var acceptThread: Thread? = null
    private var idleFuture: ScheduledFuture<*>? = null
    private val pool = Executors.newVirtualThreadPerTaskExecutor()
    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "jdx-daemon-idle").apply { isDaemon = true }
    }

    /** Binds the socket and serves in the background. Throws [IOException] when the bind fails. */
    @Throws(IOException::class)
    public fun start() {
        if (!running.compareAndSet(false, true)) return
        try {
            if (DaemonProbe.roundTrip(socketPath, RpcRequest(RpcCommand.HEALTH)) != null) {
                throw IOException("daemon already running at $socketPath")
            }
            Files.createDirectories(socketPath.parent)
            Files.deleteIfExists(socketPath)
            channel = ServerSocketChannel.open(StandardProtocolFamily.UNIX).also { server ->
                server.bind(UnixDomainSocketAddress.of(socketPath))
            }
            Files.writeString(pidPath, ProcessHandle.current().pid().toString(), StandardCharsets.UTF_8)
        } catch (e: Exception) {
            running.set(false)
            if (e is IOException) throw e
            throw IOException("cannot start daemon at $socketPath: ${e.message}", e)
        }
        acceptThread = Thread(::acceptLoop, "jdx-daemon-accept").also { it.start() }
        scheduleIdle()
    }

    /** Current numbers for `status`/`health`. Monotonic: uptime and query count never decrease. */
    public fun snapshot(): DaemonStatusSnapshot {
        val runtime = Runtime.getRuntime()
        return DaemonStatusSnapshot(
            workspace = workspace,
            uptimeSeconds = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - startNanos),
            queryCount = queryCount.get(),
            indexedArtifacts = runCatching { indexedArtifacts() }.getOrDefault(0),
            rpcVersion = RPC_VERSION,
            appVersion = appVersion,
            pid = ProcessHandle.current().pid(),
            memoryUsedMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024),
        )
    }

    public fun isAlive(): Boolean = running.get()

    /** Blocks until [stop] runs (idle timeout, `daemon stop`, or JVM shutdown). */
    public fun join() {
        stoppedLatch.await()
    }

    /** Idempotent: closes the socket, deletes socket + pid files, stops all threads. */
    public fun stop() {
        if (!running.compareAndSet(true, false)) return
        synchronized(this) { idleFuture?.cancel(false) }
        runCatching { channel?.close() }
        pool.shutdownNow()
        scheduler.shutdownNow()
        runCatching { Files.deleteIfExists(socketPath) }
        runCatching { Files.deleteIfExists(pidPath) }
        stoppedLatch.countDown()
    }

    override fun close(): Unit = stop()

    private fun acceptLoop() {
        val server = channel ?: return
        while (running.get()) {
            val client = try {
                server.accept()
            } catch (_: ClosedChannelException) {
                return
            } catch (_: IOException) {
                if (running.get()) continue else return
            }
            pool.submit { serveConnection(client) }
        }
    }

    private fun serveConnection(client: SocketChannel) {
        // Raw channel IO throughout: java.net.Socket does not speak AF_UNIX,
        // so `channel.socket().streams` is not an option (L-098).
        client.use { channel ->
            val pending = ByteArrayOutputStream()
            val buffer = ByteBuffer.allocate(8192)
            while (running.get()) {
                buffer.clear()
                val read = try {
                    channel.read(buffer)
                } catch (_: Exception) {
                    return
                }
                if (read == -1) return
                buffer.flip()
                while (buffer.hasRemaining()) {
                    val byte = buffer.get()
                    if (byte == NEWLINE_BYTE) {
                        val line = pending.toString(StandardCharsets.UTF_8)
                        pending.reset()
                        // One response per line, always: the 1:1 mapping is what
                        // makes newline framing safe for pipelined clients.
                        queryCount.incrementAndGet()
                        scheduleIdle()
                        try {
                            writeFully(channel, dispatch(line) + "\n")
                        } catch (_: Exception) {
                            return
                        }
                    } else {
                        pending.write(byte.toInt())
                    }
                }
            }
        }
    }

    private fun dispatch(line: String): String {
        val request = RpcRequest.decode(line)
            ?: return errorEnvelope(
                command = "unknown",
                query = "",
                code = 6,
                message = "malformed request: not a v1 RpcRequest line (T-040); refused, not guessed",
            )
        return try {
            effectiveHandler.handle(request)
        } catch (e: Exception) {
            errorEnvelope(
                command = request.command.wire,
                query = request.query,
                code = 6,
                message = "daemon handler failed: ${e.message ?: e.javaClass.simpleName}",
            )
        }
    }

    @Synchronized
    private fun scheduleIdle() {
        idleFuture?.cancel(false)
        val timeout = idleTimeout
        if (!running.get() || timeout == null || timeout.isZero || timeout.isNegative) return
        idleFuture = scheduler.schedule({ stop() }, timeout.toMillis(), TimeUnit.MILLISECONDS)
    }
}

/**
 * The thinnest possible client (T-041): one request in, one envelope line
 * out. The transparent CLI client (T-042) builds on this. Every function
 * returns `null` instead of throwing — a missing daemon is routine, not an
 * error (PROPOSAL.md §14.1: degrade to in-process).
 */
public object DaemonProbe {
    private val lenientJson: Json = Json { ignoreUnknownKeys = true }

    /** Sends one framed request, reads one response line. `null` on any failure. */
    public fun roundTrip(socketPath: Path, request: RpcRequest, timeoutMs: Long = 3000): String? =
        rawRoundTrip(socketPath, request.frame(), timeoutMs)

    /** Sends one raw line (framing included), reads one response line. `null` on any failure. */
    public fun rawRoundTrip(socketPath: Path, line: String, timeoutMs: Long = 3000): String? {
        // Raw channel IO: java.net.Socket does not speak AF_UNIX (L-098).
        // Non-blocking reads with a selector give the probe its timeout; the
        // connect itself stays blocking — a refused local connect fails fast.
        return try {
            SocketChannel.open(StandardProtocolFamily.UNIX).use { channel ->
                channel.connect(UnixDomainSocketAddress.of(socketPath))
                writeFully(channel, if (line.endsWith("\n")) line else "$line\n")
                channel.configureBlocking(false)
                readLine(channel, timeoutMs)
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * The `health` handshake: `null` when no daemon answers, when the answer
     * is not a health envelope, or when the daemon speaks a different
     * [RPC_VERSION] — a version-stamped socket path (T-041) plus this check
     * means an upgraded `jdx` never half-understands a stale daemon.
     */
    public fun health(socketPath: Path, timeoutMs: Long = 3000): DaemonStatusSnapshot? {
        val line = roundTrip(socketPath, RpcRequest(RpcCommand.HEALTH), timeoutMs) ?: return null
        return parseHealth(line)
    }

    internal fun parseHealth(line: String): DaemonStatusSnapshot? {
        return try {
            val root = lenientJson.parseToJsonElement(line).jsonObject
            if (root["ok"]?.jsonPrimitive?.content != "true") return null
            if (root["command"]?.jsonPrimitive?.content != RpcCommand.HEALTH.wire) return null
            val result = root["result"]?.jsonObject ?: return null
            val rpcVersion = result["rpcVersion"]?.jsonPrimitive?.intOrNull ?: return null
            if (rpcVersion != RPC_VERSION) return null
            DaemonStatusSnapshot(
                workspace = result["workspace"]?.jsonPrimitive?.content ?: return null,
                uptimeSeconds = result["uptimeSeconds"]?.jsonPrimitive?.longOrNull ?: return null,
                queryCount = result["queryCount"]?.jsonPrimitive?.longOrNull ?: return null,
                indexedArtifacts = result["indexedArtifacts"]?.jsonPrimitive?.intOrNull ?: return null,
                rpcVersion = rpcVersion,
                appVersion = result["appVersion"]?.jsonPrimitive?.content ?: return null,
                pid = result["pid"]?.jsonPrimitive?.longOrNull ?: return null,
                memoryUsedMb = result["memoryUsedMb"]?.jsonPrimitive?.longOrNull ?: return null,
            )
        } catch (_: Exception) {
            null
        }
    }
}

private const val NEWLINE_BYTE: Byte = '\n'.code.toByte()

/** Writes the whole line: a blocking channel may accept it in pieces. */
private fun writeFully(channel: SocketChannel, text: String) {
    val buffer = ByteBuffer.wrap(text.toByteArray(StandardCharsets.UTF_8))
    while (buffer.hasRemaining()) {
        if (channel.write(buffer) == -1) throw IOException("socket closed while writing")
    }
}

/**
 * Reads one `\n`-terminated line from a non-blocking channel, or `null` on
 * timeout / EOF / close. EOF with a partial line still returns the partial
 * line — a daemon always terminates its envelopes, so a fragment means the
 * peer died mid-write and the bytes are still evidence.
 */
private fun readLine(channel: SocketChannel, timeoutMs: Long): String? {
    val pending = ByteArrayOutputStream()
    val buffer = ByteBuffer.allocate(8192)
    Selector.open().use { selector ->
        channel.register(selector, SelectionKey.OP_READ)
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) return null
            if (selector.select(remaining) == 0) return null
            selector.selectedKeys().clear()
            buffer.clear()
            val read = try {
                channel.read(buffer)
            } catch (_: IOException) {
                return null
            }
            if (read == -1) return pending.takeIf { it.size() > 0 }?.toString(StandardCharsets.UTF_8)
            buffer.flip()
            while (buffer.hasRemaining()) {
                val byte = buffer.get()
                if (byte == NEWLINE_BYTE) return pending.toString(StandardCharsets.UTF_8)
                pending.write(byte.toInt())
            }
        }
    }
}
