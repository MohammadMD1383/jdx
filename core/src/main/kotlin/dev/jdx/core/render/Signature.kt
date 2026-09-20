package dev.jdx.core.render

import dev.jdx.core.model.Provenance
import dev.jdx.core.model.Warning

/** Default cap on signature rows; the footer names `--limit` to see more. */
public const val DEFAULT_SIGNATURE_LIMIT: Int = 50

/**
 * One signature row: the source-style signature text plus everything JSON
 * needs structurally. [canonicalRef] is the copy-pasteable canonical
 * reference (D-016); a `:return` suffix is added only when sibling rows share
 * name and erased parameters (bridge/covariant overloads, PROPOSAL.md §6) —
 * the same rule [MemberListing] uses.
 */
public data class SignatureEntry(
    public val canonicalRef: String,
    public val kind: MemberKind,
    public val signature: String,
    public val declaringType: String,
)

/**
 * The answer to a `jdx signature` query (T-024, PROPOSAL.md §7.1): one
 * signature line per matching overload with real parameter names, generics,
 * throws and defaults — the one result model both renderers read, mirroring
 * [BodyBlock].
 *
 * Signatures come from bytecode alone (the T-010 [SignatureLines] over the
 * matched `MethodInfo`/`FieldInfo`): no sources are read, so this command
 * answers sources-less jars by design. [query] is the canonical
 * `Declaring#name` header (no parameter list — one block may hold many
 * overloads); [signatures] are in declaration order.
 */
public data class SignatureBlock(
    public val query: String,
    public val declaringType: String,
    public val signatures: List<SignatureEntry>,
    public val truncation: Truncation?,
    public val warnings: List<Warning>,
    public val provenance: List<Provenance>,
) {
    /** Text layout: header, one bare signature line per overload, source line, next hint. */
    public fun renderText(color: Boolean = false): String {
        val out = mutableListOf("signatures of $query")
        for (entry in signatures) out.add("  ${entry.signature}")
        truncation?.let { out.add("${it.shown} of ${it.total} signatures shown (${it.hint} to see more)") }
        for (warning in warnings) out.add("warning ${warning.code}: ${warning.message}")
        out.add(provenanceText())
        out.add("next: jdx show $declaringType")
        val plain = out.joinToString("\n")
        return if (color) Ansi.colorizeListing(plain) else plain
    }

    private fun provenanceText(): String = if (provenance.isEmpty()) {
        "source: no provenance recorded"
    } else {
        provenance.joinToString("\n") { entry ->
            val base = entry.origin.name.lowercase().replace('_', '-')
            "source: ${entry.artifact} ($base)"
        }
    }

    /** Full JSON envelope; every text signature and ref appears here structurally (D-007). */
    public fun toJson(command: String): String {
        val resultJson = buildString {
            append("{\"query\":").append(JsonEscape.quote(query))
            append(",\"declaring\":").append(JsonEscape.quote(declaringType))
            append(",\"signatures\":[")
            append(
                signatures.joinToString(",") { entry ->
                    "{\"ref\":" + JsonEscape.quote(entry.canonicalRef) +
                        ",\"kind\":" + JsonEscape.quote(entry.kind.word) +
                        ",\"signature\":" + JsonEscape.quote(entry.signature) +
                        ",\"declaring\":" + JsonEscape.quote(entry.declaringType) + "}"
                },
            )
            append("]}")
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
 * Builds the block from matched entries in declaration order, cutting the
 * tail to whole rows at [maxSignatures]. An exact fit is not truncation; a
 * negative limit behaves as zero.
 */
public fun buildSignatureBlock(
    query: String,
    declaringType: String,
    entries: List<SignatureEntry>,
    provenance: List<Provenance>,
    warnings: List<Warning> = emptyList(),
    maxSignatures: Int = DEFAULT_SIGNATURE_LIMIT,
): SignatureBlock {
    val (kept, truncation) = truncateEntities(entries, maxSignatures) { total -> "--limit $total" }
    return SignatureBlock(
        query = query,
        declaringType = declaringType,
        signatures = kept,
        truncation = truncation,
        warnings = warnings,
        provenance = provenance,
    )
}
