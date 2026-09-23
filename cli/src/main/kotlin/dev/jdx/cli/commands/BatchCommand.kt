package dev.jdx.cli.commands

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.CoreCliktCommand
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import dev.jdx.cli.BuildInfo
import dev.jdx.cli.effectiveWorkspace
import dev.jdx.cli.render.toJson
import dev.jdx.cli.service.DoctorEnvironment
import dev.jdx.cli.service.DoctorService
import dev.jdx.cli.service.exitCodeFor
import dev.jdx.core.render.JsonEscape
import dev.jdx.core.rpc.RPC_VERSION
import dev.jdx.core.rpc.RpcCommand
import dev.jdx.core.rpc.RpcRequest
import dev.jdx.index.service.JdxService
import dev.jdx.index.service.dispatch
import dev.jdx.index.workspace.FileWorkspaceStore
import dev.jdx.index.workspace.WorkspaceStore
import java.io.BufferedReader
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/**
 * `jdx batch` (T-045; PROPOSAL.md §7.5/§14.5).
 *
 * Reads T-040 [RpcRequest] lines (newline-delimited JSON, the same framing the
 * daemon socket and `POST /v1/batch` speak) from stdin and prints one `--json`
 * envelope per line on stdout — the exact bytes the one-shot CLI would print
 * for the same query, so the T-046 parity proof stays structural (D-056 §1).
 *
 * A thin adapter (D-004): flag parsing and exit codes live here, every answer
 * lives in [JdxService.dispatch] (read queries), [DoctorService] (`doctor`),
 * or the small `version`/`health` internals below. The run is total: a
 * malformed line reads as an exit-6 envelope, a per-query failure rides its
 * own envelope's `ok:false` — neither ever aborts the stream. The process exit
 * code is the maximum query exit code (0 when every line succeeded).
 *
 * `--json` is accepted for script uniformity and ignored: output already is
 * envelopes. Roots resolve once, up front, from the same flags the read
 * commands take (`--jars`, `--src`, `-w`, `--no-jdk`, `--coord`, `--repo`,
 * `--fetch`); a root failure serialises as every line's envelope, keeping the
 * strict 1:1 line mapping the daemon promises (D-057 §3).
 *
 * `version` and `health` answer internally (no roots involved); `doctor` is
 * answered honestly in-process — unlike the daemon/HTTP/MCP adapters, this
 * command runs on the caller's machine, so the report is about the right
 * environment.
 *
 * Exits: 0 every line ok · 1–6 the max per-line exit · 3 empty batch (no
 * requests on stdin) · 6 batch input unreadable.
 */
class BatchCommand(
    private val terminate: (Int) -> Nothing = ::exitProcess,
    private val store: WorkspaceStore = FileWorkspaceStore.system(),
    private val getenv: (String) -> String? = { name -> System.getenv(name) },
    private val discover: ProjectDiscoveryFn? = null,
    private val stdin: () -> BufferedReader = { System.`in`.bufferedReader() },
    private val printer: (String) -> Unit = ::println,
    private val dispatch: (RpcRequest, JdxService.RootsSpec) -> JdxService.ServiceOutcome =
        { request, roots -> JdxService.dispatch(request, roots) },
    private val doctorService: DoctorService = DoctorService(DoctorEnvironment.system()),
    private val appVersion: String = BuildInfo.version,
) : CoreCliktCommand(name = "batch") {
    override fun help(context: Context): String =
        "Answer many queries in one process: read NDJSON RpcRequest lines " +
            "({\"command\":\"members\",\"query\":\"…\",\"params\":{…}}) from stdin, print one " +
            "--json envelope per line on stdout. Per-query failures ride their own " +
            "envelope and never abort the stream; the exit code is the maximum query " +
            "exit code. Output is always envelopes (--json is accepted and ignored). " +
            "Roots resolve once from --jars/--src/-w/--no-jdk/--coord/--repo/--fetch. " +
            "Example: echo '{\"command\":\"show\",\"query\":\"java.util.Map\"}' | jdx batch."

    private val jars by option(
        "--jars",
        help = "Binary roots: jar files, class directories or globs (repeatable). " +
            "Merge in front of the selected workspace's roots.",
    ).multiple()

    private val srcs by option(
        "--src",
        help = "Source-dir roots: directories of .java/.kt files scanned textually " +
            "for whole-word mentions (repeatable). Merge in front of the selected " +
            "workspace's stored srcs.",
    ).multiple()

    private val workspace by option(
        "-w",
        "--workspace",
        help = "Use a named workspace (see jdx ws). Explicit --jars merge in front of it.",
    )

    private val noJdk by option(
        "--no-jdk",
        help = "Do not include the running JDK's stdlib (included by default).",
    ).flag()

    private val coord by option(
        "--coord",
        help = "Maven coordinate root group:artifact:version (repeatable). Resolved from " +
            "~/.gradle/caches and ~/.m2 first; with --fetch, downloaded from Maven repositories " +
            "into ~/.cache/jdx/m2 with checksum verification. Merges in front of the workspace.",
    ).multiple()

    private val repo by option(
        "--repo",
        help = "Maven repository base URL for --coord fetches (repeatable, e.g. " +
            "--repo https://repo.example.com/maven2). Tried in flag order before Maven Central, " +
            "which stays last as the fallback. Must be an http(s) URL.",
    ).multiple()

    private val fetch by option(
        "--fetch",
        help = "Allow downloading --coord artifacts (and their -sources.jar) from Maven " +
            "repositories (Central by default, --repo to add mirrors). Without it, coordinates " +
            "resolve from the local caches only.",
    ).flag()

    private val json by option(
        "--json",
        help = "Accepted and ignored: batch output is always the --json envelope, one per line.",
    ).flag()

    override fun run() {
        if (json) Unit // forced: envelopes already; the flag exists for script uniformity.
        val lines = try {
            stdin().readLines()
        } catch (e: Exception) {
            printer(errorEnvelope("unknown", "", 6, "cannot read batch input: ${e.message}"))
            terminate(6)
            return
        }
        // Blank lines are framing noise, not requests (the HTTP batch skips them too).
        val requests = lines.filter { it.isNotBlank() }
        if (requests.isEmpty()) {
            printer(errorEnvelope("unknown", "", 3, "usage error: empty batch: send one NDJSON RpcRequest per line"))
            terminate(3)
            return
        }
        val roots = when (
            val resolved = ReadCommandSupport.resolveRoots(
                jars,
                noJdk,
                effectiveWorkspace(workspace),
                store,
                getenv,
                discover = discover ?: ReadCommandSupport::discoverProject,
                coords = coord,
                allowFetch = fetch,
                repos = repo,
                srcs = srcs,
            )
        ) {
            is ReadCommandSupport.RootsOrFailure.Ready -> BatchRoots.Ready(resolved.roots)
            is ReadCommandSupport.RootsOrFailure.Failed -> BatchRoots.Failed(resolved.outcome)
        }
        val startNanos = System.nanoTime()
        var served = 0L
        val workspaceName = effectiveWorkspace(workspace)?.trim()?.takeIf { it.isNotEmpty() } ?: "default"

        fun answer(line: String): Pair<String, Int> {
            return try {
                served++
                val request = RpcRequest.decode(line)
                    ?: return errorEnvelope(
                        command = "unknown",
                        query = "",
                        code = 6,
                        message = "malformed request: not a v1 RpcRequest line (T-040); refused, not guessed",
                    ) to 6
                when (request.command) {
                    RpcCommand.VERSION -> okEnvelope(
                        "version",
                        request.query,
                        "{\"version\":" + JsonEscape.quote(appVersion) + "}",
                    ) to 0
                    RpcCommand.HEALTH -> okEnvelope(
                        "health",
                        request.query,
                        healthJson(workspaceName, startNanos, served),
                    ) to 0
                    RpcCommand.DOCTOR -> {
                        val report = doctorService.probe()
                        report.toJson() to exitCodeFor(report)
                    }
                    else -> when (roots) {
                        is BatchRoots.Ready -> {
                            val outcome = dispatch(request, roots.roots)
                            outcome.toJson(request.command.wire) to outcome.exitCode
                        }
                        is BatchRoots.Failed ->
                            roots.outcome.toJson(request.command.wire) to roots.outcome.exitCode
                    }
                }
            } catch (e: Exception) {
                errorEnvelope("unknown", "", 6, "internal error: ${e.message}") to 6
            }
        }

        var maxExit = 0
        for (line in requests) {
            val (text, code) = answer(line)
            printer(text)
            if (code > maxExit) maxExit = code
        }
        if (maxExit != 0) terminate(maxExit)
    }

    /** The `health` result payload: the daemon's shape and key order, so clients read either alike. */
    private fun healthJson(workspaceName: String, startNanos: Long, queryCount: Long): String = buildString {
        val runtime = Runtime.getRuntime()
        append("{\"workspace\":").append(JsonEscape.quote(workspaceName))
        append(",\"uptimeSeconds\":").append(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - startNanos))
        append(",\"queryCount\":").append(queryCount)
        append(",\"indexedArtifacts\":0")
        append(",\"rpcVersion\":").append(RPC_VERSION)
        append(",\"appVersion\":").append(JsonEscape.quote(appVersion))
        append(",\"pid\":").append(ProcessHandle.current().pid())
        append(",\"memoryUsedMb\":").append((runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024))
        append("}")
    }

    private sealed interface BatchRoots {
        data class Ready(val roots: JdxService.RootsSpec) : BatchRoots
        data class Failed(val outcome: JdxService.ServiceOutcome.Failure) : BatchRoots
    }
}

/** `{"jdx":1,"ok":true,"command":…,"query":…,"result":…,"warnings":[],"provenance":[]}`. */
private fun okEnvelope(command: String, query: String, resultJson: String): String = buildString {
    append("{\"jdx\":").append(RPC_VERSION)
    append(",\"ok\":true")
    append(",\"command\":").append(JsonEscape.quote(command))
    append(",\"query\":").append(JsonEscape.quote(query))
    append(",\"result\":").append(resultJson)
    append(",\"warnings\":[],\"provenance\":[]}")
}

/** `{"jdx":1,"ok":false,…,"error":{"code":…,"message":…},"candidates":[],"warnings":[],"provenance":[]}`. */
private fun errorEnvelope(command: String, query: String, code: Int, message: String): String =
    buildString {
        append("{\"jdx\":").append(RPC_VERSION)
        append(",\"ok\":false")
        append(",\"command\":").append(JsonEscape.quote(command))
        append(",\"query\":").append(JsonEscape.quote(query))
        append(",\"error\":{\"code\":").append(code)
        append(",\"message\":").append(JsonEscape.quote(message)).append("}")
        append(",\"candidates\":[],\"warnings\":[],\"provenance\":[]}")
    }
