package dev.jdx.cli

import com.github.ajalt.clikt.core.CoreCliktCommand
import dev.jdx.core.rpc.RpcCommand
import dev.jdx.core.rpc.RpcRequest
import dev.jdx.index.workspace.WorkspaceStore
import dev.jdx.server.DaemonPaths
import dev.jdx.server.DaemonProbe
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The transparent CLI daemon client (T-042; PROPOSAL.md §14.1).
 *
 * One-shot CLI forwards its T-040 request to a running daemon when the socket
 * answers, else runs in-process. Failure to reach the daemon degrades to
 * in-process, never an error. `--no-daemon` forces in-process.
 *
 * Thin by rule (D-004): this file owns transport (socket location, round-trip,
 * envelope exit-code parsing) plus the flag→param builders (flag names without
 * dashes, text values — the D-058 contract, so flags forward verbatim).
 * Behaviour stays in `JdxService` on both sides.
 *
 * v1 scope (D-059, lifted by T-086): `--json` queries go warm verbatim and
 * text queries go warm via the server-rendered `"text"` envelope field
 * (plain, no ANSI — the daemon has no TTY). A missing/unreachable daemon, a
 * usage error, explicit root overrides, or a daemon that answers without the
 * `text` field (pre-T-086) all read as "run in-process".
 */
public object DaemonClient {
    private val lenientJson: Json = Json { ignoreUnknownKeys = true }

    /**
     * The answer when the daemon served the query: the raw envelope line to
     * print verbatim (byte-identical to the in-process `--json` bytes, D-056
     * §1) plus the process exit code parsed out of it.
     */
    public data class WarmHit(val line: String, val exitCode: Int)

    /**
     * The answer when the daemon served a text query: the server-rendered
     * plain text to print verbatim plus the process exit code parsed out of
     * the same envelope.
     */
    public data class WarmTextHit(val text: String, val exitCode: Int)

    /**
     * Decides whether this query may leave the process. Every clause is a
     * correctness guard, not an optimisation:
     *
     * - `noDaemon` — the user forced in-process.
     * - explicit roots (`jars`, `coords`, `repos`, `fetch`, `srcs`, `noJdk`) —
     *   the daemon serves its stored workspace only (D-058 §3); anything the
     *   caller added or removed would answer about different roots.
     * - no named workspace — auto-discovered project roots have no daemon
     *   socket; only `-w`/`JDX_WORKSPACE`/`jdx ws use` selections do.
     * - no runtime dir — without `XDG_RUNTIME_DIR` there is no socket path.
     *
     * Both `--json` and text may go warm (T-086): the JSON path prints the
     * envelope verbatim, the text path prints the server-rendered `"text"`
     * field. Presentation-only flags (`brief`, `warmMaxLines`) ride the
     * request as `warmBrief`/`warmMaxLines` params.
     */
    public fun shouldAttempt(
        noDaemon: Boolean,
        json: Boolean,
        roots: WarmRoots,
        workspaceName: String?,
        runtimeDir: Path?,
    ): Boolean {
        if (noDaemon) return false
        if (roots.hasExplicitRoots()) return false
        if (workspaceName.isNullOrBlank()) return false
        if (runtimeDir == null) return false
        return true
    }

    /**
     * Forwards [request] to the daemon at [socketPath], or returns `null` when
     * the query must run in-process instead. `null` covers every failure: no
     * daemon, version mismatch, truncated write, an envelope for a different
     * command/query — never an error, never a throw.
     */
    public fun tryWarm(
        request: RpcRequest,
        socketPath: Path,
        roundTrip: DaemonRoundTrip = defaultRoundTrip,
    ): WarmHit? {
        val line = runCatching { roundTrip(socketPath, request) }.getOrNull() ?: return null
        val code = parseExitCode(line, request.command.wire) ?: return null
        return WarmHit(line, code)
    }

    /**
     * Prints the warm envelope and maps its exit code, returning `true` when
     * the caller must stop (warm served). Returns `false` when the caller must
     * run in-process instead. Never throws for any input — a refusal here is
     * routine, not an error.
     *
     * When [json] is true the envelope line prints verbatim; otherwise the
     * server-rendered `"text"` field prints (T-086). [brief] and
     * [warmMaxLines] are the text-only presentation flags (`members`/`outline`
     * `--brief`/`--max-lines`); every other text-affecting flag already rides
     * the request as a query param.
     */
    public fun serveWarmIfReady(
        request: RpcRequest,
        flagWorkspace: String?,
        roots: WarmRoots,
        noDaemon: Boolean,
        json: Boolean,
        terminate: (Int) -> Nothing,
        store: WorkspaceStore,
        getenv: (String) -> String?,
        runtimeDir: Path?,
        roundTrip: DaemonRoundTrip = defaultRoundTrip,
        printer: (String) -> Unit = ::println,
        brief: Boolean = false,
        warmMaxLines: Int? = null,
    ): Boolean {
        val workspaceName = workspaceNameForDaemon(flagWorkspace, getenv, store)
        if (!shouldAttempt(noDaemon, json, roots, workspaceName, runtimeDir)) return false
        val socket = DaemonPaths.socketPath(runtimeDir!!, workspaceName!!.trim())
        if (json) {
            val hit = tryWarm(request, socket, roundTrip) ?: return false
            printer(hit.line)
            if (hit.exitCode != 0) terminate(hit.exitCode)
            return true
        }
        val textRequest = request.copy(
            params = request.params + buildMap {
                put("warmText", "true")
                if (brief) put("warmBrief", "true")
                if (warmMaxLines != null) put("warmMaxLines", warmMaxLines.toString())
            },
        )
        val hit = tryWarmText(textRequest, socket, roundTrip) ?: return false
        printer(hit.text)
        if (hit.exitCode != 0) terminate(hit.exitCode)
        return true
    }

    /**
     * Forwards a text [request] (carrying the `warmText` params) to the daemon
     * at [socketPath], or returns `null` when the query must run in-process
     * instead. `null` covers every failure: no daemon, a daemon that answers
     * without the `text` field (pre-T-086), version mismatch, truncated write,
     * an envelope for a different command — never an error, never a throw.
     */
    public fun tryWarmText(
        request: RpcRequest,
        socketPath: Path,
        roundTrip: DaemonRoundTrip = defaultRoundTrip,
    ): WarmTextHit? {
        val line = runCatching { roundTrip(socketPath, request) }.getOrNull() ?: return null
        val code = parseExitCode(line, request.command.wire) ?: return null
        val text = parseWarmText(line) ?: return null
        return WarmTextHit(text, code)
    }

    /**
     * Resolves which daemon socket a query belongs to: `-w` beats
     * `JDX_WORKSPACE` beats `jdx ws use` (the PROPOSAL.md §13 precedence the
     * resolver itself uses). Blank selections read as absent; any failure to
     * read the environment or the store reads as "no named workspace" (the
     * auto-discovery path), never a throw.
     */
    public fun workspaceNameForDaemon(
        flagWorkspace: String?,
        getenv: (String) -> String?,
        store: WorkspaceStore,
    ): String? {
        flagWorkspace?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        try {
            getenv("JDX_WORKSPACE")?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        } catch (_: Exception) {
            // A hostile SecurityManager: fall through to the stored workspace.
        }
        return try {
            store.activeName()?.trim()?.takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Reads the process exit code out of a warm envelope line: `0` for
     * `ok:true`, the `error.code` for `ok:false`. Returns `null` — degrade to
     * in-process — when the line is not this command's envelope: unparseable,
     * a different `command`, a non-v1 `jdx` marker, or a missing exit signal.
     * Never throws, on any input.
     *
     * The envelope's `query` is deliberately unchecked: the service normalises
     * it (`ls`/`tree` answer an empty query as `"*"`), so byte-comparing it
     * would refuse honest answers. Pairing is the transport's job — the
     * strict 1:1 line mapping (D-057 §3) means the response to our line is
     * ours; the command check catches a handler answering a different command.
     */
    public fun parseExitCode(line: String, expectedCommand: String): Int? {
        return try {
            val root = lenientJson.parseToJsonElement(line).jsonObject
            root["jdx"]?.jsonPrimitive?.intOrNull?.let { if (it != 1) return null }
            if (root["command"]?.jsonPrimitive?.content != expectedCommand) return null
            val ok = root["ok"]?.jsonPrimitive?.booleanOrNull ?: return null
            if (ok) {
                0
            } else {
                root["error"]?.jsonObject?.get("code")?.jsonPrimitive?.intOrNull
                    ?.takeIf { it in 1..6 }
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Reads the server-rendered plain text out of a warm envelope line.
     * Returns `null` — degrade to in-process — when the line carries no
     * `"text"` string (a pre-T-086 daemon, or a hand-written envelope).
     * Never throws, on any input.
     */
    public fun parseWarmText(line: String): String? {
        return try {
            val primitive = lenientJson.parseToJsonElement(line).jsonObject["text"]?.jsonPrimitive ?: return null
            if (!primitive.isString) return null
            primitive.content
        } catch (_: Exception) {
            null
        }
    }

    // -- request builders (one per read command; flags forwarded verbatim, D-058) --

    public fun showRequest(ref: String): RpcRequest =
        RpcRequest(RpcCommand.SHOW, ref)

    public fun membersRequest(
        ref: String,
        kind: String = "all",
        access: String? = null,
        staticOnly: Boolean = false,
        instanceOnly: Boolean = false,
        from: String? = null,
        grep: String? = null,
        includeSynthetic: Boolean = false,
        limit: Int = 50,
        withDoc: Boolean = false,
        sort: String = "kind",
        view: String = "kotlin",
        declared: Boolean = false,
    ): RpcRequest = RpcRequest(
        command = RpcCommand.MEMBERS,
        query = ref,
        params = buildMap {
            put("kind", kind.lowercase())
            if (access != null) put("access", access.lowercase())
            put("static", staticOnly.toText())
            put("instance", instanceOnly.toText())
            if (from != null) put("from", from)
            if (grep != null) put("grep", grep)
            put("includeSynthetic", includeSynthetic.toText())
            put("limit", limit.toString())
            put("withDoc", withDoc.toText())
            put("sort", sort.lowercase())
            put("view", view.lowercase())
            put("declared", declared.toText())
        },
    )

    public fun outlineRequest(
        ref: String,
        kind: String = "all",
        access: String? = null,
        staticOnly: Boolean = false,
        instanceOnly: Boolean = false,
        from: String? = null,
        grep: String? = null,
        includeSynthetic: Boolean = false,
        limit: Int = 50,
        withDoc: Boolean = false,
        sort: String = "kind",
        view: String = "kotlin",
    ): RpcRequest = RpcRequest(
        command = RpcCommand.OUTLINE,
        query = ref,
        params = buildMap {
            put("kind", kind.lowercase())
            if (access != null) put("access", access.lowercase())
            put("static", staticOnly.toText())
            put("instance", instanceOnly.toText())
            if (from != null) put("from", from)
            if (grep != null) put("grep", grep)
            put("includeSynthetic", includeSynthetic.toText())
            put("limit", limit.toString())
            put("withDoc", withDoc.toText())
            put("sort", sort.lowercase())
            put("view", view.lowercase())
        },
    )

    public fun bodyRequest(
        ref: String,
        context: Int = 0,
        lineNumbers: Boolean = false,
        maxLines: Int = 200,
        withSignature: Boolean = false,
        withDoc: Boolean = false,
        engine: String? = null,
    ): RpcRequest = RpcRequest(
        command = RpcCommand.BODY,
        query = ref,
        params = buildMap {
            put("context", context.toString())
            put("lineNumbers", lineNumbers.toText())
            put("maxLines", maxLines.toString())
            put("withSignature", withSignature.toText())
            put("withDoc", withDoc.toText())
            if (engine != null) put("engine", engine.lowercase())
        },
    )

    public fun sourceRequest(
        ref: String,
        lines: String? = null,
        around: String? = null,
        context: Int = 0,
        lineNumbers: Boolean = false,
        maxLines: Int = 200,
        engine: String? = null,
    ): RpcRequest = RpcRequest(
        command = RpcCommand.SOURCE,
        query = ref,
        params = buildMap {
            if (lines != null) put("lines", lines)
            if (around != null) put("around", around)
            put("context", context.toString())
            put("lineNumbers", lineNumbers.toText())
            put("maxLines", maxLines.toString())
            if (engine != null) put("engine", engine.lowercase())
        },
    )

    public fun signatureRequest(
        ref: String,
        includeSynthetic: Boolean = false,
        limit: Int = 50,
        view: String = "kotlin",
    ): RpcRequest = RpcRequest(
        command = RpcCommand.SIGNATURE,
        query = ref,
        params = mapOf(
            "includeSynthetic" to includeSynthetic.toText(),
            "limit" to limit.toString(),
            "view" to view.lowercase(),
        ),
    )

    public fun docRequest(
        ref: String,
        inherited: Boolean = false,
        noInherited: Boolean = false,
        raw: Boolean = false,
        maxLines: Int = 200,
    ): RpcRequest = RpcRequest(
        command = RpcCommand.DOC,
        query = ref,
        params = mapOf(
            "inherited" to inherited.toText(),
            "no-inherited" to noInherited.toText(),
            "raw" to raw.toText(),
            "maxLines" to maxLines.toString(),
        ),
    )

    public fun searchRequest(
        pattern: String,
        kind: String = "all",
        regex: Boolean = false,
        fuzzy: Boolean = false,
        inArtifact: String? = null,
        inPackage: String? = null,
        limit: Int = 50,
    ): RpcRequest = RpcRequest(
        command = RpcCommand.SEARCH,
        query = pattern,
        params = buildMap {
            put("kind", kind.lowercase())
            put("regex", regex.toText())
            put("fuzzy", fuzzy.toText())
            if (inArtifact != null) put("in", inArtifact)
            if (inPackage != null) put("package", inPackage)
            put("limit", limit.toString())
        },
    )

    public fun resolveRequest(name: String, limit: Int = 50): RpcRequest =
        RpcRequest(RpcCommand.RESOLVE, name, mapOf("limit" to limit.toString()))

    public fun lsRequest(packageGlob: String?, limit: Int = 50): RpcRequest =
        RpcRequest(RpcCommand.LS, packageGlob ?: "", mapOf("limit" to limit.toString()))

    public fun treeRequest(
        artifactGlob: String?,
        depth: Int = 8,
        counts: Boolean = false,
        limit: Int = 50,
    ): RpcRequest = RpcRequest(
        command = RpcCommand.TREE,
        query = artifactGlob ?: "",
        params = mapOf(
            "depth" to depth.toString(),
            "counts" to counts.toText(),
            "limit" to limit.toString(),
        ),
    )

    public fun usagesRequest(
        ref: String,
        kind: String = "all",
        inArtifact: String? = null,
        exclude: String? = null,
        limit: Int = 50,
        context: Int = 0,
    ): RpcRequest = RpcRequest(
        command = RpcCommand.USAGES,
        query = ref,
        params = buildMap {
            put("kind", kind.lowercase())
            if (inArtifact != null) put("in", inArtifact)
            if (exclude != null) put("exclude", exclude)
            put("limit", limit.toString())
            put("context", context.toString())
        },
    )

    public fun hierarchyRequest(
        ref: String,
        up: Boolean = false,
        down: Boolean = false,
        direct: Boolean = false,
        depth: Int = Int.MAX_VALUE,
        inArtifact: String? = null,
        exclude: String? = null,
        limit: Int = 50,
    ): RpcRequest = RpcRequest(
        command = RpcCommand.HIERARCHY,
        query = ref,
        params = buildMap {
            put("up", up.toText())
            put("down", down.toText())
            put("direct", direct.toText())
            put("depth", depth.toString())
            if (inArtifact != null) put("in", inArtifact)
            if (exclude != null) put("exclude", exclude)
            put("limit", limit.toString())
        },
    )

    public fun implementorsRequest(
        ref: String,
        direct: Boolean = false,
        depth: Int = Int.MAX_VALUE,
        inArtifact: String? = null,
        exclude: String? = null,
        limit: Int = 50,
    ): RpcRequest = RpcRequest(
        command = RpcCommand.IMPLEMENTORS,
        query = ref,
        params = buildMap {
            put("direct", direct.toText())
            put("depth", depth.toString())
            if (inArtifact != null) put("in", inArtifact)
            if (exclude != null) put("exclude", exclude)
            put("limit", limit.toString())
        },
    )

    public fun callersRequest(
        ref: String,
        depth: Int = 1,
        inArtifact: String? = null,
        exclude: String? = null,
        limit: Int = 50,
    ): RpcRequest = RpcRequest(
        command = RpcCommand.CALLERS,
        query = ref,
        params = buildMap {
            put("depth", depth.toString())
            if (inArtifact != null) put("in", inArtifact)
            if (exclude != null) put("exclude", exclude)
            put("limit", limit.toString())
        },
    )

    public fun callsRequest(
        ref: String,
        depth: Int = 1,
        inArtifact: String? = null,
        exclude: String? = null,
        externalOnly: Boolean = false,
        limit: Int = 50,
    ): RpcRequest = RpcRequest(
        command = RpcCommand.CALLS,
        query = ref,
        params = buildMap {
            put("depth", depth.toString())
            if (inArtifact != null) put("in", inArtifact)
            if (exclude != null) put("exclude", exclude)
            put("externalOnly", externalOnly.toText())
            put("limit", limit.toString())
        },
    )

    public fun samplesRequest(
        ref: String,
        limit: Int = 3,
        inArtifact: String? = null,
        exclude: String? = null,
        preferSources: Boolean = false,
    ): RpcRequest = RpcRequest(
        command = RpcCommand.SAMPLES,
        query = ref,
        params = buildMap {
            put("limit", limit.toString())
            if (inArtifact != null) put("in", inArtifact)
            if (exclude != null) put("exclude", exclude)
            put("preferSources", preferSources.toText())
        },
    )

    private fun Boolean.toText(): String = if (this) "true" else "false"
}

/** The root flags a warm attempt must stay clear of: any of these means daemon roots would differ. */
public data class WarmRoots(
    val jars: List<String> = emptyList(),
    val coords: List<String> = emptyList(),
    val repos: List<String> = emptyList(),
    val fetch: Boolean = false,
    val srcs: List<String> = emptyList(),
    val noJdk: Boolean = false,
) {
    internal fun hasExplicitRoots(): Boolean =
        jars.isNotEmpty() || coords.isNotEmpty() || repos.isNotEmpty() ||
            fetch || srcs.isNotEmpty() || noJdk
}

/** One request in, one envelope line out; `null` on any failure (never throws). */
public typealias DaemonRoundTrip = (socket: Path, request: RpcRequest) -> String?

/** The production transport: one framed request over the unix socket. */
public val defaultRoundTrip: DaemonRoundTrip = { socket, request ->
    DaemonProbe.roundTrip(socket, request)
}

/**
 * Effective `--no-daemon`: the command's own flag or the root's (`jdx
 * --no-daemon members ...` and `jdx members --no-daemon ...` are equivalent,
 * mirroring [effectiveJson]).
 */
internal fun CoreCliktCommand.effectiveNoDaemon(ownNoDaemon: Boolean): Boolean =
    ownNoDaemon || rootCommand()?.noDaemon == true
