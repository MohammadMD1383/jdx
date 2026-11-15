package dev.jdx.core.render

import dev.jdx.core.model.Provenance
import dev.jdx.core.model.Warning

/** Default cap on service-provider rows; the footer names `--limit` to see more. */
public const val DEFAULT_SERVICES_LIMIT: Int = 50

/**
 * One SPI provider registration (issue #25): the service interface FQN, the
 * provider class FQN, and the artifact that holds the `META-INF/services/`
 * file. Canonical names so the next command is a copy-paste (`jdx show
 * <provider>`).
 */
public data class ServiceHit(
    public val service: String,
    public val provider: String,
    public val artifact: String,
) {
    /**
     * One indented provider row under a service header:
     * `    provider com.foo.Impl`.
     */
    public fun providerLine(): String = "    provider $provider"

    /** The structural row: every text fact appears here (D-007). */
    public fun toJson(): String = buildString {
        append("{\"service\":").append(JsonEscape.quote(service))
        append(",\"provider\":").append(JsonEscape.quote(provider))
        append(",\"artifact\":").append(JsonEscape.quote(artifact))
        append("}")
    }
}

/**
 * The full answer to `jdx services`: SPI registrations grouped by artifact,
 * then by service interface. The one result model both renderers read.
 */
public data class ServiceListing(
    public val query: String,
    public val hits: List<ServiceHit>,
    public val truncation: Truncation?,
    public val warnings: List<Warning>,
    public val provenance: List<Provenance>,
) {
    /**
     * Text layout: header, artifact group counts, service headers with
     * indented provider rows, truncation footer. An empty [query] means
     * "list every registration" and the header drops the matching clause.
     */
    public fun renderText(color: Boolean = false): String {
        if (hits.isEmpty() && truncation == null) {
            return if (query.isEmpty()) {
                "no services found"
            } else {
                "no services matching '$query'"
            }
        }
        val lines = mutableListOf(
            if (query.isEmpty()) "services" else "services matching '$query'",
        )
        var currentArtifact: String? = null
        var currentService: String? = null
        val counts = hits.groupingBy { it.artifact }.eachCount()
        for (hit in hits) {
            if (hit.artifact != currentArtifact) {
                currentArtifact = hit.artifact
                currentService = null
                lines.add("${hit.artifact} (${counts.getValue(hit.artifact)})")
            }
            if (hit.service != currentService) {
                currentService = hit.service
                lines.add("  service ${hit.service}")
            }
            lines.add(hit.providerLine())
        }
        truncation?.let {
            lines.add("${it.shown} of ${it.total} providers shown (${it.hint} to see more)")
        }
        for (warning in warnings) lines.add("warning ${warning.code}: ${warning.message}")
        val plain = lines.joinToString("\n")
        return if (color) Ansi.colorizeListing(plain) else plain
    }

    /** Full JSON envelope; every text row's facts appear here (D-007). */
    public fun toJson(command: String): String {
        val resultJson = "{\"pattern\":" + JsonEscape.quote(query) +
            ",\"services\":[" + hits.joinToString(",") { it.toJson() } + "]}"
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
 * provider rows and reports the rest. Sorted input is the caller's contract
 * (D-007) — artifact, service, provider — and this function never re-sorts.
 */
public fun buildServiceListing(
    query: String,
    hits: List<ServiceHit>,
    limit: Int = DEFAULT_SERVICES_LIMIT,
    warnings: List<Warning> = emptyList(),
    provenance: List<Provenance> = emptyList(),
): ServiceListing {
    val (kept, truncation) = truncateEntities(hits, limit) { "--limit $it" }
    return ServiceListing(
        query = query,
        hits = kept,
        truncation = truncation,
        warnings = warnings,
        provenance = provenance,
    )
}
