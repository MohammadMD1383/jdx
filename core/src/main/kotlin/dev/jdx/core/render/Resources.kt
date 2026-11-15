package dev.jdx.core.render

import dev.jdx.core.model.Provenance
import dev.jdx.core.model.Warning

/** Default cap on resource path rows; the footer names `--limit` to see more. */
public const val DEFAULT_RESOURCES_LIMIT: Int = 50

/** Default cap on shown resource content lines under `--cat`; names `--max-lines`. */
public const val DEFAULT_RESOURCE_MAX_LINES: Int = 200

/**
 * One non-class resource path (issue #25 resource-listing slice): the
 * entry path inside the artifact and the artifact that holds it.
 * Canonical paths so the next command is a copy-paste
 * (`jdx resources META-INF/services --in <jar>`). Listing rows never
 * embed content — use [ResourceContent] / `--cat` for that.
 */
public data class ResourceHit(
    public val path: String,
    public val artifact: String,
) {
    /** One indented path row under an artifact header: `  META-INF/MANIFEST.MF`. */
    public fun pathLine(): String = "  $path"

    /**
     * Copy-pasteable disambiguator for `--cat` when more than one hit
     * matches: `path --in artifact`.
     */
    public fun catCandidate(): String = "$path --in $artifact"

    /** The structural row: every text fact appears here (D-007). */
    public fun toJson(): String = buildString {
        append("{\"path\":").append(JsonEscape.quote(path))
        append(",\"artifact\":").append(JsonEscape.quote(artifact))
        append("}")
    }
}

/**
 * The full answer to `jdx resources` (listing mode): non-class paths
 * grouped by artifact. The one result model both renderers read.
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

/**
 * The answer to `jdx resources <path> --cat` (issue #25 content-extraction
 * slice): one resource's text body, or a binary refusal that names size
 * without dumping bytes. [lines] are the shown slice after `--max-lines`
 * truncation; empty when [binary] is true.
 */
public data class ResourceContent(
    public val query: String,
    public val path: String,
    public val artifact: String,
    public val sizeBytes: Long,
    public val binary: Boolean,
    public val lines: List<String>,
    public val truncation: Truncation?,
    public val warnings: List<Warning>,
    public val provenance: List<Provenance>,
) {
    /**
     * Text layout: path header, artifact + size, either verbatim text lines
     * or a binary refusal, truncation footer, next hint.
     */
    public fun renderText(color: Boolean = false): String {
        val out = mutableListOf(path)
        out.add("  artifact: $artifact")
        out.add("  size: $sizeBytes bytes")
        if (binary) {
            out.add("  binary: contents not shown (not UTF-8 text)")
        } else {
            out.addAll(lines)
            truncation?.let {
                out.add("${it.shown} of ${it.total} lines shown (${it.hint} to see more)")
            }
        }
        for (warning in warnings) out.add("warning ${warning.code}: ${warning.message}")
        out.add("next: jdx resources '$path' --in $artifact")
        val plain = out.joinToString("\n")
        return if (color) Ansi.colorizeListing(plain) else plain
    }

    /** Full JSON envelope; every text fact appears here structurally (D-007). */
    public fun toJson(command: String): String {
        val resultJson = buildString {
            append("{\"path\":").append(JsonEscape.quote(path))
            append(",\"artifact\":").append(JsonEscape.quote(artifact))
            append(",\"sizeBytes\":").append(sizeBytes)
            append(",\"binary\":").append(binary)
            if (!binary) {
                append(",\"text\":").append(JsonEscape.quote(lines.joinToString("\n")))
            }
            append("}")
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

/**
 * Splits resource text into display lines the same way [TokenBudget] does:
 * universal newlines, a single trailing newline is a terminator not a
 * phantom line. Empty input yields an empty list.
 */
public fun splitResourceLines(text: String): List<String> {
    if (text.isEmpty()) return emptyList()
    var lines = text.split(Regex("\r\n|\n|\r"))
    if (lines.size > 1 && lines.last().isEmpty()) lines = lines.dropLast(1)
    return lines
}

/**
 * Builds [ResourceContent] for a text resource: caps [fileLines] to
 * [maxLines] whole lines from the top. [binary] content uses
 * [buildBinaryResourceContent] instead.
 */
public fun buildResourceContent(
    query: String,
    path: String,
    artifact: String,
    sizeBytes: Long,
    fileLines: List<String>,
    maxLines: Int = DEFAULT_RESOURCE_MAX_LINES,
    warnings: List<Warning> = emptyList(),
    provenance: List<Provenance> = emptyList(),
): ResourceContent {
    val effectiveMax = maxLines.coerceAtLeast(0)
    val truncation = if (fileLines.size > effectiveMax) {
        Truncation(shown = effectiveMax, total = fileLines.size, hint = "--max-lines ${fileLines.size}")
    } else {
        null
    }
    return ResourceContent(
        query = query,
        path = path,
        artifact = artifact,
        sizeBytes = sizeBytes,
        binary = false,
        lines = fileLines.take(effectiveMax),
        truncation = truncation,
        warnings = warnings,
        provenance = provenance,
    )
}

/**
 * Builds [ResourceContent] for a binary resource: size is reported, bytes
 * are never embedded (AGENTS.md §2.1 — never dump binaries into context).
 */
public fun buildBinaryResourceContent(
    query: String,
    path: String,
    artifact: String,
    sizeBytes: Long,
    warnings: List<Warning> = emptyList(),
    provenance: List<Provenance> = emptyList(),
): ResourceContent = ResourceContent(
    query = query,
    path = path,
    artifact = artifact,
    sizeBytes = sizeBytes,
    binary = true,
    lines = emptyList(),
    truncation = null,
    warnings = warnings,
    provenance = provenance,
)
