package dev.jdx.cli.commands

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.CoreCliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import dev.jdx.cli.DaemonClient
import dev.jdx.cli.DaemonRoundTrip
import dev.jdx.cli.WarmRoots
import dev.jdx.cli.defaultRoundTrip
import dev.jdx.cli.effectiveJson
import dev.jdx.cli.effectiveNoDaemon
import dev.jdx.cli.effectiveWorkspace
import dev.jdx.index.service.JdxService
import dev.jdx.index.workspace.FileWorkspaceStore
import dev.jdx.index.workspace.WorkspaceStore
import dev.jdx.server.DaemonPaths
import java.nio.file.Path
import kotlin.system.exitProcess

/**
 * `jdx show <type>`. A thin adapter (D-004): parses roots/output flags, asks [JdxService]
 * for the class card, renders it, and maps the outcome to an exit code (D-015).
 *
 * The card answers "what is this type" — kind, declaration, supertypes, member counts —
 * and always ends with the copy-pasteable `next:` hint for `members`.
 */
class ShowCommand(
    private val query: ShowQuery = ::defaultShowQuery,
    private val terminate: (Int) -> Nothing = ::exitProcess,
    private val store: WorkspaceStore = FileWorkspaceStore.system(),
    private val getenv: (String) -> String? = { name -> System.getenv(name) },
    private val discover: ProjectDiscoveryFn? = null,
    private val daemonRuntimeDir: Path? = DaemonPaths.systemRuntimeDir(),
    private val daemonRoundTrip: DaemonRoundTrip = defaultRoundTrip,
) : CoreCliktCommand(name = "show") {
    override fun help(context: Context): String =
        "Show the class card for a type: kind, modifiers, supertypes, member counts, " +
            "provenance and the next command to run. Takes a type reference " +
            "(e.g. 'com.example.Point', 'Gson', 'java.util.Map\$Entry'); " +
            "member references are a usage error. " +
            "Reads --jars roots plus the selected workspace (-w, JDX_WORKSPACE, jdx ws use) " +
            "plus the JDK stdlib unless --no-jdk. " +
            "With no workspace selected, Gradle/Maven project roots auto-discover from the " +
            "working directory. " +
            "Exits 1 when the type is unknown, 2 when a short name is ambiguous, " +
            "4 when the workspace cannot be resolved."

    private val ref by argument(help = "Type reference to show (full, short or nested form).")

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
        if (DaemonClient.serveWarmIfReady(
                request = DaemonClient.showRequest(ref),
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
                val outcome = query(ref, resolved.roots)
                ReadCommandSupport.finish(outcome, "show", json, noColor, terminate)
            }
            is ReadCommandSupport.RootsOrFailure.Failed ->
                ReadCommandSupport.finish(resolved.outcome, "show", json, noColor, terminate)
        }
    }
}
