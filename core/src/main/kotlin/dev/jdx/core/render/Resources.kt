package dev.jdx.core.render

import dev.jdx.core.model.Provenance
import dev.jdx.core.model.Warning

/** Default cap on resource path rows; the footer names `--limit` to see more. */
public const val DEFAULT_RESOURCES_LIMIT: Int = 50

/**
 * One non-class resource path (issue #25 resource-listing slice): the
 * entry path inside the artifact and the artifact that holds it.
 * Canonical paths so the next command is a copy-paste
 * (`jdx resources META-INF/services --in <jar>`). Content is never
 * embedded — listing only.
 */
public data class ResourceHit(
    public val path: String,
    public val artifact: String,
) {
    /** One indented path row under an artifact header: `  META-INF/MANIFEST.MF`. */
    public fun pathLine(): String = "  $path"

    /** The structural row: every text fact appears here (D-007). */
    public fun toJson(): String = buildString {
        append("{\"path\":").append(JsonEscape.quote(path))
        append(",\"artifact\":").append(JsonEscape.quote(artifact))
        append("}")
    }
}

/**
 * The full answer to `jdx resources`: non-class paths grouped by artifact.
 * The one result model both renderers read.
 */
public data class ResourceListing(
    public val query: String,
    public val hits: List<ResourceHit>,
    public val truncation: Truncation?,
    public val warnings: List<Warning>,
    public val provenance: List<Provenance>,
) {
    /**
     * Text layout: header, artifact group counts, indented paths,
     * truncation footer. An empty [query] means "list every resource"
     * and the header drops the matching clause.
     */
    public fun renderText(color: Boolean = false): String {
        if (hits.isEmpty() && truncation == null) {
            return if (query.isEmpty()) {
                "no resources found"
            } else {
                "no resources matching '$query'"
            }
        }
        val lines = mutableListOf(
            if (query.isEmpty()) "resources" else "resources matching '$query'",
        )
        var currentArtifact: String? = null
        val counts = hits.groupingBy { it.artifact }.eachCount()
        for (hit in hits) {
            if (hit.artifact != currentArtifact) {
                currentArtifact = hit.artifact
                lines.add("${hit.artifact} (${counts.getValue(hit.artifact)})")
            }
            lines.add(hit.pathLine())
        }
        truncation?.let {
            lines.add("${it.shown} of ${it.total} resources shown (${it.hint} to see more)")
        }
        for (warning in warnings) lines.add("warning ${warning.code}: ${warning.message}")
        val plain = lines.joinToString("\n")
        return if (color) Ansi.colorizeListing(plain) else plain
    }

    /** Full JSON envelope; every text row's facts appear here (D-007). */
    public fun toJson(command: String): String {
        val resultJson = "{\"pattern\":" + JsonEscape.quote(query) +
            ",\"resources\":[" + hits.joinToString(",") { it.toJson() } + "]}"
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
 * Builds the listing from already-sorted [hits]: keeps the first [limit]
 * path rows and reports the rest. Sorted input is the caller's contract
 * (D-007) — artifact, path — and this function never re-sorts.
 */
public fun buildResourceListing(
    query: String,
    hits: List<ResourceHit>,
    limit: Int = DEFAULT_RESOURCES_LIMIT,
    warnings: List<Warning> = emptyList(),
    provenance: List<Provenance> = emptyList(),
): ResourceListing {
    val (kept, truncation) = truncateEntities(hits, limit) { "--limit $it" }
    return ResourceListing(
        query = query,
        hits = kept,
        truncation = truncation,
        warnings = warnings,
        provenance = provenance,
    )
}
