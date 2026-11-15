package dev.jdx.core.model

/**
 * Annotation value-predicate helpers for issue #26 (annotation-driven views).
 *
 * `--where key=value` on `usages --kind annotation` filters by the rendered
 * [AnnotationInfo.values] map (ASM's deterministic string form from
 * `AsmClassReader.renderValue`). Matching is exact on the element name and
 * tolerant of a missing pair of quotes around a string value so
 * `--where since=99` matches a stored `"99"`.
 *
 * Rendering sorts element names so the same annotation use is byte-identical
 * regardless of source / bytecode declaration order (AGENTS.md §2.5).
 */

/**
 * One `--where key=value` clause. [key] is the annotation element name;
 * [value] is the caller-supplied spelling (may omit quotes that the rendered
 * form carries for strings).
 */
public data class AnnotationWhere(
    public val key: String,
    public val value: String,
)

/**
 * Parses one `--where key=value` clause. Splits on the first `=`; blank key
 * or a missing `=` is a usage error (returned as the failure message).
 */
public fun parseAnnotationWhere(raw: String): Result<AnnotationWhere> {
    val eq = raw.indexOf('=')
    if (eq <= 0) {
        return Result.failure(
            IllegalArgumentException(
                "usage error: --where expects key=value (got '$raw')",
            ),
        )
    }
    val key = raw.substring(0, eq).trim()
    if (key.isEmpty()) {
        return Result.failure(
            IllegalArgumentException(
                "usage error: --where expects key=value (got '$raw')",
            ),
        )
    }
    val value = raw.substring(eq + 1).trim()
    return Result.success(AnnotationWhere(key, value))
}

/**
 * Renders this annotation use as `@Fqn` or `@Fqn(k=v, …)` with element names
 * sorted. Used on `usages --kind annotation` rows so the next command can see
 * the values that matched (issue #26).
 */
public fun AnnotationInfo.renderUse(): String {
    if (values.isEmpty()) return "@${type.fqn}"
    val pairs = values.entries
        .sortedBy { it.key }
        .joinToString(", ") { (key, rendered) -> "$key=$rendered" }
    return "@${type.fqn}($pairs)"
}

/**
 * True when every [where] clause matches this annotation's rendered values.
 * An empty [where] always matches. A missing element name fails the clause;
 * value comparison accepts the stored rendered form and an unquoted variant
 * of a quoted string (so `--where since=99` matches `"99"`).
 */
public fun AnnotationInfo.matchesWhere(where: List<AnnotationWhere>): Boolean {
    if (where.isEmpty()) return true
    return where.all { clause ->
        val stored = values[clause.key] ?: return@all false
        annotationValueMatches(stored, clause.value)
    }
}

/**
 * Exact match, or unquoted-wanted against a quote-wrapped stored string
 * (and the reverse). Does not invent escapes — only strips one matching
 * quote pair from either side.
 */
internal fun annotationValueMatches(stored: String, wanted: String): Boolean {
    if (stored == wanted) return true
    val storedInner = unquoteOnce(stored)
    val wantedInner = unquoteOnce(wanted)
    return storedInner == wanted ||
        stored == wantedInner ||
        storedInner == wantedInner
}

private fun unquoteOnce(raw: String): String {
    if (raw.length < 2) return raw
    val first = raw.first()
    val last = raw.last()
    return if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
        raw.substring(1, raw.length - 1)
    } else {
        raw
    }
}
