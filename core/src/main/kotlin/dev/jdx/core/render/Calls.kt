package dev.jdx.core.render

import dev.jdx.core.model.Provenance
import dev.jdx.core.model.Warning

/** Default cap on call-hierarchy tree nodes; the footer names `--limit` to see more. */
public const val DEFAULT_CALLS_LIMIT: Int = 50

/** Default transitive walk distance; `--depth N` widens it. */
public const val DEFAULT_CALLS_DEPTH: Int = 1

/**
 * Which side of the call edge a hierarchy query walks (T-033, PROPOSAL.md §7.3):
 * incoming edges (`jdx callers` — who calls this method) or outgoing edges
 * (`jdx calls` — what this method calls).
 */
public enum class CallDirection(public val flag: String) {
    CALLERS("callers"),
    CALLS("calls"),
}

/**
 * One method in a call-hierarchy tree: its canonical ref
 * (`Owner#member(params)`), the artifact holding it — null when the edge names
 * a class outside the workspace (known from a caller's bytes, provider
 * unknown) — whether re-entering it would cycle (a leaf by definition), and
 * its callees/callers one level deeper.
 */
public data class CallNode(
    public val ref: String,
    public val artifact: String?,
    public val cycle: Boolean = false,
    public val children: List<CallNode> = emptyList(),
)

/**
 * One flattened tree row: the node's facts plus its pre-order depth (roots
 * are depth 1). The listing stores rows, not trees, so truncation is a plain
 * prefix cut — a pre-order prefix is always a connected forest, never an
 * orphaned child — and text and JSON render the same sequence (D-007).
 */
public data class CallRow(
    public val ref: String,
    public val artifact: String?,
    public val cycle: Boolean,
    public val depth: Int,
) {
    /** The text line: `    call com.example.App#run()  app.jar` (`…(cycle)` when re-entrant). */
    public fun textLine(): String = buildString {
        append("  ".repeat(depth))
        append("call ").append(ref)
        if (artifact != null) append("  ").append(artifact)
        if (cycle) append(" …(cycle)")
    }

    /** The structural row: every text fact appears here (D-007). */
    public fun toJson(): String = buildString {
        append("{\"ref\":").append(JsonEscape.quote(ref))
        if (artifact != null) append(",\"artifact\":").append(JsonEscape.quote(artifact))
        append(",\"depth\":").append(depth)
        append(",\"cycle\":").append(cycle)
        append("}")
    }
}

/**
 * The full answer to `jdx callers` / `jdx calls`: the resolved query target,
 * the walked direction, and the tree flattened pre-order. The one result model
 * both renderers read, mirroring [UsageListing].
 */
public data class CallListing(
    public val query: String,
    public val targetRef: String,
    public val direction: CallDirection,
    public val rows: List<CallRow>,
    public val truncation: Truncation?,
    public val warnings: List<Warning>,
    public val provenance: List<Provenance>,
) {
    /** Text layout per PROPOSAL.md §8.1: direction header, depth-indented kind-led lines. */
    public fun renderText(color: Boolean = false): String {
        if (rows.isEmpty() && truncation == null) {
            return if (direction == CallDirection.CALLERS) "no callers of '$query'" else "no calls from '$query'"
        }
        val header = if (direction == CallDirection.CALLERS) "callers of '$query'" else "calls from '$query'"
        val lines = mutableListOf(header)
        for (row in rows) lines.add(row.textLine())
        truncation?.let { lines.add("${it.shown} of ${it.total} calls shown (${it.hint} to see more)") }
        for (warning in warnings) lines.add("warning ${warning.code}: ${warning.message}")
        val plain = lines.joinToString("\n")
        return if (color) Ansi.colorizeListing(plain) else plain
    }

    /** Full JSON envelope; every text row's ref, artifact, depth and cycle flag appears here (D-007). */
    public fun toJson(command: String): String {
        val resultJson = "{\"target\":" + JsonEscape.quote(targetRef) +
            ",\"direction\":" + JsonEscape.quote(direction.flag) +
            ",\"calls\":[" + rows.joinToString(",") { it.toJson() } + "]}"
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
 * Builds the listing from already-ordered [roots]: flattens pre-order (roots
 * depth 1, cycle rows as leaves), keeps the first [limit] rows and reports the
 * rest. Ordered input is the caller's contract (D-007 determinism) — children
 * sorted before this is called — and this function never re-sorts, so a
 * misordered caller is visible in goldens instead of being silently repaired.
 */
public fun buildCallListing(
    query: String,
    targetRef: String,
    direction: CallDirection,
    roots: List<CallNode>,
    limit: Int = DEFAULT_CALLS_LIMIT,
    warnings: List<Warning> = emptyList(),
    provenance: List<Provenance> = emptyList(),
): CallListing {
    val flat = mutableListOf<CallRow>()
    for (root in roots) flattenCallNode(root, 1, flat)
    val (kept, truncation) = truncateEntities(flat, limit) { "--limit $it" }
    return CallListing(
        query = query,
        targetRef = targetRef,
        direction = direction,
        rows = kept,
        truncation = truncation,
        warnings = warnings,
        provenance = provenance,
    )
}

/**
 * Appends one node and its subtree pre-order. Cycle rows append as leaves:
 * their re-entry is already named by the `…(cycle)` marker, so descending
 * would print the ancestor's subtree twice and never terminate the count.
 */
private fun flattenCallNode(node: CallNode, depth: Int, into: MutableList<CallRow>) {
    into.add(CallRow(ref = node.ref, artifact = node.artifact, cycle = node.cycle, depth = depth))
    if (node.cycle) return
    for (child in node.children) flattenCallNode(child, depth + 1, into)
}
