package dev.jdx.core.render

import dev.jdx.core.model.Provenance
import dev.jdx.core.model.Warning

/** Default cap on literal rows; the footer names `--limit` to see more. */
public const val DEFAULT_LITERALS_LIMIT: Int = 50

/**
 * One string-constant hit (issue #86): the owning member's canonical ref (or
 * `path#key` for a `.properties` entry), the artifact holding it, the kind
 * word (`ldc` for a method-body load, `const` for a field `ConstantValue`,
 * `prop` for a properties-file entry), the matched string value, and optional
 * `SourceFile`/`LineNumberTable` location when the class retains debug info
 * (for `prop`, [file] is the resource path and [line] is null).
 *
 * [value] is the raw constant / property value (never quoted here — renderers
 * quote for text/JSON). Empty bytecode constants are never emitted by the
 * scanner; empty property values are kept when the key matched.
 */
public data class LiteralHit(
    public val fromRef: String,
    public val artifact: String,
    public val kind: String,
    public val value: String,
    public val file: String? = null,
    public val line: Int? = null,
) {
    /** `File.java:42` when both [file] and [line] are known; otherwise `null`. */
    public fun location(): String? =
        if (file != null && line != null) "$file:$line" else null

    /**
     * One text row: `  ldc Owner#member(...)  "value"  File.java:42`.
     * The value is JSON-quoted so control characters stay single-line.
     */
    public fun textLine(): String = buildString {
        append("  ").append(kind).append(" ").append(fromRef)
        append("  ").append(JsonEscape.quote(value))
        location()?.let { append("  ").append(it) }
    }

    /** The structural row: every text fact appears here (D-007). */
    public fun toJson(): String = buildString {
        append("{\"from\":").append(JsonEscape.quote(fromRef))
        append(",\"artifact\":").append(JsonEscape.quote(artifact))
        append(",\"kind\":").append(JsonEscape.quote(kind))
        append(",\"value\":").append(JsonEscape.quote(value))
        if (file != null) append(",\"file\":").append(JsonEscape.quote(file))
        if (line != null) append(",\"line\":").append(line)
        append("}")
    }
}

/**
 * The full answer to `jdx literals`: matched string constants grouped by
 * artifact. The one result model both renderers read, mirroring [UsageListing].
 */
public data class LiteralListing(
    public val query: String,
    public val hits: List<LiteralHit>,
    public val truncation: Truncation?,
    public val warnings: List<Warning>,
    public val provenance: List<Provenance>,
) {
    /** Text layout: header, artifact group counts, kind-led rows, truncation footer. */
    public fun renderText(color: Boolean = false): String {
        if (hits.isEmpty() && truncation == null) return "no literals matching '$query'"
        val lines = mutableListOf("literals matching '$query'")
        var currentArtifact: String? = null
        val counts = hits.groupingBy { it.artifact }.eachCount()
        for (hit in hits) {
            if (hit.artifact != currentArtifact) {
                currentArtifact = hit.artifact
                lines.add("${hit.artifact} (${counts.getValue(hit.artifact)})")
            }
            lines.add(hit.textLine())
        }
        truncation?.let { lines.add("${it.shown} of ${it.total} literals shown (${it.hint} to see more)") }
        for (warning in warnings) lines.add("warning ${warning.code}: ${warning.message}")
        val plain = lines.joinToString("\n")
        return if (color) Ansi.colorizeListing(plain) else plain
    }

    /** Full JSON envelope; every text row's facts appear here (D-007). */
    public fun toJson(command: String): String {
        val resultJson = "{\"pattern\":" + JsonEscape.quote(query) +
            ",\"literals\":[" + hits.joinToString(",") { it.toJson() } + "]}"
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
 * rows and reports the rest. Sorted input is the caller's contract (D-007) —
 * artifact, from-ref, kind, value — and this function never re-sorts.
 */
public fun buildLiteralListing(
    query: String,
    hits: List<LiteralHit>,
    limit: Int = DEFAULT_LITERALS_LIMIT,
    warnings: List<Warning> = emptyList(),
    provenance: List<Provenance> = emptyList(),
): LiteralListing {
    val (kept, truncation) = truncateEntities(hits, limit) { "--limit $it" }
    return LiteralListing(
        query = query,
        hits = kept,
        truncation = truncation,
        warnings = warnings,
        provenance = provenance,
    )
}
