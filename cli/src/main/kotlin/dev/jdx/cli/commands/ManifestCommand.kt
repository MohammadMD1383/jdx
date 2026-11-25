package dev.jdx.cli.commands

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.CoreCliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
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
 * `jdx manifest [pattern]`. A thin adapter (D-004): parses the issue-#25
 * manifest flags, asks [JdxService] for attributes, renders them, and maps
 * the outcome to an exit code (D-015).
 *
 * Lists `META-INF/MANIFEST.MF` attributes across the workspace. Default is
 * main-section attributes; `--entries` switches to per-entry (JarEntry)
 * sections. An omitted pattern lists every attribute; a non-empty pattern
 * matches the attribute name or value (and, with `--entries`, the entry
 * path). Exits 1 when nothing matches. Resource file bytes live under
 * `jdx resources --cat`.
 */
class ManifestCommand(
    private val query: ManifestQuery = ::defaultManifestQuery,
    private val terminate: (Int) -> Nothing = ::exitProcess,
    private val store: WorkspaceStore = FileWorkspaceStore.system(),
    private val getenv: (String) -> String? = { name -> System.getenv(name) },
    private val discover: ProjectDiscoveryFn? = null,
    private val daemonRuntimeDir: Path? = DaemonPaths.systemSocketDir(),
    private val daemonRoundTrip: DaemonRoundTrip = defaultRoundTrip,
) : CoreCliktCommand(name = "manifest") {
    override fun help(context: Context): String =
        "List META-INF/MANIFEST.MF attributes across the workspace's binary " +
            "roots. Default: main-section attributes. --entries lists " +
            "per-entry (JarEntry) attributes instead. With no pattern, every " +
            "attribute in that section is shown; with a pattern, match the " +
            "attribute name or value (and entry path under --entries). " +
            "--regex treats the pattern as a Java regex; --ignore-case folds " +
            "substring matches. --in/--exclude filter by artifact label; " +
            "--limit caps attribute rows (default 50). Exits 1 when nothing " +
            "matches."

    private val pattern by argument(
        help = "Optional substring (or --regex pattern) matched against " +
            "attribute names and values (and entry paths under --entries). " +
            "Omit to list every attribute in the selected section.",
    ).optional()

    private val entries by option(
        "--entries",
        help = "List per-entry (JarEntry) attributes instead of main-section attributes.",
    ).flag()

    private val regex by option(
        "--regex",
        help = "Treat the pattern as a Java regex matched against each attribute name/value " +
            "(and entry path under --entries).",
    ).flag()

    private val ignoreCase by option(
        "--ignore-case",
        help = "Case-insensitive substring match (ignored under --regex; use (?i) there).",
    ).flag()

    private val inArtifact by option(
        "--in",
        help = "Only artifacts whose label matches this glob (jar file name, JDK module or class-dir name).",
    )

    private val exclude by option(
        "--exclude",
        help = "Skip artifacts whose label matches this glob.",
    )

    private val limit by option(
        "--limit",
        help = "Maximum attribute rows shown (default 50); the rest become a truncation footer.",
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

    private val release by releaseOption()

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
                ErrorResult.generic(
                    pattern.orEmpty(),
                    exitCode = 3,
                    message = "usage error: --limit must be >= 0, got $limit",
                ),
            )
            ReadCommandSupport.finish(failure, "manifest", json, noColor, terminate)
            return
        }
        if (DaemonClient.serveWarmIfReady(
                request = DaemonClient.manifestRequest(
                    pattern = pattern,
                    regex = regex,
                    ignoreCase = ignoreCase,
                    inArtifact = inArtifact,
                    exclude = exclude,
                    limit = limit,
                    entries = entries,
                ),
                flagWorkspace = effectiveWorkspace(workspace),
                roots = WarmRoots(jars = jars, coords = coord, repos = repo, fetch = fetch, noJdk = noJdk, release = release),
                noDaemon = effectiveNoDaemon(noDaemon),
                json = json,
                terminate = terminate,
                store = store,
                getenv = getenv,
                runtimeDir = daemonRuntimeDir,
                roundTrip = daemonRoundTrip,
                color = ReadCommandSupport.useColor(noColor),
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
            release = release,
        )) {
            is ReadCommandSupport.RootsOrFailure.Ready -> {
                val outcome = query(
                    pattern,
                    resolved.roots,
                    JdxService.ManifestOptions(
                        regex = regex,
                        ignoreCase = ignoreCase,
                        inArtifact = inArtifact,
                        exclude = exclude,
                        limit = limit,
                        entries = entries,
                    ),
                )
                ReadCommandSupport.finish(outcome, "manifest", json, noColor, terminate)
            }
            is ReadCommandSupport.RootsOrFailure.Failed ->
                ReadCommandSupport.finish(resolved.outcome, "manifest", json, noColor, terminate)
        }
    }
}
