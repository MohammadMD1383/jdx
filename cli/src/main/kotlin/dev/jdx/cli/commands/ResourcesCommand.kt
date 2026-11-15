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
import dev.jdx.core.render.DEFAULT_RESOURCE_MAX_LINES
import dev.jdx.core.render.ErrorResult
import dev.jdx.index.service.JdxService
import dev.jdx.index.workspace.FileWorkspaceStore
import dev.jdx.index.workspace.WorkspaceStore
import dev.jdx.server.DaemonPaths
import java.nio.file.Path
import kotlin.system.exitProcess

/**
 * `jdx resources [pattern]`. A thin adapter (D-004): parses the issue-#25
 * resource flags, asks [JdxService] for non-class paths or `--cat` content,
 * renders them, and maps the outcome to an exit code (D-015).
 *
 * Lists non-class resource paths across the workspace. An omitted pattern
 * lists every resource; a non-empty pattern matches the entry path. With
 * `--cat`, shows the unique match's text (or refuses binary dumps); zero
 * matches exit 1, two or more exit 2 with `path --in artifact` candidates.
 */
class ResourcesCommand(
    private val query: ResourcesQuery = ::defaultResourcesQuery,
    private val terminate: (Int) -> Nothing = ::exitProcess,
    private val store: WorkspaceStore = FileWorkspaceStore.system(),
    private val getenv: (String) -> String? = { name -> System.getenv(name) },
    private val discover: ProjectDiscoveryFn? = null,
    private val daemonRuntimeDir: Path? = DaemonPaths.systemSocketDir(),
    private val daemonRoundTrip: DaemonRoundTrip = defaultRoundTrip,
) : CoreCliktCommand(name = "resources") {
    override fun help(context: Context): String =
        "List non-class resource paths across the workspace's binary roots " +
            "(META-INF, .properties, versioned META-INF/versions/… copies, …). " +
            "With no pattern, every resource is shown; with a pattern, match " +
            "the entry path (substring by default). --cat prints the unique " +
            "match's text contents (binary payloads refuse to dump bytes; " +
            "ambiguous matches exit 2). --max-lines caps --cat text lines " +
            "(default 200). --regex treats the pattern as a Java regex; " +
            "--ignore-case folds substring matches. --in/--exclude filter by " +
            "artifact label; --limit caps path rows in listing mode (default " +
            "50). Exits 1 when nothing matches."

    private val pattern by argument(
        help = "Optional substring (or --regex pattern) matched against " +
            "resource entry paths. Omit to list every non-class resource " +
            "(listing mode). Required with --cat.",
    ).optional()

    private val cat by option(
        "--cat",
        help = "Print the unique matching resource's text contents instead of " +
            "listing paths. Binary (NUL / non-UTF-8) payloads report size only. " +
            "Requires a pattern; exits 2 when more than one path matches.",
    ).flag()

    private val maxLines by option(
        "--max-lines",
        help = "Maximum text lines shown under --cat (default 200); the rest " +
            "become a truncation footer. Ignored in listing mode.",
    ).int().default(DEFAULT_RESOURCE_MAX_LINES)

    private val regex by option(
        "--regex",
        help = "Treat the pattern as a Java regex matched against each resource path.",
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
        help = "Maximum resource path rows shown in listing mode (default 50); " +
            "the rest become a truncation footer. Ignored under --cat.",
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
                ErrorResult.generic(
                    pattern.orEmpty(),
                    exitCode = 3,
                    message = "usage error: --limit must be >= 0, got $limit",
                ),
            )
            ReadCommandSupport.finish(failure, "resources", json, noColor, terminate)
            return
        }
        if (maxLines < 0) {
            val failure = JdxService.ServiceOutcome.Failure(
                ErrorResult.generic(
                    pattern.orEmpty(),
                    exitCode = 3,
                    message = "usage error: --max-lines must be >= 0, got $maxLines",
                ),
            )
            ReadCommandSupport.finish(failure, "resources", json, noColor, terminate)
            return
        }
        if (DaemonClient.serveWarmIfReady(
                request = DaemonClient.resourcesRequest(
                    pattern = pattern,
                    regex = regex,
                    ignoreCase = ignoreCase,
                    inArtifact = inArtifact,
                    exclude = exclude,
                    limit = limit,
                    cat = cat,
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
        )) {
            is ReadCommandSupport.RootsOrFailure.Ready -> {
                val outcome = query(
                    pattern,
                    resolved.roots,
                    JdxService.ResourceOptions(
                        regex = regex,
                        ignoreCase = ignoreCase,
                        inArtifact = inArtifact,
                        exclude = exclude,
                        limit = limit,
                        cat = cat,
                        maxLines = maxLines,
                    ),
                )
                ReadCommandSupport.finish(outcome, "resources", json, noColor, terminate)
            }
            is ReadCommandSupport.RootsOrFailure.Failed ->
                ReadCommandSupport.finish(resolved.outcome, "resources", json, noColor, terminate)
        }
    }
}
