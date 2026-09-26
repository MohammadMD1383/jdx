package dev.jdx.cli.commands

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.CoreCliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.int
import dev.jdx.cli.DaemonClient
import dev.jdx.cli.DaemonRoundTrip
import dev.jdx.cli.WarmRoots
import dev.jdx.cli.defaultRoundTrip
import dev.jdx.cli.effectiveJson
import dev.jdx.cli.effectiveNoDaemon
import dev.jdx.cli.effectiveWorkspace
import dev.jdx.core.render.ErrorResult
import dev.jdx.index.service.JdxService
import dev.jdx.index.workspace.FileWorkspaceStore
import dev.jdx.index.workspace.WorkspaceStore
import dev.jdx.server.DaemonPaths
import java.nio.file.Path
import kotlin.system.exitProcess

/**
 * `jdx signature <member>`. A thin adapter (D-004): parses the §7.1 flags,
 * asks [JdxService] for the member's bytecode signatures, renders them, and
 * maps the outcome to an exit code (D-015).
 *
 * Signatures come from bytecode alone (T-024) — no sources are read, so
 * sources-less jars answer by design. One line per overload: an
 * under-specified name lists every overload (exit 0), while an unknown
 * type/member exits 1 with did-you-mean suggestions.
 */
class SignatureCommand(
    private val query: SignatureQuery = ::defaultSignatureQuery,
    private val terminate: (Int) -> Nothing = ::exitProcess,
    private val store: WorkspaceStore = FileWorkspaceStore.system(),
    private val getenv: (String) -> String? = { name -> System.getenv(name) },
    private val discover: ProjectDiscoveryFn? = null,
    private val daemonRuntimeDir: Path? = DaemonPaths.systemSocketDir(),
    private val daemonRoundTrip: DaemonRoundTrip = defaultRoundTrip,
) : CoreCliktCommand(name = "signature") {
    override fun help(context: Context): String =
        "Show the signature of a member: one source-style line per overload with real " +
            "parameter names, generics, throws and defaults, plus provenance. Takes a member " +
            "reference (e.g. 'com.example.Point#getX()', \"Gson#toJson(Object)\"). " +
            "An under-specified name lists every overload. " +
            "Exits 1 when the member is unknown, 3 on a type or invalid reference, " +
            "4 when the workspace cannot be resolved."

    private val ref by argument(help = "Member reference to show the signature of (full or short form).")

    private val includeSynthetic by option(
        "--include-synthetic",
        help = "Show bridge/synthetic members, hidden by default.",
    ).flag()

    private val view by option(
        "--view",
        help = "Declaration projection: kotlin (the default, true Kotlin declarations " +
            "from @Metadata) or jvm (the raw JVM projection: JVM names, hidden " +
            "Continuation params, getters/setters, mangled internal names).",
    ).choice("kotlin", "jvm", ignoreCase = true).default("kotlin")

    private val limit by option(
        "--limit",
        help = "Maximum signatures shown (default 50); the rest become a truncation footer.",
    ).int().default(50)

    private val jars by option(
        "--jars",
        help = "Binary roots: jar files, class directories or globs (repeatable). " +
            "Merge in front of the selected workspace's roots.",
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
            "into <cache-dir>/m2 with checksum verification. Merges in front of the workspace.",
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
        help = "Emit machine-readable JSON instead of human-readable text (same information, D-007).",
    ).flag()

    private val noColor by option(
        "--no-color",
        help = "Disable ANSI colors even on a TTY (piped output is always plain).",
    ).flag()

    private val noDaemon by option(
        "--no-daemon",
        help = "Force in-process execution: do not forward this query to the background " +
            "daemon even when its socket answers (PROPOSAL.md §14.1).",
    ).flag()

    override fun run() {
        val json = effectiveJson(json)
        if (limit < 0) {
            val failure = JdxService.ServiceOutcome.Failure(
                ErrorResult.generic(ref, exitCode = 3, message = "usage error: --limit must be >= 0, got $limit"),
            )
            ReadCommandSupport.finish(failure, "signature", json, noColor, terminate)
            return
        }
        if (DaemonClient.serveWarmIfReady(
                request = DaemonClient.signatureRequest(
                    ref = ref,
                    includeSynthetic = includeSynthetic,
                    limit = limit,
                    view = view,
                ),
                flagWorkspace = effectiveWorkspace(workspace),
                roots = WarmRoots(jars = jars, coords = coord, repos = repo, fetch = fetch, noJdk = noJdk),
                noDaemon = effectiveNoDaemon(noDaemon),
                json = json,
                terminate = terminate,
                store = store,
                getenv = getenv,
                runtimeDir = daemonRuntimeDir,
                roundTrip = daemonRoundTrip,
            )
        ) return
        when (val resolved = ReadCommandSupport.resolveRoots(
            jars,
            noJdk,
            effectiveWorkspace(workspace),
            store,
            getenv,
            discover = discover ?: ReadCommandSupport::discoverProject,
            coords = coord,
            allowFetch = fetch,
            repos = repo,
        )) {
            is ReadCommandSupport.RootsOrFailure.Ready -> {
                val outcome = query(
                    ref,
                    resolved.roots,
                    JdxService.SignatureOptions(
                        includeSynthetic = includeSynthetic,
                        maxSignatures = limit,
                        view = ReadCommandSupport.viewOf(view),
                    ),
                )
                ReadCommandSupport.finish(outcome, "signature", json, noColor, terminate)
            }
            is ReadCommandSupport.RootsOrFailure.Failed ->
                ReadCommandSupport.finish(resolved.outcome, "signature", json, noColor, terminate)
        }
    }
}
