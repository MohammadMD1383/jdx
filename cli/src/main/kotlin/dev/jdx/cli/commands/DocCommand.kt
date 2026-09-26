package dev.jdx.cli.commands

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.CoreCliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
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
 * `jdx doc <symbol>`. A thin adapter (D-004): parses the §7.1 flags, asks
 * [JdxService] for the symbol's rendered javadoc, and maps the outcome to an
 * exit code (D-015).
 *
 * Docs come from the paired `-sources.jar` (ground truth, T-025). Undocumented
 * methods fall back to the nearest documenting supertype unless
 * `--no-inherited`. Without paired sources the answer names the decompiler
 * tasks (T-026/T-027) instead of guessing.
 */
class DocCommand(
    private val query: DocQuery = ::defaultDocQuery,
    private val terminate: (Int) -> Nothing = ::exitProcess,
    private val store: WorkspaceStore = FileWorkspaceStore.system(),
    private val getenv: (String) -> String? = { name -> System.getenv(name) },
    private val discover: ProjectDiscoveryFn? = null,
    private val daemonRuntimeDir: Path? = DaemonPaths.systemSocketDir(),
    private val daemonRoundTrip: DaemonRoundTrip = defaultRoundTrip,
) : CoreCliktCommand(name = "doc") {
    override fun help(context: Context): String =
        "Show the javadoc of a type or member: rendered plain text (HTML stripped, " +
            "inline tags unwrapped, block tags kept) plus provenance (artifact, file, lines). " +
            "Takes a type or member reference (e.g. 'com.example.Foo', " +
            "\"Gson#toJson(Object)\"). Undocumented methods fall back to the nearest " +
            "documenting supertype unless --no-inherited. " +
            "Exits 1 when the symbol or its documentation is unknown, 2 when a member " +
            "reference is ambiguous, 4 when the workspace cannot be resolved."

    private val ref by argument(help = "Type or member reference to show the documentation of (full or short form).")

    private val inherited by option(
        "--inherited",
        help = "Pull documentation from the nearest documenting supertype (the default; " +
            "accepted for explicitness).",
    ).flag()

    private val noInherited by option(
        "--no-inherited",
        help = "Only the symbol's own documentation (mutually exclusive with --inherited).",
    ).flag()

    private val raw by option(
        "--raw",
        help = "Serve the verbatim comment instead of rendered plain text.",
    ).flag()

    private val maxLines by option(
        "--max-lines",
        help = "Maximum doc lines shown (default 200); the rest become a truncation footer.",
    ).int().default(200)

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
        val usageError = validateDocFlags(
            inherited = inherited,
            noInherited = noInherited,
            maxLines = maxLines,
        )
        if (usageError != null) {
            val failure = JdxService.ServiceOutcome.Failure(
                ErrorResult.generic(ref, exitCode = 3, message = usageError),
            )
            ReadCommandSupport.finish(failure, "doc", json, noColor, terminate)
            return
        }
        if (DaemonClient.serveWarmIfReady(
                request = DaemonClient.docRequest(
                    ref = ref,
                    inherited = inherited,
                    noInherited = noInherited,
                    raw = raw,
                    maxLines = maxLines,
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
                    JdxService.DocOptions(
                        inherit = !noInherited,
                        raw = raw,
                        maxLines = maxLines,
                    ),
                )
                ReadCommandSupport.finish(outcome, "doc", json, noColor, terminate)
            }
            is ReadCommandSupport.RootsOrFailure.Failed ->
                ReadCommandSupport.finish(resolved.outcome, "doc", json, noColor, terminate)
        }
    }
}

/**
 * Validates the flag combinations that reach the service. Returns the usage error
 * message, or `null` when the flags are coherent.
 */
internal fun validateDocFlags(
    inherited: Boolean,
    noInherited: Boolean,
    maxLines: Int,
): String? {
    if (inherited && noInherited) {
        return "usage error: --inherited and --no-inherited are mutually exclusive"
    }
    if (maxLines < 0) return "usage error: --max-lines must be >= 0, got $maxLines"
    return null
}
