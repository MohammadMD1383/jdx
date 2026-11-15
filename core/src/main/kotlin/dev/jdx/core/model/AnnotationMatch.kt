package dev.jdx.core.model

/**
 * Annotation value-predicate helpers for issue #26 (annotation-driven views).
 *
 * `--where key=value` on `usages --kind annotation` filters by
 * [AnnotationInfo.elements] when the structured [AnnotationValue] AST is
 * present (ASM), else by the rendered [AnnotationInfo.values] map. Matching
 * is exact on the element name and tolerant of a missing pair of quotes
 * around a string value so `--where since=99` matches a stored `"99"`.
 *
 * Nested elements accept dotted path keys (`--where nested.k=1` walks a
 * [AnnotationValue.Nested] or peels a stored `@Inner(…)` string). Array
 * values compare as order-insensitive multisets (`{2, 1}` matches `{1, 2}`),
 * with the same quote tolerance per element.
 *
 * When an element is omitted in bytecode, [defaults] (from the annotation
 * type's `AnnotationDefault` methods via [ClassInfo.annotationElementDefaults])
 * fills it for matching only — [renderUse] still prints explicit values so
 * agents see what was written.
 *
 * Agents may also spell the predicate inline as an annotation use
 * (`@java.lang.Deprecated(forRemoval=true)` / `Deprecated(forRemoval=true)`):
 * [peelAnnotationUseQuery] turns that into a plain type ref plus where
 * clauses so `--where` is optional for the common case.
 *
 * The dedicated `jdx annotated` command (same issue) accepts the same use
 * spelling plus short [ANNOTATION_RECIPES] aliases (`deprecated`, `forRemoval`,
 * `test`, `bean`, …) that expand to a well-known type + optional where clauses.
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
 * Peeled annotation-use query (issue #26 shortcut spelling).
 *
 * [typeRef] is the type to resolve — no leading `@`, no element-value
 * parentheses. [where] comes from the optional `(k=v, …)` suffix (may be
 * empty when the caller only wrote `@Type`).
 */
public data class AnnotationUseQuery(
    public val typeRef: String,
    public val where: List<AnnotationWhere> = emptyList(),
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
 * the values that matched (issue #26). Explicit values only — defaults are
 * never invented into the printed use.
 */
public fun AnnotationInfo.renderUse(): String {
    if (elements.isNotEmpty()) {
        val pairs = elements.entries
            .sortedBy { it.key }
            .joinToString(", ") { (key, value) -> "$key=${value.render()}" }
        return "@${type.fqn}($pairs)"
    }
    if (values.isEmpty()) return "@${type.fqn}"
    val pairs = values.entries
        .sortedBy { it.key }
        .joinToString(", ") { (key, rendered) -> "$key=$rendered" }
    return "@${type.fqn}($pairs)"
}

/**
 * True when every [where] clause matches this annotation's values.
 * An empty [where] always matches. A missing element name fails the clause
 * unless [defaults] supplies a rendered value that matches (annotation-default
 * merging, issue #26). Value comparison accepts the stored rendered form and
 * an unquoted variant of a quoted string (so `--where since=99` matches
 * `"99"`). Dotted keys walk into nested `@Inner(…)` values; `{…}` arrays
 * match as multisets. Nested paths do not consult [defaults] (only the
 * top-level annotation type's defaults are in scope for one query).
 */
public fun AnnotationInfo.matchesWhere(
    where: List<AnnotationWhere>,
    defaults: Map<String, String> = emptyMap(),
): Boolean {
    if (where.isEmpty()) return true
    return where.all { clause -> matchesClause(clause, defaults) }
}

/**
 * Matches one clause against this annotation's values, including dotted
 * nested paths (`outer.rest`). [defaults] fills omitted top-level elements.
 */
internal fun AnnotationInfo.matchesClause(
    clause: AnnotationWhere,
    defaults: Map<String, String> = emptyMap(),
): Boolean {
    val dot = clause.key.indexOf('.')
    if (dot > 0) {
        val outer = clause.key.substring(0, dot)
        val rest = clause.key.substring(dot + 1)
        if (rest.isEmpty()) return false
        val structured = elements[outer]
        if (structured is AnnotationValue.Nested) {
            return annotationInfo(structured.type, structured.elements)
                .matchesClause(AnnotationWhere(rest, clause.value))
        }
        val stored = values[outer] ?: return false
        val nested = peelNestedAnnotationValue(stored) ?: return false
        return nested.matchesClause(AnnotationWhere(rest, clause.value))
    }
    val structured = elements[clause.key]
    if (structured != null) {
        return annotationValueMatches(structured.render(), clause.value)
    }
    if (elements.isNotEmpty()) {
        // AST present but this key was omitted — try annotation defaults.
        val fallback = defaults[clause.key] ?: return false
        return annotationValueMatches(fallback, clause.value)
    }
    val stored = values[clause.key] ?: defaults[clause.key] ?: return false
    return annotationValueMatches(stored, clause.value)
}

/**
 * Peels a rendered nested annotation value (`@Fqn` / `@Fqn(k=v,…)`) into an
 * [AnnotationInfo] for path matching. Null when [stored] is not that shape.
 */
internal fun peelNestedAnnotationValue(stored: String): AnnotationInfo? {
    val trimmed = stored.trim()
    if (!trimmed.startsWith('@')) return null
    val peeled = peelAnnotationUseQuery(trimmed)?.getOrNull() ?: return null
    val pairs = linkedMapOf<String, String>()
    for (clause in peeled.where) {
        pairs[clause.key] = clause.value
    }
    return AnnotationInfo(typeNameFromBinaryName(peeled.typeRef), pairs)
}

/**
 * Exact match, unquoted string tolerance, or order-insensitive array multiset
 * compare when both sides look like `{…}`. Does not invent escapes — only
 * strips one matching quote pair from either side of a scalar.
 */
internal fun annotationValueMatches(stored: String, wanted: String): Boolean {
    if (stored == wanted) return true
    val storedInner = unquoteOnce(stored)
    val wantedInner = unquoteOnce(wanted)
    if (storedInner == wanted ||
        stored == wantedInner ||
        storedInner == wantedInner
    ) {
        return true
    }
    if (looksLikeArray(stored) && looksLikeArray(wanted)) {
        return arrayElementsMatch(stored, wanted)
    }
    return false
}

private fun looksLikeArray(raw: String): Boolean =
    raw.length >= 2 && raw.first() == '{' && raw.last() == '}'

/**
 * Compares two rendered `{a, b}` arrays as multisets. Each element uses
 * [annotationValueMatches] so nested arrays / quote variants still pair.
 */
private fun arrayElementsMatch(stored: String, wanted: String): Boolean {
    val storedEls = splitAnnotationElements(stored.substring(1, stored.length - 1))
    val wantedEls = splitAnnotationElements(wanted.substring(1, wanted.length - 1))
    if (storedEls.size != wantedEls.size) return false
    val remaining = storedEls.toMutableList()
    for (w in wantedEls) {
        val idx = remaining.indexOfFirst { annotationValueMatches(it, w) }
        if (idx < 0) return false
        remaining.removeAt(idx)
    }
    return remaining.isEmpty()
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

/**
 * Peels `@Type` / `Type(k=v,…)` / `@Type(k=v,…)` into a type ref + where
 * clauses (issue #26 shortcut).
 *
 * Returns `null` when [raw] is not annotation-use spelling: no leading `@`
 * and no trailing element-value parentheses that contain `=`. Ordinary
 * symbol refs (`Gson#toJson(Object)`, `Foo(Object)`, bare type names) stay
 * untouched so [dev.jdx.core.ref.SymbolRefParser] keeps owning them.
 *
 * A leading `@` or a trailing `(k=v,…)` commits to this grammar: malformed
 * element lists return a failure [Result] (never a silent null).
 */
public fun peelAnnotationUseQuery(raw: String): Result<AnnotationUseQuery>? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null
    val atPrefixed = trimmed.startsWith('@')
    val body = if (atPrefixed) trimmed.substring(1).trimStart() else trimmed
    if (body.isEmpty()) {
        return Result.failure(
            IllegalArgumentException("usage error: annotation use '@' needs a type name"),
        )
    }
    // Member refs keep their own `(…)` grammar — never peel across `#` / `::`.
    if (body.contains('#') || body.contains("::")) return null

    if (!body.endsWith(')')) {
        // `@Type` alone is annotation-use spelling; bare `Type` / unclosed
        // `Type(…` without a committed `@` stays for SymbolRefParser.
        if (!atPrefixed) {
            return if (body.contains('(') && body.substringAfter('(').contains('=')) {
                Result.failure(
                    IllegalArgumentException(
                        "usage error: annotation use '$raw' is missing a closing ')'",
                    ),
                )
            } else {
                null
            }
        }
        if (body.contains('(')) {
            return Result.failure(
                IllegalArgumentException(
                    "usage error: annotation use '$raw' is missing a closing ')'",
                ),
            )
        }
        return Result.success(AnnotationUseQuery(body))
    }

    val paren = matchingOpenParen(body)
        ?: return if (atPrefixed) {
            Result.failure(
                IllegalArgumentException(
                    "usage error: annotation use '$raw' has unbalanced parentheses",
                ),
            )
        } else {
            null
        }
    val inside = body.substring(paren + 1, body.length - 1)
    // `Type(Object)` / `Type(...)` are the member-ref grammar, not annotation
    // elements — leave them alone unless the caller wrote a leading `@`.
    if (!atPrefixed && !inside.contains('=')) return null

    val typeRef = body.substring(0, paren).trim()
    if (typeRef.isEmpty()) {
        return Result.failure(
            IllegalArgumentException("usage error: annotation use '$raw' needs a type name"),
        )
    }
    if (inside.isBlank()) {
        return Result.success(AnnotationUseQuery(typeRef))
    }
    val clauses = mutableListOf<AnnotationWhere>()
    for (piece in splitAnnotationElements(inside)) {
        val parsed = parseAnnotationWhere(piece)
        if (parsed.isFailure) {
            return Result.failure(
                IllegalArgumentException(
                    parsed.exceptionOrNull()!!.message
                        ?: "usage error: --where expects key=value (got '$piece')",
                ),
            )
        }
        clauses.add(parsed.getOrThrow())
    }
    return Result.success(AnnotationUseQuery(typeRef, clauses))
}

/**
 * Index of the `(` that matches the final `)` of [body], respecting quotes.
 * Null when the trailing paren group is unbalanced.
 */
internal fun matchingOpenParen(body: String): Int? {
    if (!body.endsWith(')')) return null
    var depth = 0
    var quote: Char? = null
    for (i in body.lastIndex downTo 0) {
        val c = body[i]
        if (quote != null) {
            if (c == quote) {
                // Skip escaped quotes (`\"` / `\'`): the char before the quote
                // is a backslash that is itself unescaped.
                if (i > 0 && body[i - 1] == '\\') continue
                quote = null
            }
            continue
        }
        when (c) {
            '"', '\'' -> quote = c
            ')' -> depth++
            '(' -> {
                depth--
                if (depth == 0) return i
            }
        }
    }
    return null
}

/**
 * Splits a comma-separated annotation element list while respecting quoted
 * strings, nested `{…}` arrays, and nested `(…)` / `@Inner(…)` values.
 */
internal fun splitAnnotationElements(inside: String): List<String> {
    if (inside.isBlank()) return emptyList()
    val parts = mutableListOf<String>()
    val buf = StringBuilder()
    var depthParen = 0
    var depthBrace = 0
    var quote: Char? = null
    var i = 0
    while (i < inside.length) {
        val c = inside[i]
        if (quote != null) {
            buf.append(c)
            if (c == '\\' && i + 1 < inside.length) {
                buf.append(inside[i + 1])
                i += 2
                continue
            }
            if (c == quote) quote = null
            i++
            continue
        }
        when (c) {
            '"', '\'' -> {
                quote = c
                buf.append(c)
            }
            '(' -> {
                depthParen++
                buf.append(c)
            }
            ')' -> {
                depthParen--
                buf.append(c)
            }
            '{' -> {
                depthBrace++
                buf.append(c)
            }
            '}' -> {
                depthBrace--
                buf.append(c)
            }
            ',' -> {
                if (depthParen == 0 && depthBrace == 0) {
                    parts.add(buf.toString().trim())
                    buf.setLength(0)
                } else {
                    buf.append(c)
                }
            }
            else -> buf.append(c)
        }
        i++
    }
    parts.add(buf.toString().trim())
    return parts.filter { it.isNotEmpty() }
}

/**
 * Well-known short aliases for `jdx annotated` (issue #26 recipes).
 *
 * Keys are lowercase; [lookupAnnotationRecipe] matches case-insensitively.
 * A recipe is only the bare name (optional leading `@`, no package dots, no
 * `(…)` element list) — FQNs and annotation-use spelling stay on the peel /
 * resolve path. Recipe-supplied where clauses AND-merge with any caller
 * `--where` flags.
 */
public val ANNOTATION_RECIPES: Map<String, AnnotationUseQuery> = mapOf(
    "deprecated" to AnnotationUseQuery("java.lang.Deprecated"),
    "forremoval" to AnnotationUseQuery(
        "java.lang.Deprecated",
        listOf(AnnotationWhere("forRemoval", "true")),
    ),
    "test" to AnnotationUseQuery("org.junit.jupiter.api.Test"),
    "junit5" to AnnotationUseQuery("org.junit.jupiter.api.Test"),
    "junit4" to AnnotationUseQuery("org.junit.Test"),
    "bean" to AnnotationUseQuery("org.springframework.context.annotation.Bean"),
)

/**
 * Canonical recipe spellings for `--help` / docs (stable order, human case).
 * Lookup stays case-insensitive via [ANNOTATION_RECIPES].
 */
public val ANNOTATION_RECIPE_NAMES: List<String> = listOf(
    "deprecated",
    "forRemoval",
    "test",
    "junit5",
    "junit4",
    "bean",
)

/**
 * Resolved query for `jdx annotated`: the string handed to
 * [dev.jdx.index.service.JdxService.usages] plus any recipe-supplied where
 * clauses. [recipe] is the matched alias spelling when a recipe fired
 * (null for peel / bare-type paths).
 */
public data class AnnotatedQuery(
    public val query: String,
    public val recipeWhere: List<AnnotationWhere> = emptyList(),
    public val recipe: String? = null,
)

/**
 * Looks up a recipe alias. Returns null when [raw] is not a bare recipe name
 * (FQN, member ref, or `(k=v)` use spelling → peel / SymbolRefParser).
 */
public fun lookupAnnotationRecipe(raw: String): AnnotationUseQuery? {
    var body = raw.trim()
    if (body.startsWith('@')) body = body.substring(1).trimStart()
    if (body.isEmpty()) return null
    // Recipes are single bare words — anything with package/member/element
    // punctuation is a real type or annotation-use spelling.
    if (body.any { it == '.' || it == '#' || it == '$' || it == '(' || it == ')' || it == ':' }) {
        return null
    }
    return ANNOTATION_RECIPES[body.lowercase()]
}

/**
 * Resolves the subject of `jdx annotated <query>` (issue #26 dedicated
 * command). Recipes expand first; everything else is returned unchanged so
 * `usages --kind annotation` can peel `@Type(k=v)` / `Type(k=v)` or resolve
 * a bare type.
 */
public fun resolveAnnotatedQuery(raw: String): AnnotatedQuery {
    val trimmed = raw.trim()
    val recipe = lookupAnnotationRecipe(trimmed) ?: return AnnotatedQuery(trimmed)
    val name = trimmed.trim().removePrefix("@").trimStart()
    return AnnotatedQuery(
        query = recipe.typeRef,
        recipeWhere = recipe.where,
        recipe = name,
    )
}
