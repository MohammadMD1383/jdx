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
import dev.jdx.cli.effectiveJson
import dev.jdx.cli.effectiveWorkspace
import dev.jdx.core.render.ErrorResult
import dev.jdx.index.service.JdxService
import dev.jdx.index.workspace.FileWorkspaceStore
import dev.jdx.index.workspace.WorkspaceStore
import kotlin.system.exitProcess

/**
 * `jdx usages <symbol>`. A thin adapter (D-004): parses the §7.3 flags, asks
 * [JdxService] for the referencing methods, renders them, and maps the
 * outcome to an exit code (D-015).
 *
 * Find Usages across the workspace's bytecode roots plus project source dirs
 * (T-030/T-031): one row per referencing method (bytecode) or mentioning line
 * (source dirs, ref kind), grouped by artifact. Type refs match every edge to the
 * type; member refs match by name (overload-blind) unless the ref carries a
 * parameter list. Exits 1 when the symbol is unknown or has no usages.
 */
class UsagesCommand(
    private val query: UsagesQuery = ::defaultUsagesQuery,
    private val terminate: (Int) -> Nothing = ::exitProcess,
    private val store: WorkspaceStore = FileWorkspaceStore.system(),
    private val getenv: (String) -> String? = { name -> System.getenv(name) },
    private val discover: ProjectDiscoveryFn? = null,
) : CoreCliktCommand(name = "usages") {
    override fun help(context: Context): String =
        "Find usages of a type or member across the workspace's bytecode roots plus " +
            "project source dirs: one row per referencing method (bytecode) or mentioning " +
            "line (source dirs, ref kind). " +
            "--kind narrows to call|read|write|ref|new|throw|annotation (default: all); " +
            "new shows constructor <init> call sites, throw shows methods declaring " +
            "the type in throws, annotation shows annotated classes and members; " +
            "source-dir hits are textual mentions (ref only), so call|read|write|new|throw|annotation " +
            "show bytecode-backed edges alone. " +
            "impl|override land with hierarchy (jdx hierarchy, jdx implementors). " +
            "--in/--exclude filter by artifact label (jar file, JDK module or source-dir name). " +
            "--src adds a source dir root (repeatable; stored via jdx ws create --src). " +
            "Exits 1 when the symbol is unknown or unused, 2 on an ambiguous short name."

    private val ref by argument(help = "Type or member reference to find usages of (full or short form).")

    private val kind by option(
        "--kind",
        help = "Edge kind: call, read, write, ref, new, throw, annotation or all (default all). " +
            "impl|override land with hierarchy (jdx hierarchy, jdx implementors).",
    ).choice(
        "all", "call", "read", "write", "ref",
        "impl", "override", "new", "throw", "annotation",
        ignoreCase = true,
    ).default("all")

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
        help = "Maximum usages shown (default 50); the rest become a truncation footer.",
    ).int().default(50)

    private val context by option(
        "--context",
        help = "Source lines around each call site (not supported: source-rendered call sites live in jdx samples).",
    ).int().default(0)

    private val jars by option(
        "--jars",
        help = "Binary roots: jar files, class directories or globs (repeatable). " +
            "Merge in front of the selected workspace's roots.",
    ).multiple()

    private val srcs by option(
        "--src",
        help = "Source-dir roots: directories of .java/.kt files scanned textually for " +
            "whole-word mentions (ref kind, from <relpath>:<line>). Merge in front of " +
            "the selected workspace's stored srcs.",
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
            ReadCommandSupport.finish(failure, "usages", json, noColor, terminate)
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
            srcs = srcs,
        )) {
            is ReadCommandSupport.RootsOrFailure.Ready -> {
                val outcome = query(
                    ref,
                    resolved.roots,
                    JdxService.UsageOptions(
                        kind = usageKindOf(kind.lowercase()),
                        inArtifact = inArtifact,
                        exclude = exclude,
                        limit = limit,
                        contextLines = context,
                    ),
                )
                ReadCommandSupport.finish(outcome, "usages", json, noColor, terminate)
            }
            is ReadCommandSupport.RootsOrFailure.Failed ->
                ReadCommandSupport.finish(resolved.outcome, "usages", json, noColor, terminate)
        }
    }
}

/** Maps `--kind` to the service filter; Clikt's `choice()` guarantees the input range. */
internal fun usageKindOf(kind: String): JdxService.UsageKindFilter = when (kind) {
    "call" -> JdxService.UsageKindFilter.CALL
    "read" -> JdxService.UsageKindFilter.READ
    "write" -> JdxService.UsageKindFilter.WRITE
    "ref" -> JdxService.UsageKindFilter.REF
    "impl" -> JdxService.UsageKindFilter.IMPL
    "override" -> JdxService.UsageKindFilter.OVERRIDE
    "new" -> JdxService.UsageKindFilter.NEW
    "throw" -> JdxService.UsageKindFilter.THROW
    "annotation" -> JdxService.UsageKindFilter.ANNOTATION
    else -> JdxService.UsageKindFilter.ALL
}
