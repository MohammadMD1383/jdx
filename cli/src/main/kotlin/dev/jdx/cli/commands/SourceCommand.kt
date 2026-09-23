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
import dev.jdx.cli.DaemonClient
import dev.jdx.cli.DaemonRoundTrip
import dev.jdx.cli.WarmRoots
import dev.jdx.cli.defaultRoundTrip
import dev.jdx.cli.effectiveJson
import dev.jdx.cli.effectiveNoDaemon
import dev.jdx.cli.effectiveWorkspace
import dev.jdx.core.render.ErrorResult
import dev.jdx.decompile.DecompilerId
import dev.jdx.index.service.JdxService
import dev.jdx.index.workspace.FileWorkspaceStore
import dev.jdx.index.workspace.WorkspaceStore
import dev.jdx.server.DaemonPaths
import java.nio.file.Path
import kotlin.system.exitProcess

/**
 * `jdx source <type>`. A thin adapter (D-004): parses the §7.1 flags, asks
 * [JdxService] for the type's verbatim source file or slice, renders it, and
 * maps the outcome to an exit code (D-015).
 *
 * Sources come from the paired `-sources.jar` (ground truth, T-021/T-023), or —
 * without paired sources — from the Vineflower reconstruction (T-026), always
 * labelled as such. A short name with several candidates exits 2 with
 * every candidate as a copy-pasteable canonical ref (D-016).
 */
class SourceCommand(
    private val query: SourceQuery = ::defaultSourceQuery,
    private val terminate: (Int) -> Nothing = ::exitProcess,
    private val store: WorkspaceStore = FileWorkspaceStore.system(),
    private val getenv: (String) -> String? = { name -> System.getenv(name) },
    private val discover: ProjectDiscoveryFn? = null,
    private val daemonRuntimeDir: Path? = DaemonPaths.systemRuntimeDir(),
    private val daemonRoundTrip: DaemonRoundTrip = defaultRoundTrip,
) : CoreCliktCommand(name = "source") {
    override fun help(context: Context): String =
        "Show a type's source: the verbatim file with 1-based line numbers " +
            "plus provenance (artifact, file, lines). Takes a type reference " +
            "(e.g. 'com.example.Point', 'Gson'). Slice it with --lines A:B or " +
            "center on a member with --around '<type>#<member>' --context N. " +
            "Without paired sources the query names the decompiler tasks instead " +
            "of guessing. Exits 1 when the type or its sources are unknown, " +
            "2 when ambiguous, 4 when the workspace cannot be resolved."

    private val ref by argument(help = "Type reference to show the source of (full or short form).")

    private val lines by option(
        "--lines",
        help = "Show only lines A:B (1-based inclusive, e.g. --lines 100:180). " +
            "Mutually exclusive with --around.",
    )

    private val around by option(
        "--around",
        help = "Center the slice on a member reference (e.g. --around 'Point#getX()'); " +
            "--context N expands it each side.",
    )

    private val context by option(
        "--context",
        help = "Surrounding source lines shown each side of the --around member (default 0).",
    ).int().default(0)

    private val lineNumbers by option(
        "--line-numbers",
        help = "Prefix each shown line with its 1-based number.",
    ).flag()

    private val maxLines by option(
        "--max-lines",
        help = "Maximum source lines shown (default 200); the rest become a truncation footer.",
    ).int().default(200)

    private val engine by option(
        "--engine",
        help = "Disassembly engine: vineflower forces reconstruction even when sources are " +
            "paired (default serves sources, falling back to vineflower); javap shows " +
            "the raw bytecode instead.",
    ).choice("vineflower", "javap", ignoreCase = true)

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

    private val noDaemon by option(
        "--no-daemon",
        help = "Force in-process execution: do not forward this query to the background " +
            "daemon even when its socket answers (PROPOSAL.md §14.1).",
    ).flag()

    override fun run() {
        val json = effectiveJson(json)
        val usageError = validateSourceFlags(
            lines = lines,
            around = around,
            context = context,
            maxLines = maxLines,
            engine = engine,
        )
        if (usageError != null) {
            val failure = JdxService.ServiceOutcome.Failure(
                ErrorResult.generic(ref, exitCode = 3, message = usageError),
            )
            ReadCommandSupport.finish(failure, "source", json, noColor, terminate)
            return
        }
        if (DaemonClient.serveWarmIfReady(
                request = DaemonClient.sourceRequest(
                    ref = ref,
                    lines = lines,
                    around = around,
                    context = context,
                    lineNumbers = lineNumbers,
                    maxLines = maxLines,
                    engine = engine,
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
                val window = lines?.let { parseLinesWindow(it) }
                val outcome = query(
                    ref,
                    resolved.roots,
                    JdxService.SourceOptions(
                        lines = window,
                        aroundRef = around,
                        contextLines = context,
                        lineNumbers = lineNumbers,
                        maxLines = maxLines,
                        engine = when {
                            engine.equals("vineflower", ignoreCase = true) -> DecompilerId.VINEFLOWER
                            engine.equals("javap", ignoreCase = true) -> DecompilerId.JAVAP
                            else -> null
                        },
                    ),
                )
                ReadCommandSupport.finish(outcome, "source", json, noColor, terminate)
            }
            is ReadCommandSupport.RootsOrFailure.Failed ->
                ReadCommandSupport.finish(resolved.outcome, "source", json, noColor, terminate)
        }
    }
}

/**
 * Parses a `--lines A:B` window into a 1-based inclusive pair, or `null`
 * when the text is not of that shape (surrounding whitespace tolerated;
 * anything else — including `A > B` or `A < 1` — is not a window).
 */
internal fun parseLinesWindow(text: String): Pair<Int, Int>? {
    val parts = text.trim().split(":")
    if (parts.size != 2) return null
    val first = parts[0].trim().toIntOrNull() ?: return null
    val second = parts[1].trim().toIntOrNull() ?: return null
    if (first < 1 || second < first) return null
    return first to second
}

/**
 * Validates the flag combinations that reach the service. Returns the usage error
 * message, or `null` when the flags are coherent. Deferred flags fail here with the
 * owning task named, per the T-011 acceptance rule.
 */
internal fun validateSourceFlags(
    lines: String?,
    around: String?,
    context: Int,
    maxLines: Int,
    engine: String?,
): String? {
    if (context < 0) return "usage error: --context must be >= 0, got $context"
    if (maxLines < 0) return "usage error: --max-lines must be >= 0, got $maxLines"
    if (lines != null && parseLinesWindow(lines) == null) {
        return "usage error: --lines must be A:B with 1 <= A <= B, got '$lines'"
    }
    if (lines != null && around != null) {
        return "usage error: --lines and --around are mutually exclusive"
    }
    if (lines == null && around == null && context != 0) {
        return "usage error: --context needs --around (a whole file has no center)"
    }
    if (engine != null &&
        !engine.equals("vineflower", ignoreCase = true) &&
        !engine.equals("javap", ignoreCase = true)
    ) {
        return "usage error: --engine $engine is not a known engine (vineflower|javap)"
    }
    return null
}
