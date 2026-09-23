package dev.jdx.server

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.jdx.core.render.JsonEscape
import dev.jdx.core.rpc.RPC_VERSION
import dev.jdx.core.rpc.RpcCommand
import dev.jdx.core.rpc.RpcRequest
import dev.jdx.index.service.DaemonRoots
import dev.jdx.index.service.JdxService
import dev.jdx.index.service.daemonRoots
import dev.jdx.index.service.dispatch
import dev.jdx.index.workspace.FileWorkspaceStore
import dev.jdx.index.workspace.WorkspaceStore
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/** The default bind: localhost only (PROPOSAL.md §17 — network stays opt-in, local by default). */
public const val DEFAULT_HTTP_BIND: String = "127.0.0.1"

/** The default port `jdx serve` listens on (PROPOSAL.md §7.5). */
public const val DEFAULT_HTTP_PORT: Int = 7070

/**
 * The HTTP/JSON server (T-044; PROPOSAL.md §14.4). JDK builtin
 * `com.sun.net.httpserver` only — zero new dependencies.
 *
 * One stored workspace served per request, resolved through the same
 * `JdxService.daemonRoots` seam the unix-socket daemon (T-082) and MCP
 * (T-043) use, so `ws create` while the server runs is picked up and a
 * missing workspace reads as exit 4 naming the fix. Answers are
 * `ServiceOutcome.toJson(wire)` verbatim — the exact `--json` envelope —
 * so HTTP/CLI parity (T-046) stays structural (D-056 §1); only the HTTP
 * status code is new, mapped from the envelope's exit code (D-061 §3).
 *
 * Routes (all under `/v1/`):
 * - `GET /v1/<command>?query=…&<param>=…` — one read query. `query` is the
 *   subject (empty where the command allows it: `ls`, `tree`); every other
 *   key except `workspace` forwards verbatim as a D-058 wire param;
 *   `workspace` overrides the server default for that call (the MCP
 *   per-call override, D-060 §2). `version` and `health` answer internally;
 *   `doctor` is refused honestly (it reports the server machine's
 *   environment and `DoctorService` lives in `:cli`).
 * - `POST /v1/batch[?workspace=…]` — NDJSON `RpcRequest` lines in (the
 *   T-040 framing, shared with `jdx batch` stdin), one envelope line per
 *   request out, never aborting the stream on a per-query failure.
 * - `GET /v1/health` — the daemon's health shape and key order, so clients
 *   read either server alike; `indexedArtifacts` stays 0 (live roots, no
 *   persistent index — the D-043 precedent).
 *
 * Separate port/socket from the daemon (T-041): the two do not interfere.
 * Every handler is total — hostile paths, methods, query strings and bodies
 * read as exit-coded envelopes, never a throw, never a dropped connection.
 */
public class JdxHttpServer(
    public val workspace: String,
    public val bind: String = DEFAULT_HTTP_BIND,
    public val port: Int = DEFAULT_HTTP_PORT,
    public val appVersion: String,
    private val store: WorkspaceStore = FileWorkspaceStore.system(),
    private val service: JdxService = JdxService,
) : AutoCloseable {
    private val startNanos: Long = System.nanoTime()
    private val queryCount: AtomicLong = AtomicLong(0)
    private val stoppedLatch: CountDownLatch = CountDownLatch(1)
    private var server: HttpServer? = null
    private var pool: java.util.concurrent.ExecutorService? = null

    /** Binds and serves in the background. Throws when the bind fails (the CLI maps it to exit 6). */
    public fun start() {
        val pool = Executors.newVirtualThreadPerTaskExecutor()
        val bound = HttpServer.create(InetSocketAddress(bind, port), 0)
        bound.executor = pool
        bound.createContext("/v1/health") { exchange -> answer(exchange) { healthAnswer(queryOf(exchange).orEmpty()) } }
        bound.createContext("/v1/version") { exchange -> answer(exchange) { versionAnswer(queryOf(exchange).orEmpty()) } }
        bound.createContext("/v1/batch") { exchange -> answer(exchange) { batchAnswer(exchange) } }
        bound.createContext("/v1/") { exchange -> answer(exchange) { queryAnswer(exchange) } }
        bound.createContext("/") { exchange ->
            answer(exchange) { HttpAnswer(404, errorEnvelope("unknown", "", 6, "unknown path: serve GET /v1/<command>")) }
        }
        bound.start()
        this.server = bound
        this.pool = pool
    }

    /** The bound address (resolves the ephemeral port when [port] was 0 — tests bind that way). */
    public fun localAddress(): InetSocketAddress = server?.address
        ?: InetSocketAddress(bind, port)

    /** Blocks until [stop] runs. */
    public fun join() {
        stoppedLatch.await()
    }

    /** Idempotent: stops the listener and the thread pool. */
    public fun stop() {
        runCatching { server?.stop(0) }
        runCatching { pool?.shutdownNow() }
        server = null
        stoppedLatch.countDown()
    }

    override fun close(): Unit = stop()

    /** Current numbers for `health`. Monotonic: uptime and query count never decrease. */
    public fun snapshot(workspaceName: String): DaemonStatusSnapshot {
        val runtime = Runtime.getRuntime()
        return DaemonStatusSnapshot(
            workspace = workspaceName,
            uptimeSeconds = java.util.concurrent.TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - startNanos),
            queryCount = queryCount.get(),
            indexedArtifacts = 0,
            rpcVersion = RPC_VERSION,
            appVersion = appVersion,
            pid = ProcessHandle.current().pid(),
            memoryUsedMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024),
        )
    }

    private data class HttpAnswer(val status: Int, val body: String, val contentType: String = "application/json")

    /** Runs one handler totally: any throw becomes a 500 envelope, never a dropped connection. */
    private fun answer(exchange: HttpExchange, produce: () -> HttpAnswer) {
        val result = try {
            produce()
        } catch (e: Exception) {
            HttpAnswer(500, errorEnvelope("unknown", "", 6, "http handler failed: ${e.message}"))
        }
        try {
            val bytes = result.body.toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.set("Content-Type", "${result.contentType}; charset=utf-8")
            exchange.sendResponseHeaders(result.status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        } catch (_: Exception) {
            // The peer went away mid-write: nothing left to do.
        } finally {
            exchange.close()
        }
    }

    private fun versionAnswer(params: Map<String, String>): HttpAnswer {
        queryCount.incrementAndGet()
        return HttpAnswer(
            200,
            okEnvelope("version", params["query"].orEmpty(), "{\"version\":" + JsonEscape.quote(appVersion) + "}"),
        )
    }

    private fun healthAnswer(params: Map<String, String>): HttpAnswer {
        queryCount.incrementAndGet()
        val effective = effectiveWorkspace(params)
        return HttpAnswer(200, okEnvelope("health", params["query"].orEmpty(), healthResultJson(snapshot(effective))))
    }

    private fun queryAnswer(exchange: HttpExchange): HttpAnswer {
        if (exchange.requestMethod != "GET") {
            return HttpAnswer(
                405,
                errorEnvelope("unknown", "", 3, "usage error: query paths take GET (batch takes POST /v1/batch)"),
            )
        }
        val segment = exchange.requestURI.path.removePrefix("/v1/").trimEnd('/')
        // A trailing-slash or nested path is not a command: refuse, never guess (D-056 §6).
        if (segment.isEmpty() || '/' in segment) {
            return HttpAnswer(404, errorEnvelope("unknown", "", 6, "unknown path: serve GET /v1/<command>"))
        }
        val command = RpcCommand.fromWire(segment)
            ?: return HttpAnswer(404, errorEnvelope("unknown", "", 6, "unknown command '$segment'"))
        val params = queryOf(exchange) ?: return HttpAnswer(
            400,
            errorEnvelope("unknown", "", 3, "usage error: the query string is not valid form encoding"),
        )
        queryCount.incrementAndGet()
        return when (command) {
            RpcCommand.VERSION -> versionAnswer(params)
            RpcCommand.HEALTH -> healthAnswer(params)
            RpcCommand.DOCTOR -> HttpAnswer(
                statusForExit(6),
                errorEnvelope(
                    command = command.wire,
                    query = params["query"].orEmpty(),
                    code = 6,
                    message = "http cannot answer 'doctor': it reports this machine's environment — " +
                        "run `jdx doctor` in a shell",
                ),
            )
            else -> {
                val query = params["query"].orEmpty()
                val outcome = when (val resolved = service.daemonRoots(effectiveWorkspace(params), query, store)) {
                    is DaemonRoots.Ready ->
                        service.dispatch(RpcRequest(command, query, params - "query" - "workspace"), resolved.roots)
                    is DaemonRoots.Failed -> resolved.outcome
                }
                HttpAnswer(statusForExit(outcome.exitCode), outcome.toJson(command.wire))
            }
        }
    }

    private fun batchAnswer(exchange: HttpExchange): HttpAnswer {
        if (exchange.requestMethod != "POST") {
            return HttpAnswer(
                405,
                errorEnvelope("unknown", "", 3, "usage error: /v1/batch takes POST with NDJSON RpcRequest lines"),
            )
        }
        val params = queryOf(exchange) ?: return HttpAnswer(
            400,
            errorEnvelope("unknown", "", 3, "usage error: the query string is not valid form encoding"),
        )
        val body = try {
            exchange.requestBody.use { it.readAllBytes().toString(StandardCharsets.UTF_8) }
        } catch (e: Exception) {
            return HttpAnswer(500, errorEnvelope("unknown", "", 6, "http handler failed: ${e.message}"))
        }
        val lines = body.split('\n').map { it.trimEnd('\r') }.filter { it.isNotBlank() }
        if (lines.isEmpty()) {
            return HttpAnswer(
                400,
                errorEnvelope("unknown", "", 3, "usage error: empty batch: send one NDJSON RpcRequest per line"),
            )
        }
        // Per-query failures ride their envelope, never abort the stream (the T-045 rule);
        // the batch itself is always 200 once it held at least one decodable-or-not line.
        val out = lines.joinToString("\n") { line ->
            queryCount.incrementAndGet()
            val request = RpcRequest.decode(line)
                ?: return@joinToString errorEnvelope(
                    command = "unknown",
                    query = "",
                    code = 6,
                    message = "malformed request: not a v1 RpcRequest line (T-040); refused, not guessed",
                )
            when (request.command) {
                RpcCommand.VERSION ->
                    okEnvelope("version", request.query, "{\"version\":" + JsonEscape.quote(appVersion) + "}")
                RpcCommand.HEALTH ->
                    okEnvelope("health", request.query, healthResultJson(snapshot(workspace)))
                RpcCommand.DOCTOR -> errorEnvelope(
                    command = request.command.wire,
                    query = request.query,
                    code = 6,
                    message = "http cannot answer 'doctor': it reports this machine's environment — " +
                        "run `jdx doctor` in a shell",
                )
                else -> when (val resolved = service.daemonRoots(workspace, request.query, store)) {
                    is DaemonRoots.Ready -> service.dispatch(request, resolved.roots).toJson(request.command.wire)
                    is DaemonRoots.Failed -> resolved.outcome.toJson(request.command.wire)
                }
            }
        }
        return HttpAnswer(200, out, "application/x-ndjson")
    }

    /** The workspace this call answers from: the `workspace` argument wins over the server default. */
    private fun effectiveWorkspace(params: Map<String, String>): String =
        params["workspace"]?.trim()?.takeIf { it.isNotEmpty() } ?: workspace
}

/**
 * Maps an envelope exit code to its HTTP status (D-061 §3): the body stays the
 * exact `--json` envelope, so T-046 parity reads bodies only. Public so the
 * CLI never re-derives it and tests pin it in one place.
 */
public fun statusForExit(exitCode: Int): Int = when (exitCode) {
    0 -> 200
    1 -> 404
    2 -> 300
    3 -> 400
    4 -> 404
    5 -> 502
    else -> 500
}

/**
 * Parses a form-encoded query string into a last-wins map, or `null` when the
 * encoding itself is hostile. Never throws. `+` reads as a space (form
 * encoding); a bare key reads as an empty value.
 */
internal fun parseQueryString(raw: String?): Map<String, String>? {
    if (raw.isNullOrEmpty()) return emptyMap()
    return try {
        val entries = LinkedHashMap<String, String>()
        for (pair in raw.split('&')) {
            if (pair.isEmpty()) continue
            val equals = pair.indexOf('=')
            val name = java.net.URLDecoder.decode(if (equals < 0) pair else pair.substring(0, equals), StandardCharsets.UTF_8)
            val value = if (equals < 0) "" else java.net.URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8)
            entries[name] = value
        }
        entries
    } catch (_: Exception) {
        null
    }
}

private fun queryOf(exchange: HttpExchange): Map<String, String>? = parseQueryString(exchange.requestURI.rawQuery)
