package dev.jdx.core.model

/**
 * The kind of a bytecode reference edge (T-029, PROPOSAL.md §10.3 `ref` table).
 *
 * Produced by `index` (ASM extraction over method bodies); consumed by the M4
 * graph queries (`usages`, `callers`/`calls`, `samples`). The set is closed on
 * purpose: every edge the extractor emits maps to one of these, so query
 * `--kind` filters (T-030) have a stable vocabulary to speak.
 */
public enum class ReferenceKind {
    /** A method invocation (`invoke*`, plus `invokedynamic` bootstrap/handle targets). */
    METHOD_CALL,

    /** A field read (`getfield`, `getstatic`). */
    FIELD_READ,

    /** A field write (`putfield`, `putstatic`). */
    FIELD_WRITE,

    /**
     * Any other mention of a type: `new`, `anewarray`, `checkcast`,
     * `instanceof`, `ldc` class constants, `invokedynamic` type arguments,
     * method/field descriptors' owner types are *not* repeated here — only
     * instructions whose purpose is the type itself.
     */
    TYPE_REFERENCE,
}

/**
 * One directed reference edge inside a method body (T-029).
 *
 * `from*` names the enclosing method (binary class name, method name,
 * erased method descriptor — `<init>`/`<clinit>` included); `toOwner` is the
 * referenced type's binary name; `toMember`/`toDescriptor` name the referenced
 * member for call/field edges and are `null` for pure [ReferenceKind.TYPE_REFERENCE]
 * edges. [line] is the 1-based `LineNumberTable` entry for the first occurrence
 * of this edge in the method body, or `null` when the class was compiled
 * without debug info (`-g:none`) — degrade, don't fail (AGENTS.md §2.8).
 * [sourceFile] is the class's `SourceFile` attribute (simple name, e.g.
 * `App.java`), or `null` when absent; it is extract-time payload for
 * `file:line` rendering and is not part of the edge's logical identity
 * (the store persists [line] only — `class.source_file` already holds the
 * file). Edges are values: extraction is deterministic and sorted (D-007), so
 * indexing the same bytes twice yields identical rows.
 *
 * Binary names use `$`-joined nesting (`java.util.Map$Entry`), matching
 * [TypeName.ClassType.binaryName] — the same key the index stores classes under.
 *
 * Dedup identity is everything except [line]/[sourceFile]: a loop calling
 * `foo()` ten times is still one edge; the kept [line] is the first
 * occurrence in bytecode order (issue #8).
 */
public data class ReferenceEdge(
    public val fromClass: String,
    public val fromMember: String,
    public val fromDescriptor: String,
    public val toOwner: String,
    public val toMember: String?,
    public val toDescriptor: String?,
    public val kind: ReferenceKind,
    public val line: Int? = null,
    public val sourceFile: String? = null,
) {
    public companion object {
        /**
         * The canonical order edges are stored and compared in: source first
         * (class, member, descriptor), then kind, then target, then line.
         * The extractor emits this order and the store returns it, so two
         * runs over the same bytes are byte-identical (D-007). [sourceFile]
         * is not ordered on — it is class-scoped payload that rides along.
         */
        public fun compare(a: ReferenceEdge, b: ReferenceEdge): Int {
            var order = a.fromClass.compareTo(b.fromClass)
            if (order != 0) return order
            order = a.fromMember.compareTo(b.fromMember)
            if (order != 0) return order
            order = a.fromDescriptor.compareTo(b.fromDescriptor)
            if (order != 0) return order
            order = a.kind.compareTo(b.kind)
            if (order != 0) return order
            order = a.toOwner.compareTo(b.toOwner)
            if (order != 0) return order
            order = compareNullable(a.toMember, b.toMember)
            if (order != 0) return order
            order = compareNullable(a.toDescriptor, b.toDescriptor)
            if (order != 0) return order
            return compareNullableInt(a.line, b.line)
        }

        private fun compareNullable(a: String?, b: String?): Int = when {
            a == null && b == null -> 0
            a == null -> -1
            b == null -> 1
            else -> a.compareTo(b)
        }

        /** Null lines sort after known lines so located edges come first. */
        private fun compareNullableInt(a: Int?, b: Int?): Int = when {
            a == null && b == null -> 0
            a == null -> 1
            b == null -> -1
            else -> a.compareTo(b)
        }
    }
}

/**
 * Returns a sorted copy of this edge list in [ReferenceEdge.compare] order.
 * Idempotent: sorting twice yields identical bytes.
 */
public fun List<ReferenceEdge>.sortedEdges(): List<ReferenceEdge> =
    this.sortedWith(ReferenceEdge::compare)
