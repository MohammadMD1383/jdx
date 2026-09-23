package dev.jdx.mcp

import dev.jdx.core.render.JsonEscape
import dev.jdx.core.rpc.RPC_VERSION
import dev.jdx.core.rpc.RpcCommand
import dev.jdx.index.service.DaemonRoots
import dev.jdx.index.service.JdxService
import dev.jdx.index.service.daemonRoots
import dev.jdx.index.service.dispatch
import dev.jdx.index.workspace.FileWorkspaceStore
import dev.jdx.index.workspace.WorkspaceStore
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * One MCP session (T-043): the per-session daemon PROPOSAL.md §14.2 promises.
 *
 * A single [JdxService] answers every tool call in memory for the server's
 * lifetime, so repeated queries are instant — no cold JVM, no re-read roots.
 * Roots resolve per call from the stored workspace (the tool's `workspace`
 * argument, else the server's [workspace]), exactly like the unix-socket
 * daemon (T-041/T-082): `ws create` while the server runs is picked up, and a
 * missing workspace reads as exit 4 naming the fix rather than a silent
 * fallback to other roots.
 *
 * `version` and `health` are answered internally in the daemon's envelope
 * shapes (same key order, so clients read either server alike);
 * `indexedArtifacts` stays 0 by design — live roots, no persistent index
 * (the D-043 precedent the daemon documents). `doctor` is refused honestly:
 * it reports the caller's machine environment and `DoctorService` lives in
 * `:cli`, which this module cannot depend on (`:cli` depends on `:mcp`).
 *
 * Every entry point is total: hostile arguments read as exit-coded envelopes,
 * never throws.
 */
public class McpSession(
    public val workspace: String = "default",
    public val appVersion: String = "dev",
    private val store: WorkspaceStore = FileWorkspaceStore.system(),
    private val service: JdxService = JdxService,
) {
    private val startNanos: Long = System.nanoTime()
    private val queriesServed: AtomicLong = AtomicLong(0)
    private val lenientJson: Json = Json { ignoreUnknownKeys = true }

    /** One answered tool call: the envelope text plus whether the call failed. */
    public data class McpResult(val text: String, val isError: Boolean)

    /**
     * Answers one `jdx_<wire>` tool call. The returned text is the `--json`
     * envelope verbatim — byte-identical to what the one-shot CLI prints for
     * the same query (the T-046 parity proof feeds on this) — and [McpResult.isError]
     * carries the exit-code branch signal (`ok:false` reads as `true`).
     */
    public fun callTool(name: String, args: JsonObject): McpResult {
        return try {
            queriesServed.incrementAndGet()
            answer(name, args)
        } catch (e: Exception) {
            McpResult(errorEnvelope("health", "", 6, "internal error: ${e.message}"), true)
        }
    }

    private fun answer(name: String, args: JsonObject): McpResult {
        val tool = toolForName(name)
            ?: return McpResult(errorEnvelope(name, "", 6, "unknown tool '$name'"), true)
        val split = splitArgs(args)
        if (split.structured.isNotEmpty()) {
            val param = split.structured.sorted().first()
            return fail(tool, split.query, 3, "usage error: --$param must be a string, number or boolean")
        }
        return when (tool.command) {
            RpcCommand.VERSION -> ok(
                tool, split.query,
                "{\"version\":" + JsonEscape.quote(appVersion) + "}",
            )
            RpcCommand.HEALTH -> ok(tool, split.query, healthJson(effectiveWorkspace(split)))
            RpcCommand.DOCTOR -> fail(
                tool, split.query, 6,
                "mcp cannot answer 'doctor': it reports this machine's environment — " +
                    "run `jdx doctor` in a shell",
            )
            else -> {
                val request = tool.requestFor(split.query, split.params)
                when (val resolved = service.daemonRoots(effectiveWorkspace(split), request.query, store)) {
                    is DaemonRoots.Ready ->
                        of(tool, service.dispatch(request, resolved.roots).toJson(request.command.wire))
                    is DaemonRoots.Failed ->
                        of(tool, resolved.outcome.toJson(request.command.wire))
                }
            }
        }
    }

    /** The workspace this call answers from: the tool argument wins over the server default. */
    private fun effectiveWorkspace(split: SplitArgs): String =
        split.workspace?.trim()?.takeIf { it.isNotEmpty() } ?: workspace

    private fun ok(tool: McpToolDefinition, query: String, resultJson: String): McpResult =
        McpResult(okEnvelope(tool.command.wire, query, resultJson), false)

    private fun fail(tool: McpToolDefinition, query: String, code: Int, message: String): McpResult =
        McpResult(errorEnvelope(tool.command.wire, query, code, message), true)

    /** Parses the exit branch out of our own envelope: `ok:true` reads as success. */
    private fun of(tool: McpToolDefinition, line: String): McpResult {
        val ok = try {
            lenientJson.parseToJsonElement(line).jsonObject["ok"]?.jsonPrimitive?.booleanOrNull == true
        } catch (_: Exception) {
            false
        }
        return McpResult(line, !ok)
    }

    /** The `health` result payload: the daemon's shape and key order, so clients read either alike. */
    private fun healthJson(workspaceName: String): String = buildString {
        append("{\"workspace\":").append(JsonEscape.quote(workspaceName))
        append(",\"uptimeSeconds\":").append(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - startNanos))
        append(",\"queryCount\":").append(queriesServed.get())
        append(",\"indexedArtifacts\":0")
        append(",\"rpcVersion\":").append(RPC_VERSION)
        append(",\"appVersion\":").append(JsonEscape.quote(appVersion))
        append(",\"pid\":").append(ProcessHandle.current().pid())
        append(",\"memoryUsedMb\":").append(Runtime.getRuntime().let { (it.totalMemory() - it.freeMemory()) / (1024 * 1024) })
        append("}")
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
