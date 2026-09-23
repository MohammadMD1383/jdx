package dev.jdx.index.service

import dev.jdx.core.model.Visibility
import dev.jdx.core.render.DEFAULT_BODY_MAX_LINES
import dev.jdx.core.render.DEFAULT_CALLS_DEPTH
import dev.jdx.core.render.DEFAULT_CALLS_LIMIT
import dev.jdx.core.render.DEFAULT_DOC_MAX_LINES
import dev.jdx.core.render.DEFAULT_HIERARCHY_LIMIT
import dev.jdx.core.render.DEFAULT_MEMBER_LIMIT
import dev.jdx.core.render.DEFAULT_SAMPLES_LIMIT
import dev.jdx.core.render.DEFAULT_SEARCH_LIMIT
import dev.jdx.core.render.DEFAULT_SIGNATURE_LIMIT
import dev.jdx.core.render.DEFAULT_SOURCE_MAX_LINES
import dev.jdx.core.render.DEFAULT_TREE_DEPTH
import dev.jdx.core.render.DEFAULT_USAGES_LIMIT
import dev.jdx.core.render.ErrorResult
import dev.jdx.core.render.MemberSort
import dev.jdx.core.rpc.RpcCommand
import dev.jdx.core.rpc.RpcRequest
import dev.jdx.decompile.DecompilerId
import dev.jdx.index.maven.MavenResolver
import dev.jdx.index.service.JdxService.BodyOptions
import dev.jdx.index.service.JdxService.CallOptions
import dev.jdx.index.service.JdxService.DocOptions
import dev.jdx.index.service.JdxService.HierarchyOptions
import dev.jdx.index.service.JdxService.KindFilter
import dev.jdx.index.service.JdxService.MemberFilters
import dev.jdx.index.service.JdxService.MemberView
import dev.jdx.index.service.JdxService.RootsSpec
import dev.jdx.index.service.JdxService.SampleOptions
import dev.jdx.index.service.JdxService.SearchKindFilter
import dev.jdx.index.service.JdxService.SearchOptions
import dev.jdx.index.service.JdxService.ServiceOutcome
import dev.jdx.index.service.JdxService.SignatureOptions
import dev.jdx.index.service.JdxService.SourceOptions
import dev.jdx.index.service.JdxService.UsageKindFilter
import dev.jdx.index.service.JdxService.UsageOptions
import dev.jdx.index.workspace.FileWorkspaceStore
import dev.jdx.index.workspace.WorkspaceResolver
import dev.jdx.index.workspace.WorkspaceStore

/**
 * The daemon's query dispatch (T-082): one [RpcRequest] in, one [ServiceOutcome] out.
 *
 * This is the same call the one-shot CLI makes — `JdxService.members(ref, roots, ...)` and
 * friends — so a warm answer is byte-identical to the cold one by construction
 * (`ServiceOutcome.toJson(command)` on both sides; the T-046 parity proof stays
 * structural, D-056 §1). The daemon transport (`:server`) owns framing and the
 * `health`/`version` internals; every read query lands here.
 *
 * **Param contract** (v1): param names are the CLI long-flag names without the dashes,
 * values are the flag values as text (`"true"`, `"50"` — the T-040 spelling, so the
 * T-042 CLI client forwards flags verbatim). Absent means the CLI default. Unknown
 * enum values and unparseable integers are exit-3 usage errors naming the param —
 * never a guess, never a throw ([dispatch] is total: safe to call on hostile input
 * from any thread — parsing holds no shared state).
 *
 * Per command (defaults are the CLI's):
 * - `show`: no params.
 * - `members`: `kind` (all|method|field|ctor|property), `access`
 *   (all|public|protected|package|private), `static`/`instance` (mutually exclusive),
 *   `from`, `grep`, `includeSynthetic`, `limit`, `withDoc`, `sort`
 *   (kind|name|declaring), `view` (kotlin|jvm), `declared`.
 * - `outline`: the `members` params minus `declared` (always declared-only).
 * - `body`: `context`, `lineNumbers`, `maxLines`, `withSignature`, `withDoc`,
 *   `engine` (vineflower|javap).
 * - `source`: `lines` (`A:B`), `around`, `context`, `lineNumbers`, `maxLines`,
 *   `engine` (vineflower|javap).
 * - `signature`: `includeSynthetic`, `limit`, `view` (kotlin|jvm).
 * - `doc`: `inherited`/`no-inherited` (mutually exclusive; default inherits),
 *   `raw`, `maxLines`.
 * - `search`: `kind` (all|type|class|interface|enum|record|annotation|object|
 *   companion|method|field|package|module), `regex`, `fuzzy`, `in`, `package`, `limit`.
 * - `resolve`: `limit`.
 * - `ls`: `limit` (empty query lists every package).
 * - `tree`: `depth`, `counts`, `limit` (empty query covers every artifact).
 * - `usages`: `kind` (all|call|read|write|ref|new|throw|annotation),
 *   `in`, `exclude`, `limit`, `context`.
 * - `hierarchy`: `up`, `down`, `direct`, `depth`, `in`, `exclude`, `limit`.
 * - `implementors`: `direct`, `depth`, `in`, `exclude`, `limit`.
 * - `callers`: `depth`, `in`, `exclude`, `limit`.
 * - `calls`: `depth`, `in`, `exclude`, `externalOnly`, `limit`.
 * - `samples`: `limit`, `in`, `exclude`, `preferSources`.
 *
 * `version`/`doctor`/`health` are transport-level (T-041): they never reach this
 * function through the daemon, and calling it directly answers exit 3 saying so.
 */
public fun JdxService.dispatch(request: RpcRequest, roots: RootsSpec): ServiceOutcome {
    val scope = ParamScope(request.query, request.command.wire, request.params)
    return when (request.command) {
        RpcCommand.SHOW -> show(scope.query, roots)
        RpcCommand.MEMBERS -> members(
            scope.query, roots,
            scope.memberFilters() ?: return scope.failure(),
            declaredOnly = scope.bool("declared"),
            includeSynthetic = scope.bool("includeSynthetic"),
            maxMembers = scope.int("limit", DEFAULT_MEMBER_LIMIT) ?: return scope.failure(),
        )
        RpcCommand.OUTLINE -> outline(
            scope.query, roots,
            scope.memberFilters() ?: return scope.failure(),
            includeSynthetic = scope.bool("includeSynthetic"),
            maxMembers = scope.int("limit", DEFAULT_MEMBER_LIMIT) ?: return scope.failure(),
        )
        RpcCommand.BODY -> body(
            scope.query, roots,
            BodyOptions(
                contextLines = scope.int("context", 0) ?: return scope.failure(),
                lineNumbers = scope.bool("lineNumbers"),
                maxLines = scope.int("maxLines", DEFAULT_BODY_MAX_LINES) ?: return scope.failure(),
                withSignature = scope.bool("withSignature"),
                withDoc = scope.bool("withDoc"),
                engine = if (scope.has("engine")) scope.engineOrNull() ?: return scope.failure() else null,
            ),
        )
        RpcCommand.SOURCE -> source(
            scope.query, roots,
            SourceOptions(
                lines = if (scope.has("lines")) scope.linesWindowOrNull() ?: return scope.failure() else null,
                aroundRef = scope.params["around"],
                contextLines = scope.int("context", 0) ?: return scope.failure(),
                lineNumbers = scope.bool("lineNumbers"),
                maxLines = scope.int("maxLines", DEFAULT_SOURCE_MAX_LINES) ?: return scope.failure(),
                engine = if (scope.has("engine")) scope.engineOrNull() ?: return scope.failure() else null,
            ),
        )
        RpcCommand.SIGNATURE -> signature(
            scope.query, roots,
            SignatureOptions(
                includeSynthetic = scope.bool("includeSynthetic"),
                maxSignatures = scope.int("limit", DEFAULT_SIGNATURE_LIMIT) ?: return scope.failure(),
                view = scope.view() ?: return scope.failure(),
            ),
        )
        RpcCommand.DOC -> doc(
            scope.query, roots,
            DocOptions(
                inherit = scope.inherit() ?: return scope.failure(),
                raw = scope.bool("raw"),
                maxLines = scope.int("maxLines", DEFAULT_DOC_MAX_LINES) ?: return scope.failure(),
            ),
        )
        RpcCommand.SEARCH -> search(
            scope.query, roots,
            SearchOptions(
                kind = scope.searchKind() ?: return scope.failure(),
                regex = scope.bool("regex"),
                fuzzy = scope.bool("fuzzy"),
                inArtifact = scope.params["in"],
                inPackage = scope.params["package"],
                limit = scope.int("limit", DEFAULT_SEARCH_LIMIT) ?: return scope.failure(),
            ),
        )
        RpcCommand.RESOLVE -> resolve(
            scope.query, roots,
            limit = scope.int("limit", DEFAULT_SEARCH_LIMIT) ?: return scope.failure(),
        )
        RpcCommand.LS -> ls(
            scope.query.ifEmpty { null }, roots,
            limit = scope.int("limit", DEFAULT_SEARCH_LIMIT) ?: return scope.failure(),
        )
        RpcCommand.TREE -> tree(
            scope.query.ifEmpty { null }, roots,
            depth = scope.int("depth", DEFAULT_TREE_DEPTH) ?: return scope.failure(),
            withCounts = scope.bool("counts"),
            limit = scope.int("limit", DEFAULT_SEARCH_LIMIT) ?: return scope.failure(),
        )
        RpcCommand.USAGES -> usages(
            scope.query, roots,
            UsageOptions(
                kind = scope.usagesKind() ?: return scope.failure(),
                inArtifact = scope.params["in"],
                exclude = scope.params["exclude"],
                limit = scope.int("limit", DEFAULT_USAGES_LIMIT) ?: return scope.failure(),
                contextLines = scope.int("context", 0) ?: return scope.failure(),
            ),
        )
        RpcCommand.HIERARCHY -> hierarchy(
            scope.query, roots,
            HierarchyOptions(
                up = scope.bool("up") || !scope.bool("down"),
                down = scope.bool("down") || !scope.bool("up"),
                directOnly = scope.bool("direct"),
                depth = scope.int("depth", Int.MAX_VALUE) ?: return scope.failure(),
                inArtifact = scope.params["in"],
                exclude = scope.params["exclude"],
                limit = scope.int("limit", DEFAULT_HIERARCHY_LIMIT) ?: return scope.failure(),
            ),
        )
        RpcCommand.IMPLEMENTORS -> hierarchy(
            scope.query, roots,
            HierarchyOptions(
                up = false,
                down = true,
                directOnly = scope.bool("direct"),
                depth = scope.int("depth", Int.MAX_VALUE) ?: return scope.failure(),
                inArtifact = scope.params["in"],
                exclude = scope.params["exclude"],
                limit = scope.int("limit", DEFAULT_HIERARCHY_LIMIT) ?: return scope.failure(),
            ),
        )
        RpcCommand.CALLERS -> callers(
            scope.query, roots,
            CallOptions(
                depth = scope.int("depth", DEFAULT_CALLS_DEPTH) ?: return scope.failure(),
                inArtifact = scope.params["in"],
                exclude = scope.params["exclude"],
                limit = scope.int("limit", DEFAULT_CALLS_LIMIT) ?: return scope.failure(),
            ),
        )
        RpcCommand.CALLS -> calls(
            scope.query, roots,
            CallOptions(
                depth = scope.int("depth", DEFAULT_CALLS_DEPTH) ?: return scope.failure(),
                inArtifact = scope.params["in"],
                exclude = scope.params["exclude"],
                limit = scope.int("limit", DEFAULT_CALLS_LIMIT) ?: return scope.failure(),
                externalOnly = scope.bool("externalOnly"),
            ),
        )
        RpcCommand.SAMPLES -> samples(
            scope.query, roots,
            SampleOptions(
                limit = scope.int("limit", DEFAULT_SAMPLES_LIMIT) ?: return scope.failure(),
                inArtifact = scope.params["in"],
                exclude = scope.params["exclude"],
                preferSources = scope.bool("preferSources"),
            ),
        )
        RpcCommand.VERSION, RpcCommand.DOCTOR, RpcCommand.HEALTH ->
            ServiceOutcome.Failure(
                ErrorResult.generic(
                    scope.query, exitCode = 3,
                    message = "usage error: '${request.command.wire}' is answered by the daemon transport, not the query dispatch",
                ),
            )
    }
}

/**
 * The two ways daemon root resolution ends: roots to query with, or an exit-coded
 * outcome to serialise instead of querying (mirroring `ReadCommandSupport`).
 */
public sealed interface DaemonRoots {
    /** Resolution succeeded — query with these roots. */
    public data class Ready(val roots: RootsSpec) : DaemonRoots

    /** Resolution failed — serialise this instead of querying. */
    public data class Failed(val outcome: ServiceOutcome.Failure) : DaemonRoots
}

/**
 * Resolves the roots the daemon serves for one named workspace (T-082): the stored
 * workspace's jars plus its coordinates (local caches only — the daemon never
 * fetches, so stored `--repo` mirrors are inert here), its `srcs`, and the JDK
 * unless switched off. This is `-w <name>` semantics exactly: a missing workspace
 * reads as exit 4 naming the fix, like the one-shot CLI — never a silent fallback
 * to other roots. Call per request (not per start), so `ws create` while the daemon
 * runs is picked up. Never throws.
 */
public fun JdxService.daemonRoots(
    workspace: String,
    query: String,
    store: WorkspaceStore = FileWorkspaceStore.system(),
): DaemonRoots {
    val resolved = WorkspaceResolver.resolve(
        flagWorkspace = workspace,
        loadWorkspace = store::load,
        listNames = {
            try {
                store.listNames()
            } catch (_: Exception) {
                emptyList()
            }
        },
    )
    return when (resolved) {
        is WorkspaceResolver.Result.success -> {
            val value = resolved.value
            when (val coords = MavenResolver.resolveAll(value.coords, allowFetch = false)) {
                is MavenResolver.ResolveAllOutcome.Ok -> DaemonRoots.Ready(
                    RootsSpec(
                        jarSpecs = value.jarSpecs + coords.artifacts.map { it.binaryJar.toString() },
                        includeJdk = value.includeJdk,
                        extraWarnings = value.warnings,
                        allowFetch = false,
                        srcSpecs = value.srcSpecs,
                    ),
                )
                is MavenResolver.ResolveAllOutcome.Failed -> DaemonRoots.Failed(
                    ServiceOutcome.Failure(
                        ErrorResult.generic(query, exitCode = 5, message = "artifact read error: ${coords.message}"),
                    ),
                )
            }
        }
        is WorkspaceResolver.Result.failure ->
            DaemonRoots.Failed(
                ServiceOutcome.Failure(
                    ErrorResult.generic(query, exitCode = 4, message = resolved.error.message),
                ),
            )
    }
}

// -- param parsing (per-call scope; no shared state, so concurrent dispatches are safe) ---

/**
 * One request's view over its raw params: typed accessors that record an exit-3
 * [ServiceOutcome.Failure] instead of throwing. A fresh scope per [dispatch] call,
 * so concurrent daemon connections never share parse state.
 */
private class ParamScope(
    val query: String,
    val commandWire: String,
    val params: Map<String, String>,
) {
    private var failure: ServiceOutcome.Failure? = null

    /** The recorded failure. Callers use `… ?: return scope.failure()` after each accessor. */
    fun failure(): ServiceOutcome.Failure =
        failure ?: ServiceOutcome.Failure(ErrorResult.generic(query, exitCode = 3, message = "usage error"))

    private fun fail(message: String) {
        if (failure == null) {
            failure = ServiceOutcome.Failure(ErrorResult.generic(query, exitCode = 3, message = message))
        }
    }

    /** `"true"` (any case) is true; absent and anything else is false — the CLI flag spelling. */
    fun bool(name: String): Boolean = params[name]?.equals("true", ignoreCase = true) == true

    /** An integer param, or the default when absent; unparseable records the usage error. */
    fun int(name: String, default: Int): Int? {
        val raw = params[name] ?: return default
        return raw.toIntOrNull() ?: run {
            fail("usage error: --$name must be an integer, got '$raw'")
            null
        }
    }

    fun memberFilters(): MemberFilters? {
        if (bool("static") && bool("instance")) {
            fail("usage error: --static and --instance are mutually exclusive")
            return null
        }
        val grep = params["grep"]?.let { raw ->
            try {
                Regex(raw)
            } catch (e: Exception) {
                fail("usage error: invalid --grep regex '$raw': ${e.message}")
                return null
            }
        }
        return MemberFilters(
            kind = when (params["kind"]?.lowercase()) {
                null, "all" -> KindFilter.ALL
                "method" -> KindFilter.METHOD
                "field" -> KindFilter.FIELD
                "ctor" -> KindFilter.CTOR
                "property" -> KindFilter.PROPERTY
                else -> {
                    fail("usage error: --kind '${params["kind"]}' is not valid for $commandWire (expected all|method|field|ctor|property)")
                    return null
                }
            },
            access = when (params["access"]?.lowercase()) {
                null -> null
                "all" -> setOf(Visibility.PUBLIC, Visibility.PROTECTED, Visibility.PACKAGE_PRIVATE, Visibility.PRIVATE)
                "public" -> setOf(Visibility.PUBLIC)
                "protected" -> setOf(Visibility.PROTECTED)
                "package" -> setOf(Visibility.PACKAGE_PRIVATE)
                "private" -> setOf(Visibility.PRIVATE)
                else -> {
                    fail("usage error: --access '${params["access"]}' is not valid for $commandWire (expected all|public|protected|package|private)")
                    return null
                }
            },
            staticOnly = when {
                bool("static") -> true
                bool("instance") -> false
                else -> null
            },
            fromRef = params["from"],
            grep = grep,
            sort = when (params["sort"]?.lowercase()) {
                null, "kind" -> MemberSort.KIND
                "name" -> MemberSort.NAME
                "declaring" -> MemberSort.DECLARING
                else -> {
                    fail("usage error: --sort '${params["sort"]}' is not valid for $commandWire (expected kind|name|declaring)")
                    return null
                }
            },
            withDoc = bool("withDoc"),
            view = view() ?: return null,
        )
    }

    fun view(): MemberView? =
        when (params["view"]?.lowercase()) {
            null, "kotlin" -> MemberView.KOTLIN
            "jvm" -> MemberView.JVM
            else -> {
                fail("usage error: --view '${params["view"]}' is not valid for $commandWire (expected kotlin|jvm)")
                null
            }
        }

    /** Whether the request carried this param at all (absent means the CLI default). */
    fun has(name: String): Boolean = name in params

    /** `null` means the ladder default (paired sources first); unknown engines fail. */
    fun engineOrNull(): DecompilerId? {
        return when (params["engine"]?.lowercase()) {
            null -> null
            "vineflower" -> DecompilerId.VINEFLOWER
            "javap" -> DecompilerId.JAVAP
            else -> {
                fail("usage error: --engine ${params["engine"]} is not a known engine (vineflower|javap)")
                null
            }
        }
    }

    fun inherit(): Boolean? {
        if (bool("inherited") && bool("no-inherited")) {
            fail("usage error: --inherited and --no-inherited are mutually exclusive")
            return null
        }
        return !bool("no-inherited")
    }

    fun searchKind(): SearchKindFilter? =
        when (params["kind"]?.lowercase()) {
            null, "all" -> SearchKindFilter.ALL
            "type" -> SearchKindFilter.TYPE
            "class" -> SearchKindFilter.CLASS
            "interface" -> SearchKindFilter.INTERFACE
            "enum" -> SearchKindFilter.ENUM
            "record" -> SearchKindFilter.RECORD
            "annotation" -> SearchKindFilter.ANNOTATION
            "object" -> SearchKindFilter.OBJECT
            "companion" -> SearchKindFilter.COMPANION
            "method" -> SearchKindFilter.METHOD
            "field" -> SearchKindFilter.FIELD
            "package" -> SearchKindFilter.PACKAGE
            "module" -> SearchKindFilter.MODULE
            else -> {
                fail("usage error: --kind '${params["kind"]}' is not valid for search (expected all|type|class|interface|enum|record|annotation|object|companion|method|field|package|module)")
                null
            }
        }

    fun usagesKind(): UsageKindFilter? =
        when (params["kind"]?.lowercase()) {
            null, "all" -> UsageKindFilter.ALL
            "call" -> UsageKindFilter.CALL
            "read" -> UsageKindFilter.READ
            "write" -> UsageKindFilter.WRITE
            "ref" -> UsageKindFilter.REF
            "impl" -> UsageKindFilter.IMPL
            "override" -> UsageKindFilter.OVERRIDE
            "new" -> UsageKindFilter.NEW
            "throw" -> UsageKindFilter.THROW
            "annotation" -> UsageKindFilter.ANNOTATION
            else -> {
                fail("usage error: --kind '${params["kind"]}' is not valid for usages (expected all|call|read|write|ref|new|throw|annotation)")
                null
            }
        }

    /**
     * The `source` `lines` (`A:B`) window. Callers check [has] first: absent reads as
     * the whole-file default, while a present but misshapen window records the usage
     * error (mirroring the CLI's `--lines` validation).
     */
    fun linesWindowOrNull(): Pair<Int, Int>? {
        val text = params["lines"] ?: return null
        val parts = text.trim().split(":")
        val first = parts.getOrNull(0)?.trim()?.toIntOrNull()
        val second = parts.getOrNull(1)?.trim()?.toIntOrNull()
        if (parts.size != 2 || first == null || second == null || first < 1 || second < first) {
            fail("usage error: --lines must be A:B with 1 <= A <= B, got '$text'")
            return null
        }
        return first to second
    }
}
