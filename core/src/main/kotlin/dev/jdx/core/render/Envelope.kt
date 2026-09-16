package dev.jdx.core.render

import dev.jdx.core.model.Provenance
import dev.jdx.core.model.Warning

/**
 * The `--json` envelope (PROPOSAL.md §8.2, T-010).
 *
 * ```json
 * {"jdx":1,"ok":true,"command":"members","query":"com.example.Point",
 *  "result":{...},"truncated":{"shown":50,"total":97,"hint":"--limit 97"},
 *  "warnings":[],"provenance":[]}
 * ```
 *
 * `ok:false` carries `error:{"code","message"}` plus `candidates[]`. Key order is
 * fixed and arrays are renderer-sorted, so identical inputs yield identical bytes.
 * The version stays 1: T-010 only *adds* the optional `query`/`truncated`/
 * `warnings`/`provenance` fields D-027 reserved, which old readers ignore.
 */
public const val ENVELOPE_VERSION: Int = 1

internal fun envelopeJson(
    ok: Boolean,
    command: String,
    query: String,
    resultJson: String?,
    errorJson: String?,
    candidates: List<String>?,
    truncation: Truncation?,
    warnings: List<Warning>,
    provenance: List<Provenance>,
): String = buildString {
    append("{\"jdx\":").append(ENVELOPE_VERSION)
    append(",\"ok\":").append(ok)
    append(",\"command\":").append(JsonEscape.quote(command))
    append(",\"query\":").append(JsonEscape.quote(query))
    if (resultJson != null) append(",\"result\":").append(resultJson)
    if (errorJson != null) append(",\"error\":").append(errorJson)
    if (candidates != null) {
        append(",\"candidates\":")
        append(candidates.map { JsonEscape.quote(it) }.joinToString(",", "[", "]"))
    }
    if (truncation != null) append(",\"truncated\":").append(truncation.toJson())
    append(",\"warnings\":[")
    append(warnings.joinToString(",") { it.toJson() })
    append("],\"provenance\":[")
    append(provenance.joinToString(",") { it.toJson() })
    append("]}")
}

/** Shared success envelope for listings; errors use [ErrorResult.toJson]. */
public fun envelopeJson(
    ok: Boolean,
    command: String,
    query: String,
    resultJson: String,
    truncation: Truncation?,
    warnings: List<Warning>,
    provenance: List<Provenance>,
): String = envelopeJson(
    ok = ok,
    command = command,
    query = query,
    resultJson = resultJson,
    errorJson = null,
    candidates = null,
    truncation = truncation,
    warnings = warnings,
    provenance = provenance,
)

/** `{"code":"UNRESOLVED_SUPERTYPE","message":"…","subject":"…"}`; subject omitted when null. */
public fun Warning.toJson(): String = buildString {
    append("{\"code\":").append(JsonEscape.quote(code.name))
    append(",\"message\":").append(JsonEscape.quote(message))
    if (subject != null) append(",\"subject\":").append(JsonEscape.quote(subject))
    append("}")
}

/**
 * `{"artifact":"…","origin":"bytecode"}` plus `file`/`lines` when known
 * (source-derived only). Origin prints lowercase, `_` as `-`.
 */
public fun Provenance.toJson(): String = buildString {
    append("{\"artifact\":").append(JsonEscape.quote(artifact))
    append(",\"origin\":").append(JsonEscape.quote(origin.name.lowercase().replace('_', '-')))
    if (file != null) append(",\"file\":").append(JsonEscape.quote(file))
    val range = lineRange
    if (file != null && range != null) append(",\"lines\":[").append(range.first).append(",").append(range.last).append("]")
    append("}")
}
