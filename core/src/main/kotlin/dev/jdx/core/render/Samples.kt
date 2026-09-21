package dev.jdx.core.render

import dev.jdx.core.model.Provenance
import dev.jdx.core.model.Warning

/** Default cap on samples rows (PROPOSAL.md §7.3); the footer names `--limit` to see more. */
public const val DEFAULT_SAMPLES_LIMIT: Int = 3

/** Cap on snippet code lines per sample; longer bodies show this prefix plus a marker. */
public const val MAX_SAMPLE_SNIPPET_LINES: Int = 15

/**
 * The enclosing-method source behind one sample: the caller's own verbatim
 * body slice (T-034, PROPOSAL.md §7.3), ground-truth bytes via the T-021 seam.
 *
 * [startLine]/[endLine] are the member's own 1-based inclusive range;
 * [lines] is the shown prefix (at most [MAX_SAMPLE_SNIPPET_LINES]) and
 * [truncated] says a marker row follows instead of the rest. JSON carries the
 * same shown lines plus the total, so text⊆JSON (D-007) holds either way.
 */
public data class SampleSnippet(
    public val file: String,
    public val startLine: Int,
    public val endLine: Int,
    public val lines: List<String>,
    public val truncated: Boolean,
) {
    /** Total code lines in the member body (shown plus cut). */
    public fun totalLines(): Int = if (truncated) endLine - startLine + 1 else lines.size

    /** The text rows: the file range line, the capped code lines, the marker when cut. */
    public fun textLines(): List<String> {
        val shown = lines.take(MAX_SAMPLE_SNIPPET_LINES)
        val rows = mutableListOf("    $file:$startLine-$endLine")
        for (line in shown) rows.add("    $line")
        if (truncated || lines.size > MAX_SAMPLE_SNIPPET_LINES) {
            rows.add("      … (${shown.size} of ${totalLines()} lines shown)")
        }
        return rows
    }

    /** The structural snippet: every text fact appears here (D-007). */
    public fun toJson(): String = buildString {
        val shown = lines.take(MAX_SAMPLE_SNIPPET_LINES)
        val cut = truncated || lines.size > MAX_SAMPLE_SNIPPET_LINES
        append("{\"file\":").append(JsonEscape.quote(file))
        append(",\"lines\":[").append(startLine).append(",").append(endLine).append("]")
        append(",\"totalLines\":").append(totalLines())
        if (cut) append(",\"truncated\":true")
        append(",\"text\":").append(JsonEscape.quote(shown.joinToString("\n")))
        append("}")
    }
}

/**
 * One usage example of a symbol: the calling method's canonical ref
 * (`Binary#member(params)`), the artifact holding it, the touched member's
 * canonical target, and the caller's source body when its paired sources
 * exist — `null` otherwise, so samples degrades to a ranked caller list
 * instead of failing (degrade, don't fail).
 */
public data class SampleHit(
    public val fromRef: String,
    public val artifact: String,
    public val targetRef: String,
    public val snippet: SampleSnippet?,
) {
    /** The text rows: the example line plus the snippet rows when sourced. */
    public fun textLines(): List<String> {
        val rows = mutableListOf("  example $fromRef  $artifact")
        snippet?.let { rows.addAll(it.textLines()) }
        return rows
    }

    /** The structural row: every text fact appears here (D-007). */
    public fun toJson(): String = buildString {
        append("{\"from\":").append(JsonEscape.quote(fromRef))
        append(",\"artifact\":").append(JsonEscape.quote(artifact))
        append(",\"target\":").append(JsonEscape.quote(targetRef))
        snippet?.let { append(",\"snippet\":").append(it.toJson()) }
        append("}")
    }
}

/**
 * The exemplariness rank of one sample (T-034, PROPOSAL.md §7.3): sourced
 * artifacts first (only when `--prefer-sources`), then non-test before test,
 * non-generated before generated, fuller overloads before thinner ones, and
 * finally the caller ref itself so the order is total and deterministic
 * (D-007). Smaller ranks first.
 */
public data class SampleRank(
    public val sourced: Int,
    public val test: Int,
    public val generated: Int,
    public val negParams: Int,
    public val fromRef: String,
) : Comparable<SampleRank> {
    override fun compareTo(other: SampleRank): Int {
        var order = sourced.compareTo(other.sourced)
        if (order != 0) return order
        order = test.compareTo(other.test)
        if (order != 0) return order
        order = generated.compareTo(other.generated)
        if (order != 0) return order
        order = negParams.compareTo(other.negParams)
        if (order != 0) return order
        return fromRef.compareTo(other.fromRef)
    }
}

/**
 * Ranks one caller ref for exemplariness. [targetParamCount] is the touched
 * overload's erased parameter count — call sites using more of the API's
 * parameters rank first. [sourcedFirst] is `--prefer-sources`; [hasSources]
 * says the caller's artifact pairs sources (cheap root check, no parsing).
 */
public fun sampleOrderKey(
    fromRef: String,
    targetParamCount: Int,
    sourcedFirst: Boolean,
    hasSources: Boolean,
): SampleRank = SampleRank(
    sourced = if (sourcedFirst && !hasSources) 1 else 0,
    test = if (fromRef.contains("test", ignoreCase = true)) 1 else 0,
    generated = if (isGeneratedRef(fromRef)) 1 else 0,
    negParams = -targetParamCount.coerceAtLeast(0),
    fromRef = fromRef,
)

/** Names compiler-generated callers: `$` nesting, lambda/metafactory and accessor bridges. */
public fun isGeneratedRef(fromRef: String): Boolean =
    fromRef.contains('$') ||
        fromRef.contains("lambda\$") ||
        fromRef.contains("access\$") ||
        fromRef.contains("generated", ignoreCase = true)

/**
 * The full answer to `jdx samples`: the resolved query target and the ranked
 * examples, truncated as one flat row sequence so the footer covers everything
 * shown. The one result model both renderers read, mirroring [UsageListing].
 */
public data class SampleListing(
    public val query: String,
    public val targetRef: String,
    public val rows: List<SampleHit>,
    public val truncation: Truncation?,
    public val warnings: List<Warning>,
    public val provenance: List<Provenance>,
) {
    /** Text layout per PROPOSAL.md §8.1: header, kind-led example rows, snippet blocks. */
    public fun renderText(color: Boolean = false): String {
        if (rows.isEmpty() && truncation == null) return "no samples of '$query'"
        val lines = mutableListOf("samples of '$query'")
        for (hit in rows) lines.addAll(hit.textLines())
        truncation?.let { lines.add("${it.shown} of ${it.total} samples shown (${it.hint} to see more)") }
        for (warning in warnings) lines.add("warning ${warning.code}: ${warning.message}")
        val plain = lines.joinToString("\n")
        return if (color) Ansi.colorizeListing(plain) else plain
    }

    /** Full JSON envelope; every text row's facts appear here (D-007). */
    public fun toJson(command: String): String {
        val resultJson = "{\"target\":" + JsonEscape.quote(targetRef) +
            ",\"samples\":[" + rows.joinToString(",") { it.toJson() } + "]}"
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
 * Builds the listing from already-ranked [hits]: keeps the first [limit]
 * whole rows and reports the rest. Ranked input is the caller's contract
 * (D-007 determinism) — exemplariness order via [sampleOrderKey] — and this
 * function never re-sorts, so a misordered caller is visible in goldens
 * instead of being silently repaired.
 */
public fun buildSampleListing(
    query: String,
    targetRef: String,
    hits: List<SampleHit>,
    limit: Int = DEFAULT_SAMPLES_LIMIT,
    warnings: List<Warning> = emptyList(),
    provenance: List<Provenance> = emptyList(),
): SampleListing {
    val (kept, truncation) = truncateEntities(hits, limit) { "--limit $it" }
    return SampleListing(
        query = query,
        targetRef = targetRef,
        rows = kept,
        truncation = truncation,
        warnings = warnings,
        provenance = provenance,
    )
}
