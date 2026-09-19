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
 * `jdx members <type>`. A thin adapter (D-004): parses the §7.1 flags, asks [JdxService]
 * for the member listing, renders it, and maps the outcome to an exit code (D-015).
 *
 * `--inherited` is the default: members from superclasses and interfaces are included,
 * grouped by declaring type, with `java.lang.Object` members collapsed to one summary
 * line. `--declared` shows only the type's own members (the `outline` command is that
 * mode under a shorter name).
 */
class MembersCommand(
    private val query: MemberQuery = ::defaultMemberQuery,
    private val terminate: (Int) -> Nothing = ::exitProcess,
    private val store: WorkspaceStore = FileWorkspaceStore.system(),
    private val getenv: (String) -> String? = { name -> System.getenv(name) },
    private val discover: ProjectDiscoveryFn? = null,
) : CoreCliktCommand(name = "members") {
    override fun help(context: Context): String =
        "List the members of a type — the '.' completion equivalent. Inherited members " +
            "are included by default (--inherited), grouped by declaring type; pass " +
            "--declared for only the type's own members. java.lang.Object members collapse " +
            "to one line unless --from java.lang.Object expands them. " +
            "Exits 1 when the type is unknown, 2 when a short name is ambiguous."

    private val ref by argument(help = "Type reference to list members of.")

    private val declared by option(
        "--declared",
        help = "Only members declared on this exact type (overrides the --inherited default).",
    ).flag()

    private val inherited by option(
        "--inherited",
        help = "Include inherited members (the default; accepted for explicitness).",
    ).flag()

    private val kind by option(
        "--kind",
        help = "Member kind to list: method, field, ctor or all.",
    ).choice("all", "method", "field", "ctor", ignoreCase = true).default("all")

    private val access by option(
        "--access",
        help = "Visibility to list: public, protected, package, private or all " +
            "(default: public + protected).",
    ).choice("public", "protected", "package", "private", "all", ignoreCase = true)

    private val staticOnly by option(
        "--static",
        help = "Only static members (mutually exclusive with --instance).",
    ).flag()

    private val instanceOnly by option(
        "--instance",
        help = "Only instance members (mutually exclusive with --static).",
    ).flag()

    private val from by option(
        "--from",
        help = "Only members inherited from a specific supertype (e.g. --from java.lang.Object).",
    )

    private val grep by option(
        "--grep",
        help = "Only members whose name matches this regex.",
    )

    private val includeSynthetic by option(
        "--include-synthetic",
        help = "Include bridge/synthetic members, hidden by default.",
    ).flag()

    private val limit by option(
        "--limit",
        help = "Maximum member rows shown (default 50); the rest become a truncation footer.",
    ).int().default(50)

    private val withDoc by option(
        "--with-doc",
        help = "Include the first javadoc sentence per member (not yet implemented, T-025).",
    ).flag()

    private val sort by option(
        "--sort",
        help = "Row order: kind (the default, kind-then-name in linearisation order); " +
            "name (flat name-first across kinds and groups); " +
            "declaring (alphabetical by declaring type).",
    ).choice("kind", "name", "declaring", ignoreCase = true).default("kind")

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
            "~/.gradle/caches and ~/.m2 first; with --fetch, downloaded from Maven Central " +
            "into ~/.cache/jdx/m2 with checksum verification. Merges in front of the workspace.",
    ).multiple()

    private val fetch by option(
        "--fetch",
        help = "Allow downloading --coord artifacts (and their -sources.jar) from Maven " +
            "Central. Without it, coordinates resolve from the local caches only.",
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
        val usageError = ReadCommandSupport.validateMemberFlags(
            static = staticOnly,
            instance = instanceOnly,
            grep = grep,
            withDoc = withDoc,
            sort = sort.lowercase(),
        )
        if (usageError != null) {
            val failure = JdxService.ServiceOutcome.Failure(
                ErrorResult.generic(ref, exitCode = 3, message = usageError),
            )
            ReadCommandSupport.finish(failure, "members", json, noColor, terminate)
            return
        }
        val filters = JdxService.MemberFilters(
            kind = ReadCommandSupport.kindOf(kind.lowercase()),
            access = ReadCommandSupport.accessOf(access?.lowercase()),
            staticOnly = when {
                staticOnly -> true
                instanceOnly -> false
                else -> null
            },
            fromRef = from,
            grep = grep?.let { Regex(it) },
            sort = ReadCommandSupport.sortOf(sort),
        )
        when (val resolved = ReadCommandSupport.resolveRoots(
            jars,
            noJdk,
            effectiveWorkspace(workspace),
            store,
            getenv,
            discover = discover ?: ReadCommandSupport::discoverProject,
            coords = coord,
            allowFetch = fetch,
        )) {
            is ReadCommandSupport.RootsOrFailure.Ready -> {
                val outcome = query(
                    ref,
                    resolved.roots,
                    filters,
                    declared,
                    includeSynthetic,
                    limit,
                )
                ReadCommandSupport.finish(outcome, "members", json, noColor, terminate)
            }
            is ReadCommandSupport.RootsOrFailure.Failed ->
                ReadCommandSupport.finish(resolved.outcome, "members", json, noColor, terminate)
        }
    }
}

/**
 * `jdx outline <type>`. The *File Structure* popup: one dense line per member declared
 * on the type itself — `members --declared` under a shorter name, with the same filters.
 */
class OutlineCommand(
    private val query: MemberQuery = ::defaultMemberQuery,
    private val terminate: (Int) -> Nothing = ::exitProcess,
    private val store: WorkspaceStore = FileWorkspaceStore.system(),
    private val getenv: (String) -> String? = { name -> System.getenv(name) },
    private val discover: ProjectDiscoveryFn? = null,
) : CoreCliktCommand(name = "outline") {
    override fun help(context: Context): String =
        "Outline a type: one dense line per member declared on it (never inherited " +
            "members). Same filters as members --declared. " +
            "Exits 1 when the type is unknown, 2 when a short name is ambiguous."

    private val ref by argument(help = "Type reference to outline.")

    private val kind by option(
        "--kind",
        help = "Member kind to list: method, field, ctor or all.",
    ).choice("all", "method", "field", "ctor", ignoreCase = true).default("all")

    private val access by option(
        "--access",
        help = "Visibility to list: public, protected, package, private or all " +
            "(default: public + protected).",
    ).choice("public", "protected", "package", "private", "all", ignoreCase = true)

    private val staticOnly by option(
        "--static",
        help = "Only static members (mutually exclusive with --instance).",
    ).flag()

    private val instanceOnly by option(
        "--instance",
        help = "Only instance members (mutually exclusive with --static).",
    ).flag()

    private val from by option(
        "--from",
        help = "Only members inherited from a specific supertype (rarely useful with outline).",
    )

    private val grep by option(
        "--grep",
        help = "Only members whose name matches this regex.",
    )

    private val includeSynthetic by option(
        "--include-synthetic",
        help = "Include bridge/synthetic members, hidden by default.",
    ).flag()

    private val limit by option(
        "--limit",
        help = "Maximum member rows shown (default 50); the rest become a truncation footer.",
    ).int().default(50)

    private val withDoc by option(
        "--with-doc",
        help = "Include the first javadoc sentence per member (not yet implemented, T-025).",
    ).flag()

    private val sort by option(
        "--sort",
        help = "Row order: kind (the default, kind-then-name in linearisation order); " +
            "name (flat name-first across kinds and groups); " +
            "declaring (alphabetical by declaring type).",
    ).choice("kind", "name", "declaring", ignoreCase = true).default("kind")

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
            "~/.gradle/caches and ~/.m2 first; with --fetch, downloaded from Maven Central " +
            "into ~/.cache/jdx/m2 with checksum verification. Merges in front of the workspace.",
    ).multiple()

    private val fetch by option(
        "--fetch",
        help = "Allow downloading --coord artifacts (and their -sources.jar) from Maven " +
            "Central. Without it, coordinates resolve from the local caches only.",
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
        val usageError = ReadCommandSupport.validateMemberFlags(
            static = staticOnly,
            instance = instanceOnly,
            grep = grep,
            withDoc = withDoc,
            sort = sort.lowercase(),
        )
        if (usageError != null) {
            val failure = JdxService.ServiceOutcome.Failure(
                ErrorResult.generic(ref, exitCode = 3, message = usageError),
            )
            ReadCommandSupport.finish(failure, "outline", json, noColor, terminate)
            return
        }
        val filters = JdxService.MemberFilters(
            kind = ReadCommandSupport.kindOf(kind.lowercase()),
            access = ReadCommandSupport.accessOf(access?.lowercase()),
            staticOnly = when {
                staticOnly -> true
                instanceOnly -> false
                else -> null
            },
            fromRef = from,
            grep = grep?.let { Regex(it) },
            sort = ReadCommandSupport.sortOf(sort),
        )
        when (val resolved = ReadCommandSupport.resolveRoots(
            jars,
            noJdk,
            effectiveWorkspace(workspace),
            store,
            getenv,
            discover = discover ?: ReadCommandSupport::discoverProject,
            coords = coord,
            allowFetch = fetch,
        )) {
            is ReadCommandSupport.RootsOrFailure.Ready -> {
                val outcome = query(
                    ref,
                    resolved.roots,
                    filters,
                    true,
                    includeSynthetic,
                    limit,
                )
                ReadCommandSupport.finish(outcome, "outline", json, noColor, terminate)
            }
            is ReadCommandSupport.RootsOrFailure.Failed ->
                ReadCommandSupport.finish(resolved.outcome, "outline", json, noColor, terminate)
        }
    }
}
