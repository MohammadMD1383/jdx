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
import dev.jdx.core.diff.ApiSurface
import dev.jdx.core.diff.FailOn
import dev.jdx.core.diff.SeverityFilter
import dev.jdx.core.render.DEFAULT_DIFF_LIMIT
import dev.jdx.core.render.ErrorResult
import dev.jdx.index.service.JdxService
import kotlin.system.exitProcess

/**
 * `jdx diff <old> <new>`. A thin adapter (D-004): parses the two artifact specs and the
 * §21 flags, asks [JdxService] to compare them, renders the report, and maps the
 * outcome to an exit code (D-015).
 *
 * Two deliberate departures from every other read command, both forced by what a diff
 * *is* rather than taste:
 *
 * - **No classpath, no workspace, no `--jars`, no `--no-jdk`.** A diff names its own two
 *   artifacts, so there is no classpath to resolve. `jdx diff` works in an empty
 *   directory with no workspace ever created, which is the normal way an agent meets it.
 * - **No daemon forwarding, and no `--no-daemon` flag.** A daemon warm on *one*
 *   workspace's classpath holds nothing a two-artifact comparison can reuse — the jars,
 *   the ASM reads and the differ are all per-invocation — and a `--fail-on` verdict
 *   would have no way back over the wire, since the v1 transport carries an exit code
 *   only as an error. So the flag would be a lie, and it is absent rather than inert.
 *   The daemon still *serves* `diff`: the MCP tool and `POST /v1/diff` dispatch it over
 *   the same [JdxService] call, byte-identically.
 *
 * Structure comes from bytecode alone: no sources jar is read and nothing is
 * decompiled, because an API diff has no "flesh" to recover — the same truth model as
 * every other command (PROPOSAL.md §5).
 */
class DiffCommand(
    private val query: DiffQuery = ::defaultDiffQuery,
    private val terminate: (Int) -> Nothing = ::exitProcess,
) : CoreCliktCommand(name = "diff") {
    override fun help(context: Context): String =
        "Compare the public API of two artifacts and report every difference, most " +
            "breaking first: removed members, narrowed visibility, changed descriptors, " +
            "final/abstract/static flips, throws clauses and Kotlin-visible changes. " +
            "Each finding names one rule (MEMBER_REMOVED, RETURN_TYPE_CHANGED, ...) so a " +
            "caller can filter on the rule rather than on prose. " +
            "Exits 0 whenever the comparison ran, whatever it found — add --fail-on " +
            "breaking (or --fail-on any) to exit 1 in a pipeline. " +
            "Exits 3 on a bad spec or flag, 5 when an artifact cannot be read. " +
            "Each spec is a jar file, class directory, glob, or Maven coordinate " +
            "(with --fetch). No workspace is needed."

    private val oldArtifact by argument(help = "Baseline artifact: jar, class dir, glob, or group:artifact:version.")

    private val newArtifact by argument(help = "Candidate artifact, compared against the baseline.")

    private val visibility by option(
        "--visibility",
        help = "Which declarations to compare: public (the default — public and protected " +
            "types and members, the surface a consumer compiles against) or all (every " +
            "declared type and member, for refactors and mod/mixin work).",
    ).choice("public", "all", ignoreCase = true).default("public")

    private val includeSynthetic by option(
        "--include-synthetic",
        help = "Compare bridge/synthetic members too, hidden by default.",
    ).flag()

    private val severity by option(
        "--severity",
        help = "How much of the report to print: all (the default), suspicious (breaking " +
            "and suspicious), or breaking only. The per-severity tally always describes " +
            "the whole comparison, never only what was printed.",
    ).choice("all", "suspicious", "breaking", ignoreCase = true).default("all")

    private val failOn by option(
        "--fail-on",
        help = "Turn the report into a process status: breaking exits 1 when a breaking " +
            "change is present, any exits 1 on any difference. Without it a successful " +
            "comparison always exits 0.",
    ).choice("none", "breaking", "any", ignoreCase = true).default("none")

    private val limit by option(
        "--limit",
        help = "Maximum findings shown (default $DEFAULT_DIFF_LIMIT); the rest become a " +
            "truncation footer. The tail this cuts is by construction the least severe.",
    ).int().default(DEFAULT_DIFF_LIMIT)

    private val fetch by option(
        "--fetch",
        help = "Allow downloading a Maven coordinate from Maven repositories (Central by " +
            "default, --repo to add mirrors). Without it, coordinates resolve from " +
            "~/.gradle/caches and ~/.m2 only.",
    ).flag()

    private val repo by option(
        "--repo",
        help = "Maven repository base URL for --fetch (repeatable). Tried in flag order " +
            "before Maven Central, which stays last as the fallback.",
    ).multiple()

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
        // Flag validation lives here, in the adapter, because a Clikt `.choice()` that
        // fails prints Clikt's own error and exits 1 — the wrong code for a usage error.
        // The value is already constrained, so these are the exit-3 guards a hostile
        // caller (MCP/HTTP, or a future flag) can still reach.
        val surface = ApiSurface.fromFlag(visibility)
        if (surface == null) {
            fail("--visibility '$visibility' is not valid for diff (expected public|all)")
            return
        }
        val severityFilter = SeverityFilter.fromFlag(severity)
        if (severityFilter == null) {
            fail("--severity '$severity' is not valid for diff (expected all|suspicious|breaking)")
            return
        }
        val failOnMode = FailOn.fromFlag(failOn)
        if (failOnMode == null) {
            fail("--fail-on '$failOn' is not valid for diff (expected none|breaking|any)")
            return
        }
        if (limit < 0) {
            fail("--limit must be >= 0, got $limit")
            return
        }
        val outcome = query(
            JdxService.ArtifactSpec(spec = oldArtifact, allowFetch = fetch, repos = repo),
            JdxService.ArtifactSpec(spec = newArtifact, allowFetch = fetch, repos = repo),
            JdxService.DiffOptions(
                visibility = surface,
                includeSynthetic = includeSynthetic,
                severityFilter = severityFilter,
                failOn = failOnMode,
                maxFindings = limit,
            ),
        )
        ReadCommandSupport.finish(outcome, "diff", json, noColor, terminate)
    }

    /** One exit-3 usage failure, rendered through the shared finish path. */
    private fun fail(message: String) {
        val json = effectiveJson(json)
        ReadCommandSupport.finish(
            JdxService.ServiceOutcome.Failure(
                ErrorResult.generic(
                    query = "$oldArtifact -> $newArtifact",
                    exitCode = 3,
                    message = "usage error: $message",
                ),
            ),
            "diff",
            json,
            noColor,
            terminate,
        )
    }
}
