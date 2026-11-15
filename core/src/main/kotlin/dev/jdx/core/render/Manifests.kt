package dev.jdx.core.render

import dev.jdx.core.model.Provenance
import dev.jdx.core.model.Warning

/** Default cap on manifest attribute rows; the footer names `--limit` to see more. */
public const val DEFAULT_MANIFEST_LIMIT: Int = 50

/**
 * One main-section manifest attribute (issue #25 manifest slice): the attribute
 * name, its value, and the artifact that holds `META-INF/MANIFEST.MF`.
 * Canonical names so the next command is a copy-paste (`jdx manifest
 * Main-Class --in <jar>`).
 *
 * Per-entry (JarEntry) attributes are out of this slice — main attributes are
 * what agents ask for (`Main-Class`, `Implementation-Version`, `Multi-Release`).
 */
public data class ManifestHit(
    public val name: String,
    public val value: String,
    public val artifact: String,
) {
    /**
     * One indented attribute row under an artifact header:
     * `  Main-Class: com.foo.Main`.
     */
    public fun attributeLine(): String = "  $name: $value"

    /** The structural row: every text fact appears here (D-007). */
    public fun toJson(): String = buildString {
        append("{\"name\":").append(JsonEscape.quote(name))
        append(",\"value\":").append(JsonEscape.quote(value))
        append(",\"artifact\":").append(JsonEscape.quote(artifact))
        append("}")
    }
}

/**
 * The full answer to `jdx manifest`: main-section attributes grouped by
 * artifact. The one result model both renderers read.
 */
public data class ManifestListing(
    public val query: String,
    public val hits: List<ManifestHit>,
    public val truncation: Truncation?,
    public val warnings: List<Warning>,
    public val provenance: List<Provenance>,
) {
    /**
     * Text layout: header, artifact group counts, indented `Name: value`
     * rows, truncation footer. An empty [query] means "list every attribute"
     * and the header drops the matching clause.
     */
    public fun renderText(color: Boolean = false): String {
        if (hits.isEmpty() && truncation == null) {
            return if (query.isEmpty()) {
                "no manifests found"
            } else {
                "no manifest attributes matching '$query'"
            }
        }
        val lines = mutableListOf(
            if (query.isEmpty()) "manifests" else "manifests matching '$query'",
        )
        var currentArtifact: String? = null
        val counts = hits.groupingBy { it.artifact }.eachCount()
        for (hit in hits) {
            if (hit.artifact != currentArtifact) {
                currentArtifact = hit.artifact
                lines.add("${hit.artifact} (${counts.getValue(hit.artifact)})")
            }
            lines.add(hit.attributeLine())
        }
        truncation?.let {
            lines.add("${it.shown} of ${it.total} attributes shown (${it.hint} to see more)")
        }
        for (warning in warnings) lines.add("warning ${warning.code}: ${warning.message}")
        val plain = lines.joinToString("\n")
        return if (color) Ansi.colorizeListing(plain) else plain
    }

    /** Full JSON envelope; every text row's facts appear here (D-007). */
    public fun toJson(command: String): String {
        val resultJson = "{\"pattern\":" + JsonEscape.quote(query) +
            ",\"attributes\":[" + hits.joinToString(",") { it.toJson() } + "]}"
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
 * attribute rows and reports the rest. Sorted input is the caller's contract
 * (D-007) — artifact, name — and this function never re-sorts.
 */
public fun buildManifestListing(
    query: String,
    hits: List<ManifestHit>,
    limit: Int = DEFAULT_MANIFEST_LIMIT,
    warnings: List<Warning> = emptyList(),
    provenance: List<Provenance> = emptyList(),
): ManifestListing {
    val (kept, truncation) = truncateEntities(hits, limit) { "--limit $it" }
    return ManifestListing(
        query = query,
        hits = kept,
        truncation = truncation,
        warnings = warnings,
        provenance = provenance,
    )
}
