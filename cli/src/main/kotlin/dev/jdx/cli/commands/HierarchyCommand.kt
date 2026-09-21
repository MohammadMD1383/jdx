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
 * `jdx hierarchy <type>`. A thin adapter (D-004): parses the §7.3 flags, asks
 * [JdxService] for the supertype chain and workspace subtypes, renders them,
 * and maps the outcome to an exit code (D-015).
 *
 * Supertypes upward (full transitive chain, interfaces included) and
 * subtypes/implementors downward across the whole workspace (T-032): the
 * upward lineage always shows, `--in`/`--exclude` scope the downward scan.
 * Member refs exit 3 — hierarchy takes a type. Exits 0 once the type
 * resolves, even with empty sections.
 */
class HierarchyCommand(
    private val query: HierarchyQuery = ::defaultHierarchyQuery,
    private val terminate: (Int) -> Nothing = ::exitProcess,
    private val store: WorkspaceStore = FileWorkspaceStore.system(),
    private val getenv: (String) -> String? = { name -> System.getenv(name) },
    private val discover: ProjectDiscoveryFn? = null,
) : CoreCliktCommand(name = "hierarchy") {
    override fun help(context: Context): String =
        "Show a type's hierarchy: supertypes upward (full transitive chain, " +
            "interfaces included) and subtypes/implementors downward across the " +
            "workspace. --up/--down select directions (default both), --direct " +
            "shows one level, --depth N caps transitive levels, --in/--exclude " +
            "filter subtypes by artifact label. Member refs exit 3 (takes a " +
            "type); unknown types exit 1, ambiguous short names exit 2."

    private val ref by argument(help = "Type reference to show the hierarchy of (full or short form).")

    private val up by option(
        "--up",
        help = "Show supertypes upward (default when neither --up nor --down is given).",
    ).flag()

    private val down by option(
        "--down",
        help = "Show subtypes downward (default when neither --up nor --down is given).",
    ).flag()

    private val direct by option(
        "--direct",
        help = "Show one level only (direct supertypes and direct subtypes).",
    ).flag()

    private val depth by option(
        "--depth",
        help = "Maximum transitive levels shown (default unlimited).",
    ).int().default(Int.MAX_VALUE)

    private val inArtifact by option(
        "--in",
        help = "Only subtypes whose artifact label matches this glob (jar file name, JDK module or class-dir name).",
    )

    private val exclude by option(
        "--exclude",
        help = "Skip subtypes whose artifact label matches this glob.",
    )

    private val limit by option(
        "--limit",
        help = "Maximum subtypes shown (default 50); the rest become a truncation footer.",
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
            ReadCommandSupport.finish(failure, "hierarchy", json, noColor, terminate)
            return
        }
        if (depth < 1) {
            val failure = JdxService.ServiceOutcome.Failure(
                ErrorResult.generic(ref, exitCode = 3, message = "usage error: --depth must be >= 1, got $depth"),
            )
            ReadCommandSupport.finish(failure, "hierarchy", json, noColor, terminate)
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
                    JdxService.HierarchyOptions(
                        up = up || !down,
                        down = down || !up,
                        directOnly = direct,
                        depth = depth,
                        inArtifact = inArtifact,
                        exclude = exclude,
                        limit = limit,
                    ),
                )
                ReadCommandSupport.finish(outcome, "hierarchy", json, noColor, terminate)
            }
            is ReadCommandSupport.RootsOrFailure.Failed ->
                ReadCommandSupport.finish(resolved.outcome, "hierarchy", json, noColor, terminate)
        }
    }
}

/**
 * `jdx implementors <type>`. A thin alias for `hierarchy --down` (PROPOSAL.md
 * §7.3): the downward workspace scan only, with the same filters.
 */
class ImplementorsCommand(
    private val query: HierarchyQuery = ::defaultHierarchyQuery,
    private val terminate: (Int) -> Nothing = ::exitProcess,
    private val store: WorkspaceStore = FileWorkspaceStore.system(),
    private val getenv: (String) -> String? = { name -> System.getenv(name) },
    private val discover: ProjectDiscoveryFn? = null,
) : CoreCliktCommand(name = "implementors") {
    override fun help(context: Context): String =
        "List a type's implementors/subtypes across the workspace (alias for " +
            "`hierarchy --down`). --direct shows direct subtypes only, --depth N " +
            "caps transitive levels, --in/--exclude filter by artifact label."

    private val ref by argument(help = "Type reference to list implementors of (full or short form).")

    private val direct by option(
        "--direct",
        help = "Show direct subtypes only.",
    ).flag()

    private val depth by option(
        "--depth",
        help = "Maximum transitive levels shown (default unlimited).",
    ).int().default(Int.MAX_VALUE)

    private val inArtifact by option(
        "--in",
        help = "Only subtypes whose artifact label matches this glob (jar file name, JDK module or class-dir name).",
    )

    private val exclude by option(
        "--exclude",
        help = "Skip subtypes whose artifact label matches this glob.",
    )

    private val limit by option(
        "--limit",
        help = "Maximum subtypes shown (default 50); the rest become a truncation footer.",
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
            ReadCommandSupport.finish(failure, "implementors", json, noColor, terminate)
            return
        }
        if (depth < 1) {
            val failure = JdxService.ServiceOutcome.Failure(
                ErrorResult.generic(ref, exitCode = 3, message = "usage error: --depth must be >= 1, got $depth"),
            )
            ReadCommandSupport.finish(failure, "implementors", json, noColor, terminate)
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
                    JdxService.HierarchyOptions(
                        up = false,
                        down = true,
                        directOnly = direct,
                        depth = depth,
                        inArtifact = inArtifact,
                        exclude = exclude,
                        limit = limit,
                    ),
                )
                ReadCommandSupport.finish(outcome, "implementors", json, noColor, terminate)
            }
            is ReadCommandSupport.RootsOrFailure.Failed ->
                ReadCommandSupport.finish(resolved.outcome, "implementors", json, noColor, terminate)
        }
    }
}
