package dev.jdx.core.render

import dev.jdx.core.model.Provenance
import dev.jdx.core.model.Warning

/** Default cap on hierarchy subtype rows; the footer names `--limit` to see more. */
public const val DEFAULT_HIERARCHY_LIMIT: Int = 50

/**
 * One supertype edge on the path upward from the queried type: the supertype's
 * binary name, how the subtype relates to it (`extends` for superclasses and
 * superinterfaces of interfaces, `implements` for interfaces a class declares),
 * the artifact providing it — null when the supertype names a class outside
 * the workspace (its edge is known from the child's bytes, its provider is
 * not) — and the BFS distance (1 = direct supertype).
 */
public data class SupertypeEntry(
    public val binary: String,
    public val relation: String,
    public val artifact: String?,
    public val depth: Int,
) {
    /** The text line: `    extends com.example.Base  app.jar` (artifact omitted when unknown). */
    public fun textLine(): String = buildString {
        append("    ").append(relation).append(" ").append(binary)
        if (artifact != null) append("  ").append(artifact)
    }

    /** The structural row: every text fact appears here (D-007). */
    public fun toJson(): String = buildString {
        append("{\"type\":").append(JsonEscape.quote(binary))
        append(",\"relation\":").append(JsonEscape.quote(relation))
        if (artifact != null) append(",\"artifact\":").append(JsonEscape.quote(artifact))
        append(",\"depth\":").append(depth)
        append("}")
    }
}

/**
 * One subtype found downward in the workspace: the subtype's binary name, the
 * artifact providing it, and the direct edge out of it when its parent is not
 * the queried type (`extends h.Middle` — null for direct children, whose
 * parent is the target itself).
 */
public data class SubtypeEntry(
    public val binary: String,
    public val artifact: String,
    public val via: String?,
) {
    /** The text line: `    class h.Sprout   app.jar (extends h.Middle)`. */
    public fun textLine(): String = buildString {
        append("    class ").append(binary)
        append("  ").append(artifact)
        if (via != null) append(" ($via)")
    }

    /** The structural row: every text fact appears here (D-007). */
    public fun toJson(): String = buildString {
        append("{\"type\":").append(JsonEscape.quote(binary))
        append(",\"artifact\":").append(JsonEscape.quote(artifact))
        if (via != null) append(",\"via\":").append(JsonEscape.quote(via))
        append("}")
    }
}

/**
 * The full answer to `jdx hierarchy`: the supertype chain upward (BFS order,
 * nearest first) and the workspace subtypes downward (sorted by binary),
 * truncated as one flat subtype sequence so the footer covers everything
 * shown. The one result model both renderers read, mirroring [UsageListing].
 *
 * [showUp]/[showDown] record the requested directions so `--up`-only answers
 * omit the subtypes section entirely (and vice versa) instead of printing an
 * empty one. The up chain is never truncated — it is one lineage, short by
 * construction — while [subtypes] keeps the first [limit] rows.
 */
public data class HierarchyListing(
    public val query: String,
    public val targetRef: String,
    public val supertypes: List<SupertypeEntry>,
    public val subtypes: List<SubtypeEntry>,
    public val showUp: Boolean = true,
    public val showDown: Boolean = true,
    public val truncation: Truncation?,
    public val warnings: List<Warning>,
    public val provenance: List<Provenance>,
) {
    /** Text layout per PROPOSAL.md §8.1: kind-led lines, counts, explicit footer. */
    public fun renderText(color: Boolean = false): String {
        val lines = mutableListOf("hierarchy of '$query'")
        if (showUp) {
            if (supertypes.isEmpty()) {
                lines.add("  supertypes: none")
            } else {
                lines.add("  supertypes")
                for (entry in supertypes) lines.add(entry.textLine())
            }
        }
        if (showDown) {
            // The count covers the kept prefix only, so the header stays
            // truthful under truncation — the footer carries the total.
            lines.add("  subtypes in workspace (${subtypes.size})")
            for (entry in subtypes) lines.add(entry.textLine())
            truncation?.let { lines.add("${it.shown} of ${it.total} subtypes shown (${it.hint} to see more)") }
        }
        for (warning in warnings) lines.add("warning ${warning.code}: ${warning.message}")
        val plain = lines.joinToString("\n")
        return if (color) Ansi.colorizeListing(plain) else plain
    }

    /** Full JSON envelope; every text row's type, artifact and relation appears here (D-007). */
    public fun toJson(command: String): String {
        val resultJson = "{\"target\":" + JsonEscape.quote(targetRef) +
            ",\"supertypes\":[" + supertypes.joinToString(",") { it.toJson() } + "]" +
            ",\"subtypes\":[" + subtypes.joinToString(",") { it.toJson() } + "]}"
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
 * Builds the listing from an already-ordered supertype chain and
 * already-sorted [subtypes]: keeps the first [limit] subtype rows and reports
 * the rest. Sorted input is the caller's contract (D-007 determinism) —
 * supertypes nearest-first, subtypes by binary — and this function never
 * re-sorts, so a misordered caller is visible in goldens instead of being
 * silently repaired.
 */
public fun buildHierarchyListing(
    query: String,
    targetRef: String,
    supertypes: List<SupertypeEntry>,
    subtypes: List<SubtypeEntry>,
    showUp: Boolean = true,
    showDown: Boolean = true,
    limit: Int = DEFAULT_HIERARCHY_LIMIT,
    warnings: List<Warning> = emptyList(),
    provenance: List<Provenance> = emptyList(),
): HierarchyListing {
    val (kept, truncation) = truncateEntities(subtypes, limit) { "--limit $it" }
    return HierarchyListing(
        query = query,
        targetRef = targetRef,
        supertypes = supertypes,
        subtypes = kept,
        showUp = showUp,
        showDown = showDown,
        truncation = truncation,
        warnings = warnings,
        provenance = provenance,
    )
}
