package dev.jdx.core.render

import dev.jdx.core.diff.ApiDiff
import dev.jdx.core.diff.ApiSurface
import dev.jdx.core.diff.DIFF_FINDING_ORDER
import dev.jdx.core.diff.DiffCounts
import dev.jdx.core.diff.DiffFinding
import dev.jdx.core.diff.DiffGate
import dev.jdx.core.diff.DiffSeverity
import dev.jdx.core.diff.FailOn
import dev.jdx.core.diff.SeverityFilter
import dev.jdx.core.model.Provenance
import dev.jdx.core.model.Warning

/**
 * Default cap on findings printed by `jdx diff`. Deliberately higher than the
 * 50 of a member listing: a diff is the one answer that is *supposed* to be longer
 * than a screen, and the ordering puts every breaking change first, so the tail
 * this cuts is by construction the least urgent part of the report.
 */
public const val DEFAULT_DIFF_LIMIT: Int = 200

/**
 * The answer to a `jdx diff` query (issue #23): what changed between two artifacts,
 * most severe first, with a per-severity tally and a CI gate verdict.
 *
 * The model is flat on purpose — one [DiffFinding] per line, each naming its
 * canonical ref — so the report is a filter-and-cut problem rather than a tree
 * walk, and an agent can read the breaking block and stop.
 *
 * [findings] is what is *shown* (filtered by [severityFilter], cut to `maxFindings`);
 * [counts] always describes the whole comparison, so a filtered report never lies
 * about what it is hiding. [exitCode] is the gate's verdict, and is the only
 * non-zero exit a successful comparison can produce.
 */
public data class DiffReport(
    public val oldArtifact: String,
    public val newArtifact: String,
    public val surface: ApiSurface,
    public val includeSynthetic: Boolean,
    public val severityFilter: SeverityFilter,
    public val findings: List<DiffFinding>,
    public val counts: DiffCounts,
    public val gate: DiffGate,
    public val truncation: Truncation?,
    /** Findings dropped by [severityFilter] — shown count for the "N hidden" line. */
    public val hiddenBySeverity: Int,
    public val warnings: List<Warning>,
    public val provenance: List<Provenance>,
) {
    /** The envelope's `query`: both sides, so one line identifies the comparison. */
    public val query: String get() = "$oldArtifact -> $newArtifact"

    /** True when the two artifacts agree completely. */
    public val identical: Boolean get() = counts.total == 0

    /** The process exit code this report implies: the gate's, and nothing else. */
    public val exitCode: Int get() = gate.exitCode

    /**
     * Text layout: header naming both sides and the surface, one line per finding
     * (`severity  RULE  ref[: detail]`), the truncation and hidden-finding lines, the
     * per-severity tally, the gate verdict when a gate was asked for, provenance, and
     * the next step.
     */
    public fun renderText(color: Boolean = false): String {
        val out = mutableListOf("api diff $query (${surface.wireName} surface)")
        if (identical) {
            out.add("no differences (${counts.typesNew} types compared)")
        } else {
            for (finding in findings) out.add(findingTextLine(finding))
            truncation?.let { out.add("${it.shown} of ${it.total} findings shown (${it.hint} to see more)") }
            if (hiddenBySeverity > 0) {
                out.add("$hiddenBySeverity findings below --severity ${severityFilter.wireName}")
            }
            out.add(countsText())
        }
        if (gate.mode != FailOn.NONE) out.add(gateText())
        for (warning in warnings) out.add("warning ${warning.code}: ${warning.message}")
        out.add(provenanceText())
        nextHint()?.let { out.add("next: $it") }
        val plain = out.joinToString("\n")
        return if (color) Ansi.colorizeListing(plain) else plain
    }

    private fun findingTextLine(finding: DiffFinding): String {
        val subject = if (finding.detail.isEmpty()) finding.ref else "${finding.ref}: ${finding.detail}"
        return "${finding.severity.wireName}  ${finding.rule.name}  $subject"
    }

    /** The tally line — the one number set an agent reads before anything else. */
    private fun countsText(): String = buildString {
        append("${counts.breaking} breaking")
        append(" · ${counts.suspicious} suspicious")
        append(" · ${counts.informational} informational")
        append(" · ${counts.typesAdded} type${plural(counts.typesAdded)} added")
        append(" · ${counts.typesRemoved} type${plural(counts.typesRemoved)} removed")
    }

    private fun plural(count: Int): String = if (count == 1) "" else "s"

    private fun gateText(): String = when (gate.mode) {
        FailOn.BREAKING -> if (gate.tripped) {
            "fail-on breaking: ${counts.breaking} breaking change${plural(counts.breaking)} (exit ${gate.exitCode})"
        } else {
            "fail-on breaking: no breaking changes (exit ${gate.exitCode})"
        }
        FailOn.ANY -> if (gate.tripped) {
            "fail-on any: ${counts.total} difference${plural(counts.total)} (exit ${gate.exitCode})"
        } else {
            "fail-on any: no differences (exit ${gate.exitCode})"
        }
        FailOn.NONE -> ""
    }

    private fun provenanceText(): String = if (provenance.isEmpty()) {
        "source: no provenance recorded"
    } else {
        provenance.joinToString("\n") { entry ->
            "source: ${entry.artifact} (${entry.origin.name.lowercase().replace('_', '-')})"
        }
    }

    /**
     * The next command to type, always naming a type the report actually mentions:
     * the first breaking finding's declaring type, else the first finding's. A diff
     * is answered by "what *is* this type now?", so `members` is the honest next step
     * (AGENTS.md §2.3).
     */
    private fun nextHint(): String? {
        val subject = findings.firstOrNull { it.rule.severity == DiffSeverity.BREAKING } ?: findings.firstOrNull()
        val type = subject?.type ?: return null
        return "jdx members $type"
    }

    /** Full JSON envelope; every rule id, ref and detail text shows is here (D-007). */
    public fun toJson(command: String): String {
        val resultJson = buildString {
            append("{\"old\":").append(JsonEscape.quote(oldArtifact))
            append(",\"new\":").append(JsonEscape.quote(newArtifact))
            append(",\"surface\":").append(JsonEscape.quote(surface.wireName))
            append(",\"includeSynthetic\":").append(includeSynthetic)
            append(",\"identical\":").append(identical)
            append(",\"counts\":").append(countsJson(counts))
            append(",\"gate\":{\"mode\":").append(JsonEscape.quote(gate.mode.name.lowercase()))
            append(",\"tripped\":").append(gate.tripped)
            append(",\"exitCode\":").append(gate.exitCode).append("}")
            append(",\"findings\":[")
            append(
                findings.joinToString(",") { finding ->
                    "{\"severity\":" + JsonEscape.quote(finding.severity.wireName) +
                        ",\"rule\":" + JsonEscape.quote(finding.rule.name) +
                        ",\"type\":" + JsonEscape.quote(finding.type) +
                        ",\"ref\":" + JsonEscape.quote(finding.ref) +
                        ",\"detail\":" + JsonEscape.quote(finding.detail) + "}"
                },
            )
            append("]}")
        }
        return envelopeJson(
            ok = true,
            command = command,
            query = query,
            resultJson = resultJson,
            truncation = truncation,
            warnings = warnings,
            provenance = provenance,
        )
    }
}

/** The wire spelling of a severity, as the header, the finding lines and JSON print it. */
public val DiffSeverity.wireName: String get() = name.lowercase()

private fun countsJson(counts: DiffCounts): String = buildString {
    append("{\"typesOld\":").append(counts.typesOld)
    append(",\"typesNew\":").append(counts.typesNew)
    append(",\"breaking\":").append(counts.breaking)
    append(",\"suspicious\":").append(counts.suspicious)
    append(",\"informational\":").append(counts.informational)
    append(",\"typesAdded\":").append(counts.typesAdded)
    append(",\"typesRemoved\":").append(counts.typesRemoved)
    append(",\"total\":").append(counts.total)
    append("}")
}

/**
 * Builds the report from a computed [ApiDiff]: orders the findings most severe
 * first ([DIFF_FINDING_ORDER]), applies [severityFilter] and cuts to [maxFindings]
 * at a whole finding, and derives the counts and the gate from the unfiltered
 * comparison so the tally and the verdict can never disagree with each other.
 *
 * Ordering happens here rather than in the differ so that any [ApiDiff] — however
 * it was built — renders in the same total order and produces the same bytes.
 */
public fun buildDiffReport(
    diff: ApiDiff,
    surface: ApiSurface = ApiSurface.PUBLIC,
    includeSynthetic: Boolean = false,
    severityFilter: SeverityFilter = SeverityFilter.ALL,
    failOn: FailOn = FailOn.NONE,
    maxFindings: Int = DEFAULT_DIFF_LIMIT,
    provenance: List<Provenance> = emptyList(),
    warnings: List<Warning> = emptyList(),
): DiffReport {
    val counts = DiffCounts.of(diff)
    val ordered = diff.ordered
    val shown = ordered.filter { it.severity <= severityFilter.threshold }
    val (kept, truncation) = truncateEntities(shown, maxFindings) { total -> "--limit $total" }
    return DiffReport(
        oldArtifact = diff.oldArtifact,
        newArtifact = diff.newArtifact,
        surface = surface,
        includeSynthetic = includeSynthetic,
        severityFilter = severityFilter,
        findings = kept,
        counts = counts,
        gate = DiffGate(mode = failOn, breakingCount = counts.breaking, findingCount = counts.total),
        truncation = truncation,
        hiddenBySeverity = ordered.size - shown.size,
        // Both sides are read independently, so a class both artifacts share and both
        // trips a warning on reports it twice. It is one fact, not two, and a self-diff
        // (`jdx diff a.jar a.jar`) would otherwise print every warning twice. Distinct
        // keeps the first-seen order, so the output stays deterministic. The service
        // attributes each side's warnings (`in <label>:`) before calling, so the same
        // unreadable class in two differently-named jars stays two facts and reports
        // once per side; only truly identical entries dedupe here.
        warnings = warnings.distinct(),
        provenance = provenance,
    )
}
