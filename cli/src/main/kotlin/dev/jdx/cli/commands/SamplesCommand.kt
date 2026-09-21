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
 * `jdx samples <symbol>`. A thin adapter (D-004): parses the §7.3 flags, asks
 * [JdxService] for the ranked usage examples, renders them, and maps the
 * outcome to an exit code (D-015).
 *
 * Real call sites as usage examples (T-034): incoming `METHOD_CALL` edges
 * ranked by exemplariness (non-test before test, non-generated before
 * generated, fuller overloads first), each with the caller's enclosing-method
 * source when its paired sources exist — snippet-less otherwise. Type refs
 * match calls to any member; member refs are overload-blind unless the ref
 * carries a parameter list. Exits 1 when the symbol is unknown or unsampled,
 * 2 on an ambiguous short name.
 */
class SamplesCommand(
    private val query: SamplesQuery = ::defaultSamplesQuery,
    private val terminate: (Int) -> Nothing = ::exitProcess,
    private val store: WorkspaceStore = FileWorkspaceStore.system(),
    private val getenv: (String) -> String? = { name -> System.getenv(name) },
    private val discover: ProjectDiscoveryFn? = null,
) : CoreCliktCommand(name = "samples") {
    override fun help(context: Context): String =
        "Show real call sites as usage examples, ranked by exemplariness " +
            "(non-test before test, non-generated before generated, fuller overloads first): " +
            "one row per calling method with its enclosing-method source when paired sources " +
            "exist, snippet-less otherwise. --limit caps the ranked rows (default 3); " +
            "--prefer-sources ranks callers from sources-paired artifacts first; " +
            "--in/--exclude filter rows by artifact label (jar file, JDK module " +
            "or class-dir name). Type refs match calls to any member; field refs exit 3 " +
            "(examples need a call site: jdx usages shows reads and writes). " +
            "Exits 1 when the symbol is unknown or unsampled, 2 on an ambiguous short name."

    private val ref by argument(help = "Type or member reference to show usage examples of (full or short form).")

    private val limit by option(
        "--limit",
        help = "Maximum examples shown (default 3); the rest become a truncation footer.",
    ).int().default(3)

    private val inArtifact by option(
        "--in",
        help = "Only examples whose artifact label matches this glob (jar file name, JDK module or class-dir name).",
    )

    private val exclude by option(
        "--exclude",
        help = "Skip examples whose artifact label matches this glob.",
    )

    private val preferSources by option(
        "--prefer-sources",
        help = "Rank examples from sources-paired artifacts (rendered with snippets) ahead of snippet-less ones.",
    ).flag()

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
            ReadCommandSupport.finish(failure, "samples", json, noColor, terminate)
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
                    JdxService.SampleOptions(
                        limit = limit,
                        inArtifact = inArtifact,
                        exclude = exclude,
                        preferSources = preferSources,
                    ),
                )
                ReadCommandSupport.finish(outcome, "samples", json, noColor, terminate)
            }
            is ReadCommandSupport.RootsOrFailure.Failed ->
                ReadCommandSupport.finish(resolved.outcome, "samples", json, noColor, terminate)
        }
    }
}
