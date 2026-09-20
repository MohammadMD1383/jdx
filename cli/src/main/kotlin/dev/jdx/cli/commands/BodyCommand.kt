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
 * `jdx body <member>`. A thin adapter (D-004): parses the §7.1 flags, asks [JdxService]
 * for the member's verbatim source slice, renders it, and maps the outcome to an exit
 * code (D-015).
 *
 * Bodies come from the paired `-sources.jar` (ground truth, T-021/T-022). Without
 * paired sources the answer names the decompiler tasks (T-026/T-027) instead of
 * guessing. A bare `Type#name` with several overloads exits 2 with every candidate
 * as a copy-pasteable canonical ref (D-016).
 */
class BodyCommand(
    private val query: BodyQuery = ::defaultBodyQuery,
    private val terminate: (Int) -> Nothing = ::exitProcess,
    private val store: WorkspaceStore = FileWorkspaceStore.system(),
    private val getenv: (String) -> String? = { name -> System.getenv(name) },
    private val discover: ProjectDiscoveryFn? = null,
) : CoreCliktCommand(name = "body") {
    override fun help(context: Context): String =
        "Show the body of a member: the verbatim source slice with 1-based line numbers " +
            "plus provenance (artifact, file, lines). Takes a member reference " +
            "(e.g. 'com.example.Point#getX()', \"Gson#toJson(Object)\"). " +
            "An under-specified name with several overloads exits 2 with candidates. " +
            "Without paired sources the query names the decompiler tasks instead of guessing. " +
            "Exits 1 when the member or its sources are unknown, 2 when ambiguous, " +
            "4 when the workspace cannot be resolved."

    private val ref by argument(help = "Member reference to show the body of (full or short form).")

    private val context by option(
        "--context",
        help = "Surrounding source lines shown each side of the member (default 0).",
    ).int().default(0)

    private val lineNumbers by option(
        "--line-numbers",
        help = "Prefix each shown line with its 1-based number.",
    ).flag()

    private val maxLines by option(
        "--max-lines",
        help = "Maximum body lines shown (default 200); the rest become a truncation footer.",
    ).int().default(200)

    private val engine by option(
        "--engine",
        help = "Decompiler engine: vineflower or javap (not yet implemented, T-026/T-027).",
    ).choice("vineflower", "javap", ignoreCase = true)

    private val withDoc by option(
        "--with-doc",
        help = "Include the member's javadoc (not yet implemented, T-025).",
    ).flag()

    private val withSignature by option(
        "--with-signature",
        help = "Prepend the resolved bytecode signature header to the body.",
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
        val usageError = validateBodyFlags(
            context = context,
            maxLines = maxLines,
            engine = engine,
            withDoc = withDoc,
        )
        if (usageError != null) {
            val failure = JdxService.ServiceOutcome.Failure(
                ErrorResult.generic(ref, exitCode = 3, message = usageError),
            )
            ReadCommandSupport.finish(failure, "body", json, noColor, terminate)
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
                    JdxService.BodyOptions(
                        contextLines = context,
                        lineNumbers = lineNumbers,
                        maxLines = maxLines,
                        withSignature = withSignature,
                    ),
                )
                ReadCommandSupport.finish(outcome, "body", json, noColor, terminate)
            }
            is ReadCommandSupport.RootsOrFailure.Failed ->
                ReadCommandSupport.finish(resolved.outcome, "body", json, noColor, terminate)
        }
    }
}

/**
 * Validates the flag combinations that reach the service. Returns the usage error
 * message, or `null` when the flags are coherent. Deferred flags fail here with the
 * owning task named, per the T-011 acceptance rule.
 */
internal fun validateBodyFlags(
    context: Int,
    maxLines: Int,
    engine: String?,
    withDoc: Boolean,
): String? {
    if (context < 0) return "usage error: --context must be >= 0, got $context"
    if (maxLines < 0) return "usage error: --max-lines must be >= 0, got $maxLines"
    if (engine != null) {
        return "usage error: --engine $engine is not yet implemented " +
            "(decompilation: T-026 Vineflower, T-027 javap)"
    }
    if (withDoc) {
        return "usage error: --with-doc is not yet implemented (T-025: javadoc/KDoc rendering)"
    }
    return null
}
