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
import dev.jdx.core.model.ANNOTATION_RECIPE_NAMES
import dev.jdx.core.model.AnnotationWhere
import dev.jdx.core.model.parseAnnotationWhere
import dev.jdx.core.render.ErrorResult
import dev.jdx.index.service.JdxService
import dev.jdx.index.workspace.FileWorkspaceStore
import dev.jdx.index.workspace.WorkspaceStore
import dev.jdx.server.DaemonPaths
import java.nio.file.Path
import kotlin.system.exitProcess

/**
 * `jdx annotated <query>`. A thin adapter (D-004): parses the issue-#26 flags,
 * asks [JdxService] for annotated classes/members, renders them, and maps the
 * outcome to an exit code (D-015).
 *
 * Dedicated entry point for annotation-driven views: equivalent to
 * `usages --kind annotation`, plus short recipe aliases (`deprecated`,
 * `forRemoval`, `test`, `bean`, …). Annotation-use spelling (`Type(k=v)`) is
 * accepted without a `--kind` flag (omit leading `@` on the CLI — Clikt
 * `@file`). Exits 1 when the annotation type is unknown or unused.
 */
class AnnotatedCommand(
    private val query: AnnotatedQueryFn = ::defaultAnnotatedQuery,
    private val terminate: (Int) -> Nothing = ::exitProcess,
    private val store: WorkspaceStore = FileWorkspaceStore.system(),
    private val getenv: (String) -> String? = { name -> System.getenv(name) },
    private val discover: ProjectDiscoveryFn? = null,
    private val daemonRuntimeDir: Path? = DaemonPaths.systemSocketDir(),
    private val daemonRoundTrip: DaemonRoundTrip = defaultRoundTrip,
) : CoreCliktCommand(name = "annotated") {
    override fun help(context: Context): String {
        val recipes = ANNOTATION_RECIPE_NAMES.joinToString(", ")
        return "List classes and members carrying an annotation (issue #26): " +
            "equivalent to 'jdx usages <type> --kind annotation'. " +
            "Query may be an annotation type, annotation-use spelling " +
            "'Type(k=v)' (omit leading @ on the CLI), or a recipe alias " +
            "($recipes). --where key=value (repeatable) filters by element " +
            "values (AND; string values may omit quotes). --in/--exclude " +
            "filter by artifact label; --src adds source-dir roots; " +
            "--context N shows a source window around each hit. Exits 1 " +
            "when the annotation type is unknown or unused, 2 on an " +
            "ambiguous short name."
    }

    private val ref by argument(
        help = "Annotation type, 'Type(k=v)' use spelling (omit leading @), " +
            "or recipe alias (${ANNOTATION_RECIPE_NAMES.joinToString("|")}).",
    )

    private val where by option(
        "--where",
        help = "Annotation value predicate key=value (repeatable, AND). " +
            "String values may omit surrounding quotes (--where since=99 " +
            "matches since=\"99\"). Dotted keys walk nested annotations " +
            "(--where nested.k=1); {…} arrays match order-insensitively. " +
            "Equivalent inline form: jdx annotated 'Type(k=v)'.",
    ).multiple()

    private val inArtifact by option(
        "--in",
        help = "Only artifacts whose label matches this glob (jar file name, JDK module or source-dir name).",
    )

    private val exclude by option(
        "--exclude",
        help = "Skip artifacts whose label matches this glob.",
    )

    private val limit by option(
        "--limit",
        help = "Maximum hits shown (default 50); the rest become a truncation footer.",
    ).int().default(50)

    private val context by option(
        "--context",
        help = "Source lines around each hit (0 = location only; N slices paired " +
            "sources / --src files around the LineNumberTable line, capped at 15).",
    ).int().default(0)

    private val jars by option(
        "--jars",
        help = "Binary roots: jar files, class directories or globs (repeatable). " +
            "Merge in front of the selected workspace's roots.",
    ).multiple()

    private val srcs by option(
        "--src",
        help = "Source-dir roots: directories of .java/.kt files (repeatable; stored via jdx ws create --src).",
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
            ReadCommandSupport.finish(failure, "annotated", json, noColor, terminate)
            return
        }
        val whereClauses = mutableListOf<AnnotationWhere>()
        for (raw in where) {
            val parsed = parseAnnotationWhere(raw)
            if (parsed.isFailure) {
                val failure = JdxService.ServiceOutcome.Failure(
                    ErrorResult.generic(
                        ref,
                        exitCode = 3,
                        message = parsed.exceptionOrNull()!!.message!!,
                    ),
                )
                ReadCommandSupport.finish(failure, "annotated", json, noColor, terminate)
                return
            }
            whereClauses.add(parsed.getOrThrow())
        }
        if (DaemonClient.serveWarmIfReady(
                request = DaemonClient.annotatedRequest(
                    ref = ref,
                    inArtifact = inArtifact,
                    exclude = exclude,
                    limit = limit,
                    context = context,
                    where = where,
                ),
                flagWorkspace = effectiveWorkspace(workspace),
                roots = WarmRoots(
                    jars = jars,
                    coords = coord,
                    repos = repo,
                    fetch = fetch,
                    srcs = srcs,
                    noJdk = noJdk,
                ),
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
            srcs = srcs,
        )) {
            is ReadCommandSupport.RootsOrFailure.Ready -> {
                val outcome = query(
                    ref,
                    resolved.roots,
                    JdxService.AnnotatedOptions(
                        inArtifact = inArtifact,
                        exclude = exclude,
                        limit = limit,
                        contextLines = context,
                        where = whereClauses,
                    ),
                )
                ReadCommandSupport.finish(outcome, "annotated", json, noColor, terminate)
            }
            is ReadCommandSupport.RootsOrFailure.Failed ->
                ReadCommandSupport.finish(resolved.outcome, "annotated", json, noColor, terminate)
        }
    }
}
