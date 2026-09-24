package dev.jdx.server

import dev.jdx.core.rpc.RpcCommand
import dev.jdx.index.service.DaemonRoots
import dev.jdx.index.service.JdxService
import dev.jdx.index.service.daemonRoots
import dev.jdx.index.service.dispatch
import dev.jdx.index.service.dispatchJson
import dev.jdx.index.service.toJsonWithWarmText
import dev.jdx.index.workspace.FileWorkspaceStore
import dev.jdx.index.workspace.WorkspaceStore

/**
 * The daemon's `JdxService` query dispatch (T-082): the [DaemonHandler] the
 * foreground server runs with.
 *
 * `health` and `version` stay on the T-041 internals (no index involved);
 * `doctor` is refused honestly — it reports the caller's machine environment and
 * `DoctorService` lives in `:cli`, which this module cannot depend on
 * (`:cli` already depends on `:server`). Every other command resolves the
 * daemon's workspace roots per request and dispatches to the same `JdxService`
 * methods the one-shot CLI calls; responses are `ServiceOutcome.toJson(wire)`
 * verbatim, so warm answers are byte-identical to cold ones (D-056 §1).
 *
 * Malformed/unknown lines never reach this handler: the transport owns that
 * envelope (T-041). Root failures (missing workspace → exit 4, unresolvable
 * stored coordinate → exit 5) serialise as the query's envelope, never a drop.
 * Warm-text requests (`warmText=true`, T-086) carry the server-rendered plain
 * text in a `"text"` field on both paths.
 */
public fun jdxServiceHandler(
    workspace: String,
    status: () -> DaemonStatusSnapshot,
    appVersion: String,
    store: WorkspaceStore = FileWorkspaceStore.system(),
): DaemonHandler {
    val internal = defaultDaemonHandler(status, appVersion)
    return DaemonHandler { request ->
        when (request.command) {
            RpcCommand.HEALTH, RpcCommand.VERSION -> internal.handle(request)
            RpcCommand.DOCTOR -> errorEnvelope(
                command = request.command.wire,
                query = request.query,
                code = 6,
                message = "daemon cannot answer 'doctor': it reports this machine's environment — " +
                    "run the same query without the daemon",
            )
            else -> when (val resolved = JdxService.daemonRoots(workspace, request.query, store)) {
                is DaemonRoots.Ready ->
                    JdxService.dispatchJson(request, resolved.roots)
                is DaemonRoots.Failed ->
                    resolved.outcome.toJsonWithWarmText(request.command.wire, request.params)
            }
        }
    }
}
