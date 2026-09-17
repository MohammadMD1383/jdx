package dev.jdx.cli.commands

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.CoreCliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.int
import dev.jdx.cli.effectiveJson
import dev.jdx.cli.effectiveWorkspace
import dev.jdx.index.service.JdxService
import dev.jdx.index.workspace.FileWorkspaceStore
import dev.jdx.index.workspace.WorkspaceStore
import kotlin.system.exitProcess

/**
 * `jdx search <pattern>`. A thin adapter (D-004): parses the §7.2 flags, asks
 * [JdxService] for the symbol hits, renders them, and maps the outcome to an
 * exit code (D-015).
 *
 * Matching is glob by default (`*Http*Client`), `--regex` for regexes, and
 * bare words match case-insensitively or as IntelliJ-style camel humps
 * (`HMap` → `HashMap`); `--fuzzy` retries misses by Levenshtein distance.
 * Exits 1 when nothing matches (with did-you-mean), never 2 — several hits
 * are success here, not ambiguity.
 */
class SearchCommand(
    private val query: SearchQuery = ::defaultSearchQuery,
    private val terminate: (Int) -> Nothing = ::exitProcess,
    private val store: WorkspaceStore = FileWorkspaceStore.system(),
    private val getenv: (String) -> String? = { name -> System.getenv(name) },
    private val discover: ProjectDiscoveryFn? = null,
) : CoreCliktCommand(name = "search") {
    override fun help(context: Context): String =
        "Search symbols across the workspace: types, members, packages and JDK modules. " +
            "Glob by default ('*Http*Client'), --regex for regexes, bare words match " +
            "case-insensitively or as camel humps (HMap finds HashMap). " +
            "--kind narrows to class|interface|enum|record|annotation|method|field|package|module " +
            "(default: types, packages and modules; pass method|field to scan members). " +
            "--fuzzy retries a miss by Levenshtein distance. " +
            "Exits 1 when nothing matches."

    private val pattern by argument(help = "Search pattern: glob, --regex regex, or bare word.")

    private val kind by option(
        "--kind",
        help = "Symbol kind to search: all, type, class, interface, enum, record, annotation, " +
            "method, field, package or module.",
    ).choice(
        "all", "type", "class", "interface", "enum", "record", "annotation",
        "object", "companion", "method", "field", "package", "module",
        ignoreCase = true,
    ).default("all")

    private val regex by option(
        "--regex",
        help = "Treat the pattern as a regex instead of a glob.",
    ).flag()

    private val fuzzy by option(
        "--fuzzy",
        help = "Levenshtein fallback: when nothing matches, retry within two edits.",
    ).flag()

    private val inArtifact by option(
        "--in",
        help = "Only artifacts whose label matches this glob (jar file name or JDK module).",
    )

    private val inPackage by option(
        "--package",
        help = "Only symbols in packages matching this glob.",
    )

    private val limit by option(
        "--limit",
        help = "Maximum hits shown (default 50); the rest become a truncation footer.",
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
        when (val resolved = ReadCommandSupport.resolveRoots(
            jars,
            noJdk,
            effectiveWorkspace(workspace),
            store,
            getenv,
            discover = discover ?: ReadCommandSupport::discoverProject,
        )) {
            is ReadCommandSupport.RootsOrFailure.Ready -> {
                val outcome = query(
                    pattern,
                    resolved.roots,
                    JdxService.SearchOptions(
                        kind = searchKindOf(kind.lowercase()),
                        regex = regex,
                        fuzzy = fuzzy,
                        inArtifact = inArtifact,
                        inPackage = inPackage,
                        limit = limit,
                    ),
                )
                ReadCommandSupport.finish(outcome, "search", json, noColor, terminate)
            }
            is ReadCommandSupport.RootsOrFailure.Failed ->
                ReadCommandSupport.finish(resolved.outcome, "search", json, noColor, terminate)
        }
    }
}

/**
 * `jdx resolve <name>`. "What is this identifier?" — exact-match
 * disambiguation listing every candidate with kind and artifact.
 */
class ResolveCommand(
    private val query: ResolveQuery = ::defaultResolveQuery,
    private val terminate: (Int) -> Nothing = ::exitProcess,
    private val store: WorkspaceStore = FileWorkspaceStore.system(),
    private val getenv: (String) -> String? = { name -> System.getenv(name) },
    private val discover: ProjectDiscoveryFn? = null,
) : CoreCliktCommand(name = "resolve") {
    override fun help(context: Context): String =
        "Resolve an unqualified or partial name to every candidate, each with kind and " +
            "artifact — the disambiguator for unknown symbols in stack traces and snippets. " +
            "Accepts 'Gson', 'com.google.gson.Gson', 'Gson#toJson' and package names. " +
            "Several candidates are success (exit 0); none is exit 1 with did-you-mean."

    private val name by argument(help = "Type, member (Type#member) or package name to resolve.")

    private val limit by option(
        "--limit",
        help = "Maximum candidates shown (default 50); the rest become a truncation footer.",
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
        when (val resolved = ReadCommandSupport.resolveRoots(
            jars,
            noJdk,
            effectiveWorkspace(workspace),
            store,
            getenv,
            discover = discover ?: ReadCommandSupport::discoverProject,
        )) {
            is ReadCommandSupport.RootsOrFailure.Ready -> {
                val outcome = query(name, resolved.roots, limit)
                ReadCommandSupport.finish(outcome, "resolve", json, noColor, terminate)
            }
            is ReadCommandSupport.RootsOrFailure.Failed ->
                ReadCommandSupport.finish(resolved.outcome, "resolve", json, noColor, terminate)
        }
    }
}

/**
 * `jdx ls [package-glob]`. Browse packages: a glob lists matching packages
 * with type counts; an exact package name lists the types inside it.
 */
class LsCommand(
    private val query: LsQuery = ::defaultLsQuery,
    private val terminate: (Int) -> Nothing = ::exitProcess,
    private val store: WorkspaceStore = FileWorkspaceStore.system(),
    private val getenv: (String) -> String? = { name -> System.getenv(name) },
    private val discover: ProjectDiscoveryFn? = null,
) : CoreCliktCommand(name = "ls") {
    override fun help(context: Context): String =
        "List packages in the workspace ('com.google.*') with type counts; " +
            "an exact package name ('com.google.gson') lists the types inside it. " +
            "Exits 1 when nothing matches."

    private val packageGlob by argument(
        help = "Package glob (default '*' — every package), or one exact package to list types for.",
    ).optional()

    private val limit by option(
        "--limit",
        help = "Maximum rows shown (default 50); the rest become a truncation footer.",
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
        when (val resolved = ReadCommandSupport.resolveRoots(
            jars,
            noJdk,
            effectiveWorkspace(workspace),
            store,
            getenv,
            discover = discover ?: ReadCommandSupport::discoverProject,
        )) {
            is ReadCommandSupport.RootsOrFailure.Ready -> {
                val outcome = query(packageGlob, resolved.roots, limit)
                ReadCommandSupport.finish(outcome, "ls", json, noColor, terminate)
            }
            is ReadCommandSupport.RootsOrFailure.Failed ->
                ReadCommandSupport.finish(resolved.outcome, "ls", json, noColor, terminate)
        }
    }
}

/**
 * `jdx tree [artifact-glob]`. Browse an artifact's package forest, nested to
 * `--depth`, with per-package subtree counts under `--counts`.
 */
class TreeCommand(
    private val query: TreeQuery = ::defaultTreeQuery,
    private val terminate: (Int) -> Nothing = ::exitProcess,
    private val store: WorkspaceStore = FileWorkspaceStore.system(),
    private val getenv: (String) -> String? = { name -> System.getenv(name) },
    private val discover: ProjectDiscoveryFn? = null,
) : CoreCliktCommand(name = "tree") {
    override fun help(context: Context): String =
        "Show the package forest of each matching artifact (jar file name or JDK module; " +
            "default '*' — every artifact). --depth caps nesting (0 shows top segments only), " +
            "--counts adds per-package subtree type counts. Exits 1 when no artifact matches."

    private val artifact by argument(
        help = "Artifact glob: jar file name or JDK module (default '*' — every artifact).",
    ).optional()

    private val depth by option(
        "--depth",
        help = "Maximum package nesting shown (default 8; 0 shows top-level segments only).",
    ).int().default(8)

    private val counts by option(
        "--counts",
        help = "Show per-package subtree type counts.",
    ).flag()

    private val limit by option(
        "--limit",
        help = "Maximum package nodes shown (default 50); the rest become a truncation footer.",
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
        when (val resolved = ReadCommandSupport.resolveRoots(
            jars,
            noJdk,
            effectiveWorkspace(workspace),
            store,
            getenv,
            discover = discover ?: ReadCommandSupport::discoverProject,
        )) {
            is ReadCommandSupport.RootsOrFailure.Ready -> {
                val outcome = query(artifact, resolved.roots, depth, counts, limit)
                ReadCommandSupport.finish(outcome, "tree", json, noColor, terminate)
            }
            is ReadCommandSupport.RootsOrFailure.Failed ->
                ReadCommandSupport.finish(resolved.outcome, "tree", json, noColor, terminate)
        }
    }
}

/** Maps `--kind` to the service filter; Clikt's `choice()` guarantees the input range. */
internal fun searchKindOf(kind: String): JdxService.SearchKindFilter = when (kind) {
    "type" -> JdxService.SearchKindFilter.TYPE
    "class" -> JdxService.SearchKindFilter.CLASS
    "interface" -> JdxService.SearchKindFilter.INTERFACE
    "enum" -> JdxService.SearchKindFilter.ENUM
    "record" -> JdxService.SearchKindFilter.RECORD
    "annotation" -> JdxService.SearchKindFilter.ANNOTATION
    "object" -> JdxService.SearchKindFilter.OBJECT
    "companion" -> JdxService.SearchKindFilter.COMPANION
    "method" -> JdxService.SearchKindFilter.METHOD
    "field" -> JdxService.SearchKindFilter.FIELD
    "package" -> JdxService.SearchKindFilter.PACKAGE
    "module" -> JdxService.SearchKindFilter.MODULE
    else -> JdxService.SearchKindFilter.ALL
}
