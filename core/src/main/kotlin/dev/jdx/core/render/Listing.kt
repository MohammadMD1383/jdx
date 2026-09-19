package dev.jdx.core.render

import dev.jdx.core.model.ClassInfo
import dev.jdx.core.model.MemberSymbolRef
import dev.jdx.core.model.Provenance
import dev.jdx.core.model.TypeName
import dev.jdx.core.model.Warning
import dev.jdx.core.model.WarningCode
import dev.jdx.core.ref.SymbolRefPrinter
import dev.jdx.core.resolve.ResolvedField
import dev.jdx.core.resolve.ResolvedMembers
import dev.jdx.core.resolve.ResolvedMethod

/** Default cap on member rows per listing; the footer names `--limit` to see more. */
public const val DEFAULT_MEMBER_LIMIT: Int = 50

/** Binary name of the type whose members collapse onto one summary line. */
public const val OBJECT_BINARY_NAME: String = "java.lang.Object"

/** The three row kinds, in listing order: constructors, then methods, then fields. */
public enum class MemberKind(public val word: String, public val plural: String) {
    CONSTRUCTOR("constructor", "constructors"),
    METHOD("method", "methods"),
    FIELD("field", "fields"),
}

/**
 * One member row: the text signature plus everything JSON needs structurally.
 * [canonicalRef] is the copy-pasteable canonical reference (D-016); a `:return`
 * suffix is added only when sibling rows share name and erased parameters
 * (bridge/covariant overloads, PROPOSAL.md §6). [memberName] is the raw member
 * name (`<init>` for constructors) and exists so `--sort name` (T-062) orders
 * by name without parsing display text; it is intentionally absent from JSON
 * (the ref already carries the name — D-007 text⊆JSON is unchanged).
 */
public data class MemberRow(
    public val canonicalRef: String,
    public val kind: MemberKind,
    public val signature: String,
    public val declaringType: TypeName.ClassType,
    public val depth: Int,
    public val deprecated: Boolean,
    public val overriddenTypes: List<TypeName.ClassType>,
    public val hiddenTypes: List<TypeName.ClassType>,
    public val memberName: String,
) {
    /** The text line: `  method public int getX()`. */
    public fun textLine(): String = "  ${kind.word} $signature"

    /** The structural row: empty lists and `false` are omitted (token economy). */
    public fun toJson(): String = buildString {
        append("{\"ref\":").append(JsonEscape.quote(canonicalRef))
        append(",\"kind\":").append(JsonEscape.quote(kind.word))
        append(",\"declaring\":").append(JsonEscape.quote(declaringType.binaryName))
        append(",\"signature\":").append(JsonEscape.quote(signature))
        append(",\"depth\":").append(depth)
        if (overriddenTypes.isNotEmpty()) {
            append(",\"overridden\":")
            append(overriddenTypes.map { JsonEscape.quote(it.binaryName) }.joinToString(",", "[", "]"))
        }
        if (hiddenTypes.isNotEmpty()) {
            append(",\"hidden\":")
            append(hiddenTypes.map { JsonEscape.quote(it.binaryName) }.joinToString(",", "[", "]"))
        }
        if (deprecated) append(",\"deprecated\":true")
        append("}")
    }
}

/** Rows sharing a declaring type and kind, printed under one header. */
public data class MemberGroup(
    public val declaringType: TypeName.ClassType,
    public val depth: Int,
    public val kind: MemberKind,
    public val rows: List<MemberRow>,
) {
    /** `methods declared on com.example.Point:` vs `methods inherited from …:`. */
    public fun headerLine(): String {
        val relation = if (depth == 0) "declared on" else "inherited from"
        return "${kind.plural} $relation ${declaringType.binaryName}:"
    }
}

/** How many members of each kind the listing holds (pre-truncation, eligible). */
public data class MemberCounts(
    public val constructors: Int,
    public val methods: Int,
    public val fields: Int,
) {
    public fun toJson(): String =
        "{\"constructors\":$constructors,\"methods\":$methods,\"fields\":$fields}"
}

/** Collapsed `java.lang.Object` members: one summary line, expandable via `--from`. */
public data class ObjectSummary(
    public val count: Int,
    public val expandHint: String = "--from java.lang.Object",
) {
    public fun textLine(): String = "+ $count from java.lang.Object ($expandHint to expand)"

    public fun toJson(): String =
        "{\"count\":$count,\"expandHint\":" + JsonEscape.quote(expandHint) + "}"
}

/**
 * Row order for [buildMemberListing] (T-062, PROPOSAL.md §7.1).
 *
 * - [KIND] (default): groups in linearisation order (target first,
 *   `java.lang.Object` last), kind-then-name inside each group. This is the
 *   T-010 layout, preserved byte-identically.
 * - [NAME]: flat name-first order across kinds and groups — every row sorted
 *   by raw member name (`<init>` sorts as `<init>`), then signature, ref,
 *   declaring type and depth. Group headers are kept (text and JSON share the
 *   row order per D-007) but groups are run-length runs along the global
 *   order, so a kind header may repeat when names interleave across kinds.
 * - [DECLARING]: groups sorted alphabetically by declaring-type binary name,
 *   then kind; rows inside each group sort exactly as [KIND]. This differs from
 *   [KIND] whenever hierarchy names do not sort alphabetically.
 */
public enum class MemberSort(public val flag: String) {
    KIND("kind"),
    NAME("name"),
    DECLARING("declaring"),
    ;

    public companion object {
        /** Parses a `--sort` flag value case-insensitively, or null when unknown. */
        public fun fromFlag(flag: String): MemberSort? =
            entries.firstOrNull { it.flag.equals(flag, ignoreCase = true) }
    }
}

/** Knobs for [buildMemberListing]. T-011's flags (`--sort`, `--grep`, `--from`) extend this. */
public data class MemberListingOptions(
    /** Outline mode: only the target's own members, no inherited groups. */
    public val declaredOnly: Boolean = false,
    /** Collapse inherited `java.lang.Object` members onto one summary line. */
    public val collapseObjectMembers: Boolean = true,
    /** Maximum member rows shown; the rest become the truncation footer. */
    public val maxMembers: Int = DEFAULT_MEMBER_LIMIT,
    /** Row order (T-062): kind (default), flat name-first, or alphabetical declaring. */
    public val sort: MemberSort = MemberSort.KIND,
)

/**
 * The full answer to a members/outline query: grouped rows, the Object summary,
 * truncation, warnings and provenance — the one result model both renderers read.
 */
public data class MemberListing(
    public val query: TypeName.ClassType,
    public val counts: MemberCounts,
    public val groups: List<MemberGroup>,
    public val objectSummary: ObjectSummary?,
    public val truncation: Truncation?,
    public val warnings: List<Warning>,
    public val provenance: List<Provenance>,
) {
    /** Text layout per PROPOSAL.md §8.1: headers, kind-led lines, explicit truncation. */
    public fun renderText(color: Boolean = false): String {
        val lines = mutableListOf("members of ${query.binaryName}")
        for (group in groups) {
            lines.add(group.headerLine())
            for (row in group.rows) lines.add(row.textLine())
        }
        objectSummary?.let { lines.add(it.textLine()) }
        truncation?.let { lines.add("${it.shown} of ${it.total} members shown (${it.hint} to see more)") }
        for (warning in warnings) lines.add("warning ${warning.code}: ${warning.message}")
        lines.add(provenanceText())
        val plain = lines.joinToString("\n")
        return if (color) Ansi.colorizeListing(plain) else plain
    }

    private fun provenanceText(): String = if (provenance.isEmpty()) {
        "source: no provenance recorded"
    } else {
        provenance.joinToString("\n") { entry ->
            val base = entry.origin.name.lowercase().replace('_', '-')
            val file = entry.file ?: return@joinToString "source: ${entry.artifact} ($base)"
            val range = entry.lineRange
            if (range == null) "source: ${entry.artifact} ($base, $file)"
            else "source: ${entry.artifact} ($base, $file:${range.first}-${range.last})"
        }
    }

    /** Full JSON envelope; every text row's signature and ref appears here (D-007). */
    public fun toJson(command: String): String {
        val resultJson = buildString {
            append("{\"groups\":[")
            append(
                groups.joinToString(",") { group ->
                    "{\"declaring\":" + JsonEscape.quote(group.declaringType.binaryName) +
                        ",\"depth\":" + group.depth +
                        ",\"kind\":" + JsonEscape.quote(group.kind.word) +
                        ",\"rows\":[" + group.rows.joinToString(",") { it.toJson() } + "]}"
                },
            )
            append("],\"counts\":").append(counts.toJson())
            objectSummary?.let { append(",\"objectSummary\":").append(it.toJson()) }
            append("}")
        }
        return envelopeJson(
            ok = true,
            command = command,
            query = query.binaryName,
            resultJson = resultJson,
            truncation = truncation,
            warnings = warnings,
            provenance = provenance,
        )
    }
}

/**
 * Builds the listing from a target and its [ResolvedMembers]: rows grouped by
 * declaring type (linearisation order for [MemberSort.KIND] — target first,
 * `java.lang.Object` last — alphabetical for [MemberSort.DECLARING],
 * first-row order for the flat [MemberSort.NAME]), sorted inside each group
 * per the sort order, Object optionally collapsed, tail truncated to whole
 * rows. Missing supertypes become [WarningCode.UNRESOLVED_SUPERTYPE]
 * warnings — labelled, never silent holes.
 */
public fun buildMemberListing(
    target: ClassInfo,
    resolved: ResolvedMembers,
    provenance: List<Provenance>,
    options: MemberListingOptions = MemberListingOptions(),
): MemberListing {
    val warnings = resolved.missingSupertypes.map { missing ->
        Warning(
            code = WarningCode.UNRESOLVED_SUPERTYPE,
            message = "supertype ${missing.binaryName} is not in the workspace" +
                " — members inherited from it are missing",
            subject = target.name.binaryName,
        )
    }
    val order = resolved.linearization.mapIndexed { index, node -> node.type.binaryName to index }.toMap()
    val showDepth: (Int) -> Boolean = { depth -> !options.declaredOnly || depth == 0 }

    val methods = resolved.methods.filter { showDepth(it.depth) }
    // Siblings sharing name and erased parameters need `:return` on their refs (§6).
    // The key excludes the return type on purpose: a bridge and its target share
    // name and parameters and differ only in return — the erased descriptor would
    // count them as distinct and defeat the disambiguation.
    val siblingCounts = methods.groupingBy {
        Triple(
            it.declaringType.binaryName,
            it.member.name,
            it.member.descriptor.parameters.joinToString("") { parameter -> parameter.descriptor },
        )
    }.eachCount()
    val methodRows = methods.map { resolvedMethod ->
        val key = Triple(
            resolvedMethod.declaringType.binaryName,
            resolvedMethod.member.name,
            resolvedMethod.member.descriptor.parameters.joinToString("") { parameter -> parameter.descriptor },
        )
        toMethodRow(resolvedMethod, target, (siblingCounts[key] ?: 0) > 1)
    }
    val fieldRows = resolved.fields.filter { showDepth(it.depth) }.map { resolvedField ->
        toFieldRow(resolvedField)
    }

    val allRows = methodRows + fieldRows
    val collapsing = options.collapseObjectMembers && !options.declaredOnly
    val objectRows = if (collapsing) allRows.filter { it.declaringType.binaryName == OBJECT_BINARY_NAME } else emptyList()
    val objectSet = objectRows.map { it.canonicalRef }.toSet()

    val groups = when (options.sort) {
        MemberSort.KIND -> kindGroups(
            allRows.filter { it.canonicalRef !in objectSet },
            order,
        )
        MemberSort.DECLARING -> declaringGroups(
            allRows.filter { it.canonicalRef !in objectSet },
        )
        MemberSort.NAME -> nameGroups(
            allRows.filter { it.canonicalRef !in objectSet },
        )
    }

    val counts = MemberCounts(
        constructors = allRows.count { it.kind == MemberKind.CONSTRUCTOR },
        methods = allRows.count { it.kind == MemberKind.METHOD },
        fields = allRows.count { it.kind == MemberKind.FIELD },
    )
    val (keptGroups, truncation) = truncateGroups(groups, options.maxMembers, allRows.size)

    val summary = if (collapsing && objectRows.isNotEmpty()) ObjectSummary(count = objectRows.size) else null
    return MemberListing(
        query = target.name,
        counts = counts,
        groups = keptGroups,
        objectSummary = summary,
        truncation = truncation,
        warnings = warnings,
        provenance = provenance,
    )
}

private fun toMethodRow(resolved: ResolvedMethod, target: ClassInfo, disambiguateReturn: Boolean): MemberRow {
    val kind = if (resolved.member.name == "<init>") MemberKind.CONSTRUCTOR else MemberKind.METHOD
    val baseRef = SymbolRefPrinter.print(
        MemberSymbolRef(
            declaringType = resolved.declaringType,
            name = resolved.member.name,
            parameterTypes = resolved.member.descriptor.parameters,
        ),
    )
    // Constructors cannot overload on return type; bridges are always methods.
    val canonicalRef = if (disambiguateReturn && kind == MemberKind.METHOD) {
        baseRef + ":" + SignatureLines.renderTypeName(resolved.member.descriptor.returnType)
    } else {
        baseRef
    }
    return MemberRow(
        canonicalRef = canonicalRef,
        kind = kind,
        signature = SignatureLines.methodLine(
            member = resolved.member,
            substituted = resolved.substitutedSignature,
            declaringSimpleName = target.name.simpleName,
        ),
        declaringType = resolved.declaringType,
        depth = resolved.depth,
        deprecated = resolved.member.deprecated,
        overriddenTypes = resolved.overriddenTypes,
        hiddenTypes = emptyList(),
        memberName = resolved.member.name,
    )
}

private fun toFieldRow(resolved: ResolvedField): MemberRow = MemberRow(
    canonicalRef = SymbolRefPrinter.print(
        MemberSymbolRef(resolved.declaringType, resolved.member.name),
    ),
    kind = MemberKind.FIELD,
    signature = SignatureLines.fieldLine(resolved.member, resolved.substitutedSignature),
    declaringType = resolved.declaringType,
    depth = resolved.depth,
    deprecated = resolved.member.deprecated,
    overriddenTypes = emptyList(),
    hiddenTypes = resolved.hiddenTypes,
    memberName = resolved.member.name,
)

/**
 * The default (T-010) layout, preserved byte-identically: groups in
 * linearisation order, kind-then-name inside each group.
 */
private fun kindGroups(rows: List<MemberRow>, order: Map<String, Int>): List<MemberGroup> =
    rows
        .groupBy { it.declaringType.binaryName to it.kind }
        .map { (key, groupRows) ->
            val representative = groupRows.first()
            MemberGroup(
                declaringType = representative.declaringType,
                depth = representative.depth,
                kind = key.second,
                // kind-then-name: the group fixes the kind, so sort by signature text,
                // tie-broken by ref (overload order is otherwise declaration order).
                rows = groupRows.sortedWith(compareBy({ it.signature }, { it.canonicalRef })),
            )
        }
        .sortedWith(compareBy({ order[it.declaringType.binaryName] ?: Int.MAX_VALUE }, { it.kind.ordinal }))

/**
 * Alphabetical declaring-type layout: groups sorted by declaring binary name,
 * then kind; rows inside each group sort exactly as [kindGroups].
 */
private fun declaringGroups(rows: List<MemberRow>): List<MemberGroup> =
    rows
        .groupBy { it.declaringType.binaryName to it.kind }
        .map { (key, groupRows) ->
            val representative = groupRows.first()
            MemberGroup(
                declaringType = representative.declaringType,
                depth = representative.depth,
                kind = key.second,
                rows = groupRows.sortedWith(compareBy({ it.signature }, { it.canonicalRef })),
            )
        }
        .sortedWith(compareBy({ it.declaringType.binaryName }, { it.kind.ordinal }))

/**
 * Flat name-first layout: every row sorted globally by raw member name, then
 * signature, ref, declaring type and depth. Groups are run-length runs along
 * that order (a new group each time the declaring type or kind changes), so a
 * kind header may repeat when names interleave across kinds — the price of
 * true flatness. Flattening the groups yields the globally sorted sequence,
 * in text and in JSON alike (D-007), and truncation keeps its prefix.
 */
private fun nameGroups(rows: List<MemberRow>): List<MemberGroup> {
    val ordered = rows.sortedWith(
        compareBy(
            { it.memberName },
            { it.signature },
            { it.canonicalRef },
            { it.declaringType.binaryName },
            { it.depth },
        ),
    )
    val groups = mutableListOf<MemberGroup>()
    var currentKey: Pair<String, MemberKind>? = null
    var currentRows = mutableListOf<MemberRow>()
    fun flush() {
        val key = currentKey
        if (key != null && currentRows.isNotEmpty()) {
            val representative = currentRows.first()
            groups.add(
                MemberGroup(
                    declaringType = representative.declaringType,
                    depth = representative.depth,
                    kind = key.second,
                    rows = currentRows.toList(),
                ),
            )
            currentRows = mutableListOf()
        }
    }
    for (row in ordered) {
        val key = row.declaringType.binaryName to row.kind
        if (key != currentKey) {
            flush()
            currentKey = key
        }
        currentRows.add(row)
    }
    flush()
    return groups
}

/**
 * Keeps the first [limit] rows across [groups] in order, dropping trailing empty
 * groups. The Object summary line is chrome (always printed), so it is not
 * counted; [total] is the eligible row count for the footer.
 */
private fun truncateGroups(
    groups: List<MemberGroup>,
    limit: Int,
    total: Int,
): Pair<List<MemberGroup>, Truncation?> {
    val effectiveLimit = limit.coerceAtLeast(0)
    if (total <= effectiveLimit) return groups to null
    var remaining = effectiveLimit
    val kept = mutableListOf<MemberGroup>()
    for (group in groups) {
        if (remaining <= 0) break
        val take = minOf(remaining, group.rows.size)
        kept.add(group.copy(rows = group.rows.take(take)))
        remaining -= take
    }
    return kept to Truncation(shown = effectiveLimit, total = total, hint = "--limit $total")
}
