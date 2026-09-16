package dev.jdx.core.render

/**
 * Entity-preserving truncation (PROPOSAL.md §8.3, T-010).
 *
 * Truncation operates on whole member rows, never mid-entity: callers order rows
 * most-valuable-first (declared before inherited, `java.lang.Object` collapsed to
 * a pinned summary line) and cut the tail. A cut result always reports
 * `shown`/`total`/`hint` so an agent knows exactly how to see the rest.
 */
public data class Truncation(
    /** How many entities are shown. */
    public val shown: Int,
    /** How many entities exist in total. Invariant: `shown <= total`. */
    public val total: Int,
    /** The flag revealing the rest, e.g. `--limit 97`. Present exactly when cut. */
    public val hint: String,
) {
    /** Renders inside the JSON envelope: `{"shown":50,"total":97,"hint":"--limit 97"}`. */
    public fun toJson(): String =
        "{\"shown\":$shown,\"total\":$total,\"hint\":" + JsonEscape.quote(hint) + "}"
}

/**
 * Cuts [items] to at most [limit] whole entities. Returns the kept prefix and the
 * [Truncation] — `null` when everything fits (an exact fit is not truncation).
 * A negative limit behaves as zero; the hint names the total via [hintFor].
 */
public fun <T> truncateEntities(items: List<T>, limit: Int, hintFor: (total: Int) -> String): Pair<List<T>, Truncation?> {
    val effectiveLimit = limit.coerceAtLeast(0)
    if (items.size <= effectiveLimit) return items to null
    return items.take(effectiveLimit) to Truncation(
        shown = effectiveLimit,
        total = items.size,
        hint = hintFor(items.size),
    )
}
