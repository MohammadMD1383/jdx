package dev.jdx.cli.commands

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.CoreCliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import dev.jdx.cli.effectiveJson
import dev.jdx.cli.effectiveWorkspace
import dev.jdx.core.render.ErrorResult
import dev.jdx.index.service.JdxService
import dev.jdx.index.workspace.FileWorkspaceStore
import dev.jdx.index.workspace.WorkspaceStore
import kotlin.system.exitProcess

/**
 * The call-hierarchy pair (T-033): `callers` walks incoming `METHOD_CALL`
 * edges, `calls` walks outgoing ones. Both commands below are thin adapters
 * (D-004) — flags parsed here, behaviour in [JdxService].
 */

/**
 * `jdx callers <method>`. A thin adapter (D-004): parses the §7.3 flags, asks
 * [JdxService] for the incoming call tree, renders it, and maps the outcome
 * to an exit code (D-015).
 *
 * Incoming call edges, transitively to `--depth` (default 1): one indented
 * row per calling method, cycle-safe with `…(cycle)` markers. Member refs
 * only (methods + constructors) — type refs and field refs exit 3, fields
 * belong to `usages`. Exits 1 when the method is unknown or uncalled, 2 on an
 * ambiguous short name.
 */
class CallersCommand(
    private val query: CallGraphQuery = ::defaultCallGraphQuery,
    private val terminate: (Int) -> Nothing = ::exitProcess,
    private val store: WorkspaceStore = FileWorkspaceStore.system(),
    private val getenv: (String) -> String? = { name -> System.getenv(name) },
    private val discover: ProjectDiscoveryFn? = null,
) : CoreCliktCommand(name = "callers") {
    override fun help(context: Context): String =
        "Show who calls a method, transitively to --depth (default 1): one " +
            "indented row per calling method, cycle-safe with …(cycle) markers. " +
            "--in/--exclude filter rows by artifact label (jar file, JDK module " +
            "or class-dir name). Member refs only (methods + constructors); " +
            "type refs and field refs exit 3 (fields belong to jdx usages). " +
            "Exits 1 when the method is unknown or uncalled, 2 on an ambiguous short name."

    private val ref by argument(help = "Method or constructor reference to list callers of (full or short form).")

    private val depth by option(
        "--depth",
        help = "Maximum transitive levels shown (default 1, direct callers only).",
    ).int().default(1)

    private val inArtifact by option(
        "--in",
        help = "Only callers whose artifact label matches this glob (jar file name, JDK module or class-dir name).",
    )

    private val exclude by option(
        "--exclude",
        help = "Skip callers whose artifact label matches this glob.",
    )

    private val limit by option(
        "--limit",
        help = "Maximum tree nodes shown (default 50); the rest become a truncation footer.",
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

    override fun run() {
        val json = effectiveJson(json)
        if (limit < 0) {
            val failure = JdxService.ServiceOutcome.Failure(
                ErrorResult.generic(ref, exitCode = 3, message = "usage error: --limit must be >= 0, got $limit"),
            )
            ReadCommandSupport.finish(failure, "callers", json, noColor, terminate)
            return
        }
        if (depth < 1) {
            val failure = JdxService.ServiceOutcome.Failure(
                ErrorResult.generic(ref, exitCode = 3, message = "usage error: --depth must be >= 1, got $depth"),
            )
            ReadCommandSupport.finish(failure, "callers", json, noColor, terminate)
            return
        }
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
                    JdxService.CallOptions(
                        depth = depth,
                        inArtifact = inArtifact,
                        exclude = exclude,
                        limit = limit,
                    ),
                    dev.jdx.core.render.CallDirection.CALLERS,
                )
                ReadCommandSupport.finish(outcome, "callers", json, noColor, terminate)
            }
            is ReadCommandSupport.RootsOrFailure.Failed ->
                ReadCommandSupport.finish(resolved.outcome, "callers", json, noColor, terminate)
        }
    }
}

/**
 * `jdx calls <method>`. The outward half of the pair: what this method calls,
 * transitively to `--depth` (default 1), with the same row layout, exit codes
 * and root flags as [CallersCommand], plus `--external-only` (Appendix B) to
 * prune callees in the method's own artifact for dependency analysis.
 */
class CallsCommand(
    private val query: CallGraphQuery = ::defaultCallGraphQuery,
    private val terminate: (Int) -> Nothing = ::exitProcess,
    private val store: WorkspaceStore = FileWorkspaceStore.system(),
    private val getenv: (String) -> String? = { name -> System.getenv(name) },
    private val discover: ProjectDiscoveryFn? = null,
) : CoreCliktCommand(name = "calls") {
    override fun help(context: Context): String =
        "Show what a method calls, transitively to --depth (default 1): one " +
            "indented row per callee, cycle-safe with …(cycle) markers. " +
            "--in/--exclude filter rows by artifact label; --external-only " +
            "prunes callees in the method's own artifact. Member refs only " +
            "(methods + constructors); type refs and field refs exit 3. " +
            "Exits 1 when the method is unknown or calls nothing, 2 on an ambiguous short name."

    private val ref by argument(help = "Method or constructor reference to list callees of (full or short form).")

    private val depth by option(
        "--depth",
        help = "Maximum transitive levels shown (default 1, direct callees only).",
    ).int().default(1)

    private val inArtifact by option(
        "--in",
        help = "Only callees whose artifact label matches this glob (jar file name, JDK module or class-dir name).",
    )

    private val exclude by option(
        "--exclude",
        help = "Skip callees whose artifact label matches this glob.",
    )

    private val externalOnly by option(
        "--external-only",
        help = "Hide callees in the queried method's own artifact (dependency view).",
    ).flag()

    private val limit by option(
        "--limit",
        help = "Maximum tree nodes shown (default 50); the rest become a truncation footer.",
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

    override fun run() {
        val json = effectiveJson(json)
        if (limit < 0) {
            val failure = JdxService.ServiceOutcome.Failure(
                ErrorResult.generic(ref, exitCode = 3, message = "usage error: --limit must be >= 0, got $limit"),
            )
            ReadCommandSupport.finish(failure, "calls", json, noColor, terminate)
            return
        }
        if (depth < 1) {
            val failure = JdxService.ServiceOutcome.Failure(
                ErrorResult.generic(ref, exitCode = 3, message = "usage error: --depth must be >= 1, got $depth"),
            )
            ReadCommandSupport.finish(failure, "calls", json, noColor, terminate)
            return
        }
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
                    JdxService.CallOptions(
                        depth = depth,
                        inArtifact = inArtifact,
                        exclude = exclude,
                        limit = limit,
                        externalOnly = externalOnly,
                    ),
                    dev.jdx.core.render.CallDirection.CALLS,
                )
                ReadCommandSupport.finish(outcome, "calls", json, noColor, terminate)
            }
            is ReadCommandSupport.RootsOrFailure.Failed ->
                ReadCommandSupport.finish(resolved.outcome, "calls", json, noColor, terminate)
        }
    }
}
