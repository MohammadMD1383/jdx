package dev.jdx.core.render

import dev.jdx.core.model.Provenance
import dev.jdx.core.model.TypeKind
import dev.jdx.core.model.Warning

/** Default cap on search/ls/tree rows; the footer names `--limit` to see more. */
public const val DEFAULT_SEARCH_LIMIT: Int = 50

/** Default package-tree depth for `jdx tree` (0 means top-level segments only). */
public const val DEFAULT_TREE_DEPTH: Int = 8

/**
 * One symbol hit: the kind-led line plus everything JSON needs structurally.
 * [ref] is the copy-pasteable canonical reference (a binary type name, a
 * `Type#member(params)` member ref, a dotted package name, or a module name);
 * [artifact] is the display label of the providing root (jar file name or JDK
 * module, PROPOSAL.md §13). Hits from shadowing duplicates list one row per
 * provider — the honest answer for `search` (D-031).
 */
public data class SearchHit(
    public val kind: String,
    public val ref: String,
    public val artifact: String,
    public val packageName: String,
) {
    /** The text line: `  class com.google.gson.Gson  gson-2.14.0.jar`. */
    public fun textLine(): String = "  $kind $ref  $artifact"

    /** The structural row: every text fact appears here (D-007). */
    public fun toJson(): String = buildString {
        append("{\"kind\":").append(JsonEscape.quote(kind))
        append(",\"ref\":").append(JsonEscape.quote(ref))
        append(",\"artifact\":").append(JsonEscape.quote(artifact))
        append(",\"package\":").append(JsonEscape.quote(packageName))
        append("}")
    }
}

/**
 * The full answer to `search`/`resolve`: sorted hits, truncation, warnings.
 * The one result model both renderers read, mirroring [MemberListing].
 */
public data class SearchListing(
    public val query: String,
    public val hits: List<SearchHit>,
    public val truncation: Truncation?,
    public val warnings: List<Warning>,
    public val provenance: List<Provenance>,
) {
    /** Text layout per PROPOSAL.md §8.1: kind-led lines, explicit truncation. */
    public fun renderText(color: Boolean = false): String {
        val lines = mutableListOf("search results for '$query'")
        for (hit in hits) lines.add(hit.textLine())
        truncation?.let { lines.add("${it.shown} of ${it.total} results shown (${it.hint} to see more)") }
        for (warning in warnings) lines.add("warning ${warning.code}: ${warning.message}")
        val plain = lines.joinToString("\n")
        return if (color) Ansi.colorizeListing(plain) else plain
    }

    /** Full JSON envelope; every text row's kind, ref and artifact appears here (D-007). */
    public fun toJson(command: String): String {
        val resultJson = "{\"hits\":[" + hits.joinToString(",") { it.toJson() } + "]}"
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
 * Builds the listing from already-sorted [hits]: keeps the first [limit] whole
 * rows and reports the rest. Sorted input is the caller's contract (D-007
 * determinism); this function never re-sorts, so a misordered caller is visible
 * in goldens instead of being silently repaired.
 */
public fun buildSearchListing(
    query: String,
    hits: List<SearchHit>,
    limit: Int = DEFAULT_SEARCH_LIMIT,
    warnings: List<Warning> = emptyList(),
    provenance: List<Provenance> = emptyList(),
): SearchListing {
    val (kept, truncation) = truncateEntities(hits, limit) { "--limit $it" }
    return SearchListing(
        query = query,
        hits = kept,
        truncation = truncation,
        warnings = warnings,
        provenance = provenance,
    )
}

/** The `search --kind` word for a [TypeKind] (Kotlin kinds keep their own words). */
public fun searchKindWord(kind: TypeKind): String = when (kind) {
    TypeKind.CLASS -> "class"
    TypeKind.INTERFACE -> "interface"
    TypeKind.ENUM -> "enum"
    TypeKind.RECORD -> "record"
    TypeKind.ANNOTATION -> "annotation"
    TypeKind.OBJECT -> "object"
    TypeKind.COMPANION -> "companion"
}

/**
 * One package row for `jdx ls`: the dotted name and how many indexed types sit
 * directly in it (nested packages count separately — the tree shows rollups).
 */
public data class PackageEntry(
    public val name: String,
    public val typeCount: Int,
) {
    /** The text line: `  package com.google.gson (12 types)`. */
    public fun textLine(): String = "  package $name ($typeCount ${if (typeCount == 1) "type" else "types"})"

    /** The structural row: every text fact appears here (D-007). */
    public fun toJson(): String = buildString {
        append("{\"name\":").append(JsonEscape.quote(name))
        append(",\"types\":").append(typeCount)
        append("}")
    }
}

/**
 * One type row for `jdx ls <exact-package>`: the kind-led line, mirroring
 * [SearchHit] without the package (it is the query).
 */
public data class LsTypeEntry(
    public val kind: String,
    public val ref: String,
    public val artifact: String,
) {
    /** The text line: `  class com.google.gson.Gson  gson-2.14.0.jar`. */
    public fun textLine(): String = "  $kind $ref  $artifact"

    /** The structural row: every text fact appears here (D-007). */
    public fun toJson(): String = buildString {
        append("{\"kind\":").append(JsonEscape.quote(kind))
        append(",\"ref\":").append(JsonEscape.quote(ref))
        append(",\"artifact\":").append(JsonEscape.quote(artifact))
        append("}")
    }
}

/**
 * The full answer to `jdx ls [package-glob]`: matching packages with counts,
 * plus — only when the glob names one exact package — the types inside it.
 * The one result model both renderers read.
 */
public data class LsListing(
    public val query: String,
    public val packages: List<PackageEntry>,
    public val types: List<LsTypeEntry>,
    public val truncation: Truncation?,
    public val warnings: List<Warning>,
) {
    /** Text layout: packages first, then the exact package's types. */
    public fun renderText(color: Boolean = false): String {
        val lines = mutableListOf<String>()
        if (types.isNotEmpty()) lines.add("types in package '$query'")
        else lines.add("packages matching '$query'")
        for (entry in packages) lines.add(entry.textLine())
        for (entry in types) lines.add(entry.textLine())
        truncation?.let { lines.add("${it.shown} of ${it.total} rows shown (${it.hint} to see more)") }
        for (warning in warnings) lines.add("warning ${warning.code}: ${warning.message}")
        val plain = lines.joinToString("\n")
        return if (color) Ansi.colorizeListing(plain) else plain
    }

    /** Full JSON envelope; every text row appears here structurally (D-007). */
    public fun toJson(command: String): String {
        val resultJson = "{\"packages\":[" + packages.joinToString(",") { it.toJson() } +
            "],\"types\":[" + types.joinToString(",") { it.toJson() } + "]}"
        return envelopeJson(
            ok = true,
            command = command,
            query = query,
            resultJson = resultJson,
            truncation = truncation,
            warnings = warnings,
            provenance = emptyList(),
        )
    }
}

/**
 * Builds the `ls` listing: packages sorted by name, types sorted by ref then
 * artifact, both truncated as one flat row sequence (packages first) so the
 * footer count covers everything shown.
 */
public fun buildLsListing(
    query: String,
    packages: List<PackageEntry>,
    types: List<LsTypeEntry>,
    limit: Int = DEFAULT_SEARCH_LIMIT,
    warnings: List<Warning> = emptyList(),
): LsListing {
    val total = packages.size + types.size
    val effectiveLimit = limit.coerceAtLeast(0)
    if (total <= effectiveLimit) {
        return LsListing(query, packages, types, truncation = null, warnings = warnings)
    }
    val keptPackages = packages.take(effectiveLimit)
    val remaining = effectiveLimit - keptPackages.size
    val keptTypes = if (remaining <= 0) emptyList() else types.take(remaining)
    return LsListing(
        query = query,
        packages = keptPackages,
        types = keptTypes,
        truncation = Truncation(shown = effectiveLimit, total = total, hint = "--limit $total"),
        warnings = warnings,
    )
}

/**
 * One package-tree node for `jdx tree`: [segment] is this level's name part
 * (`google` in `com.google`), [packageName] the dotted path from the root,
 * [typeCount] the types in this package's subtree, [children] sorted by segment.
 */
public data class TreeNode(
    public val segment: String,
    public val packageName: String,
    public val typeCount: Int,
    public val children: List<TreeNode> = emptyList(),
) {
    /** Appends this subtree at [indent], honouring [withCounts]. */
    internal fun textLines(indent: String, withCounts: Boolean): List<String> {
        val label = if (withCounts) {
            "$packageName ($typeCount ${if (typeCount == 1) "type" else "types"})"
        } else {
            packageName
        }
        val lines = mutableListOf("$indent$label")
        for (child in children) lines.addAll(child.textLines("$indent  ", withCounts))
        return lines
    }

    /** The structural node: every text fact appears here (D-007). */
    public fun toJson(withCounts: Boolean): String = buildString {
        append("{\"package\":").append(JsonEscape.quote(packageName))
        if (withCounts) append(",\"types\":").append(typeCount)
        append(",\"children\":[")
        append(children.joinToString(",") { it.toJson(withCounts) })
        append("]}")
    }
}

/**
 * One artifact's package forest for `jdx tree`: the artifact label plus its
 * top-level nodes (usually one per first package segment).
 */
public data class ArtifactTree(
    public val artifact: String,
    public val roots: List<TreeNode>,
) {
    /** Appends this artifact's block at no indent. */
    internal fun textLines(withCounts: Boolean): List<String> {
        val lines = mutableListOf(artifact)
        for (root in roots) lines.addAll(root.textLines("  ", withCounts))
        return lines
    }

    /** The structural block: every text fact appears here (D-007). */
    public fun toJson(withCounts: Boolean): String = buildString {
        append("{\"artifact\":").append(JsonEscape.quote(artifact))
        append(",\"packages\":[")
        append(roots.joinToString(",") { it.toJson(withCounts) })
        append("]}")
    }
}

/**
 * The full answer to `jdx tree <artifact-glob>`: one block per matching
 * artifact, each a depth-limited package forest. The one result model both
 * renderers read.
 */
public data class TreeListing(
    public val query: String,
    public val artifacts: List<ArtifactTree>,
    public val withCounts: Boolean,
    public val truncation: Truncation?,
    public val warnings: List<Warning>,
) {
    /** Text layout: one artifact header, then the indented forest. */
    public fun renderText(color: Boolean = false): String {
        val lines = mutableListOf("package tree for '$query'")
        for (artifact in artifacts) lines.addAll(artifact.textLines(withCounts))
        truncation?.let { lines.add("${it.shown} of ${it.total} package nodes shown (${it.hint} to see more)") }
        for (warning in warnings) lines.add("warning ${warning.code}: ${warning.message}")
        val plain = lines.joinToString("\n")
        return if (color) Ansi.colorizeListing(plain) else plain
    }

    /** Full JSON envelope; every text node appears here structurally (D-007). */
    public fun toJson(command: String): String {
        val resultJson = "{\"artifacts\":[" + artifacts.joinToString(",") { it.toJson(withCounts) } + "]}"
        return envelopeJson(
            ok = true,
            command = command,
            query = query,
            resultJson = resultJson,
            truncation = truncation,
            warnings = warnings,
            provenance = emptyList(),
        )
    }
}

/**
 * Builds the package forest for one artifact from its (sorted) package names:
 * segments nest, [depth] caps nesting (0 shows top-level segments only),
 * [typeCountOf] supplies each node's subtree count, [limit] caps whole nodes
 * breadth-first so truncation never cuts mid-node.
 */
public fun buildArtifactTree(
    artifact: String,
    packageNames: List<String>,
    typeCountOf: (packageName: String) -> Int,
    depth: Int = DEFAULT_TREE_DEPTH,
): ArtifactTree {
    val effectiveDepth = depth.coerceAtLeast(0)
    // Parent paths that exist only as prefixes (e.g. `com` when only
    // `com.google` has types) still become nodes so the forest connects.
    val allPaths = mutableSetOf<String>()
    for (name in packageNames) {
        if (name.isEmpty()) continue
        val segments = name.split('.')
        for (end in 1..segments.size) allPaths.add(segments.take(end).joinToString("."))
    }
    fun childrenOf(parent: String, level: Int): List<TreeNode> {
        if (level > effectiveDepth) return emptyList()
        val prefix = if (parent.isEmpty()) "" else "$parent."
        return allPaths
            .filter { it.startsWith(prefix) && it.removePrefix(prefix).contains('.').not() && it != parent }
            .sorted()
            .map { path ->
                TreeNode(
                    segment = path.removePrefix(prefix),
                    packageName = path,
                    typeCount = typeCountOf(path),
                    children = childrenOf(path, level + 1),
                )
            }
    }
    val roots = childrenOf("", 0)
    return ArtifactTree(artifact = artifact, roots = roots)
}

/**
 * Builds the `tree` listing over several artifacts (sorted by label already by
 * the caller): per-artifact node caps share one [limit] budget in order, so
 * output is deterministic and the footer covers the whole forest.
 */
public fun buildTreeListing(
    query: String,
    artifacts: List<ArtifactTree>,
    withCounts: Boolean,
    nodeTotal: Int,
    limit: Int = DEFAULT_SEARCH_LIMIT,
    warnings: List<Warning> = emptyList(),
): TreeListing {
    val effectiveLimit = limit.coerceAtLeast(0)
    if (nodeTotal <= effectiveLimit) {
        return TreeListing(query, artifacts, withCounts, truncation = null, warnings = warnings)
    }
    var remaining = effectiveLimit
    val kept = artifacts.map { artifact ->
        val (roots, _) = truncateTreeBreadthFirst(artifact.roots, remaining)
        remaining -= countNodes(roots)
        artifact.copy(roots = roots)
    }
    return TreeListing(
        query = query,
        artifacts = kept,
        withCounts = withCounts,
        truncation = Truncation(shown = effectiveLimit, total = nodeTotal, hint = "--limit $nodeTotal"),
        warnings = warnings,
    )
}

/** Counts every node in a forest (for truncation totals). */
public fun countNodes(roots: List<TreeNode>): Int {
    var total = 0
    val queue = ArrayDeque(roots)
    while (queue.isNotEmpty()) {
        val node = queue.removeFirst()
        total++
        queue.addAll(node.children)
    }
    return total
}

/** Keeps the first [limit] nodes breadth-first, dropping whole subtrees. */
private fun truncateTreeBreadthFirst(roots: List<TreeNode>, limit: Int): Pair<List<TreeNode>, Int> {
    if (limit <= 0) return emptyList<TreeNode>() to countNodes(roots)
    // Flatten breadth-first with parent links, keep the first `limit` paths.
    val order = mutableListOf<String>()
    val queue = ArrayDeque(roots.map { it.packageName })
    val byPath = mutableMapOf<String, TreeNode>()
    fun index(nodes: List<TreeNode>) {
        for (node in nodes) {
            byPath[node.packageName] = node
            index(node.children)
        }
    }
    index(roots)
    while (queue.isNotEmpty()) {
        val path = queue.removeFirst()
        order.add(path)
        val node = byPath[path] ?: continue
        queue.addAll(node.children.map { it.packageName })
    }
    val total = order.size
    if (total <= limit) return roots to total
    val kept = order.take(limit).toSet()
    fun prune(nodes: List<TreeNode>): List<TreeNode> =
        nodes.filter { it.packageName in kept }.map { it.copy(children = prune(it.children)) }
    return prune(roots) to total
}
