package dev.jdx.core.render

import dev.jdx.core.model.Provenance
import dev.jdx.core.model.Warning

/** Default cap on manifest attribute rows; the footer names `--limit` to see more. */
public const val DEFAULT_MANIFEST_LIMIT: Int = 50

/**
 * One manifest attribute row (issue #25): the attribute name, its value, the
 * artifact that holds `META-INF/MANIFEST.MF`, and — when non-null — the
 * per-entry (JarEntry) path this attribute belongs to.
 *
 * Main-section hits leave [entry] null (`Main-Class`, `Multi-Release`, …).
 * `--entries` hits set [entry] to the `Name:` section path so the next
 * command is a copy-paste (`jdx resources <entry> --in <jar>`).
 */
public data class ManifestHit(
    public val name: String,
    public val value: String,
    public val artifact: String,
    public val entry: String? = null,
) {
    /**
     * One indented attribute row under an artifact (and optional entry)
     * header: `  Main-Class: com.foo.Main` or `    SHA-256-Digest: …`.
     */
    public fun attributeLine(): String =
        if (entry == null) "  $name: $value" else "    $name: $value"

    /** The structural row: every text fact appears here (D-007). */
    public fun toJson(): String = buildString {
        append("{\"name\":").append(JsonEscape.quote(name))
        append(",\"value\":").append(JsonEscape.quote(value))
        append(",\"artifact\":").append(JsonEscape.quote(artifact))
        if (entry != null) {
            append(",\"entry\":").append(JsonEscape.quote(entry))
        }
        append("}")
    }
}

/**
 * The full answer to `jdx manifest`: attributes grouped by artifact (and by
 * entry path when listing per-entry attributes). The one result model both
 * renderers read.
 */
public data class ManifestListing(
    public val query: String,
    public val hits: List<ManifestHit>,
    public val truncation: Truncation?,
    public val warnings: List<Warning>,
    public val provenance: List<Provenance>,
) {
    /**
     * Text layout: header, artifact group counts, optional entry sub-headers
     * for `--entries`, indented `Name: value` rows, truncation footer. An
     * empty [query] means "list every attribute" and the header drops the
     * matching clause.
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
        var currentEntry: String? = null
        val counts = hits.groupingBy { it.artifact }.eachCount()
        for (hit in hits) {
            if (hit.artifact != currentArtifact) {
                currentArtifact = hit.artifact
                currentEntry = null
                lines.add("${hit.artifact} (${counts.getValue(hit.artifact)})")
            }
            val entry = hit.entry
            if (entry != null && entry != currentEntry) {
                currentEntry = entry
                lines.add("  $entry")
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
 * (D-007) — artifact, entry (nulls first), name — and this function never
 * re-sorts.
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
