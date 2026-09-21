package dev.jdx.core.render

import dev.jdx.core.model.Provenance
import dev.jdx.core.model.Warning

/** Default cap on usages rows; the footer names `--limit` to see more. */
public const val DEFAULT_USAGES_LIMIT: Int = 50

/**
 * One usage of a symbol: the referencing method's canonical ref, the artifact
 * holding it, the edge kind word (`call`, `read`, `write`, `ref`), and the
 * touched member's canonical target — per edge, so two reads of different
 * fields from one method render as distinct rows, never duplicates.
 * [targetRef] is `Owner#member(params)` for call/field edges (bare
 * `Owner#member` when the descriptor cannot parse — hostile bytes, never a
 * failure) and the bare owner binary for pure [type-reference][dev.jdx.core.model.ReferenceKind.TYPE_REFERENCE]
 * edges.
 */
public data class UsageHit(
    public val fromRef: String,
    public val artifact: String,
    public val kind: String,
    public val targetRef: String,
) {
    /** The text line without the edge target: `  call com.example.App#run()`. */
    public fun textLine(): String = "  $kind $fromRef"

    /**
     * The text line for a type query (`showTargets`): the edge target
     * shortened past the queried owner (`  call u.App#run() -> #greet(...)`),
     * so same-method touches of different members stay distinct.
     */
    public fun textLineWithTarget(queryOwner: String): String {
        val suffix = if (targetRef.startsWith("$queryOwner#")) targetRef.removePrefix(queryOwner) else targetRef
        return "  $kind $fromRef -> $suffix"
    }

    /** The structural row: every text fact appears here (D-007). */
    public fun toJson(): String = buildString {
        append("{\"from\":").append(JsonEscape.quote(fromRef))
        append(",\"artifact\":").append(JsonEscape.quote(artifact))
        append(",\"kind\":").append(JsonEscape.quote(kind))
        append(",\"target\":").append(JsonEscape.quote(targetRef))
        append("}")
    }
}

/**
 * The full answer to `jdx usages`: the referencing methods grouped by
 * artifact (PROPOSAL.md §7.3), truncated as one flat row sequence so the
 * footer covers everything shown. The one result model both renderers read,
 * mirroring [SearchListing].
 *
 * [targetRef] is the resolved query target (the header); each hit carries its
 * own edge target. [showTargets] is true for type queries: every row names
 * the touched member (`-> #greet(...)`), since "what touches T" needs the
 * member while "who calls M" (a member query) is answered by the call sites
 * alone.
 */
public data class UsageListing(
    public val query: String,
    public val targetRef: String,
    public val hits: List<UsageHit>,
    public val truncation: Truncation?,
    public val warnings: List<Warning>,
    public val provenance: List<Provenance>,
    public val showTargets: Boolean = false,
) {
    /** Text layout per PROPOSAL.md §8.1: artifact group headers, kind-led lines. */
    public fun renderText(color: Boolean = false): String {
        if (hits.isEmpty() && truncation == null) return "no usages of '$query'"
        val lines = mutableListOf("usages of '$query'")
        var currentArtifact: String? = null
        // Counts cover the kept prefix only, so headers stay truthful under
        // truncation — a header never claims rows beyond the footer.
        val counts = hits.groupingBy { it.artifact }.eachCount()
        for (hit in hits) {
            if (hit.artifact != currentArtifact) {
                currentArtifact = hit.artifact
                lines.add("${hit.artifact} (${counts.getValue(hit.artifact)})")
            }
            lines.add(if (showTargets) hit.textLineWithTarget(targetRef) else hit.textLine())
        }
        truncation?.let { lines.add("${it.shown} of ${it.total} usages shown (${it.hint} to see more)") }
        for (warning in warnings) lines.add("warning ${warning.code}: ${warning.message}")
        val plain = lines.joinToString("\n")
        return if (color) Ansi.colorizeListing(plain) else plain
    }

    /** Full JSON envelope; every text row's from, artifact and kind appears here (D-007). */
    public fun toJson(command: String): String {
        val resultJson = "{\"target\":" + JsonEscape.quote(targetRef) +
            ",\"usages\":[" + hits.joinToString(",") { it.toJson() } + "]}"
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

/**
 * Builds the listing from already-sorted [hits]: keeps the first [limit] whole
 * rows and reports the rest. Sorted input is the caller's contract (D-007
 * determinism) — artifact, from-ref, kind, target — and this function never
 * re-sorts, so a misordered caller is visible in goldens instead of being
 * silently repaired.
 */
public fun buildUsageListing(
    query: String,
    targetRef: String,
    hits: List<UsageHit>,
    limit: Int = DEFAULT_USAGES_LIMIT,
    warnings: List<Warning> = emptyList(),
    provenance: List<Provenance> = emptyList(),
    showTargets: Boolean = false,
): UsageListing {
    val (kept, truncation) = truncateEntities(hits, limit) { "--limit $it" }
    return UsageListing(
        query = query,
        targetRef = targetRef,
        hits = kept,
        truncation = truncation,
        warnings = warnings,
        provenance = provenance,
        showTargets = showTargets,
    )
}
