package dev.jdx.core.render

import dev.jdx.core.model.Access
import dev.jdx.core.model.AccessFlag
import dev.jdx.core.model.ClassInfo
import dev.jdx.core.model.Provenance
import dev.jdx.core.model.TypeKind
import dev.jdx.core.model.Warning

/**
 * The class card behind `jdx show` (T-011, PROPOSAL.md §7.1): kind, modifiers,
 * supertypes, a declared-member count summary (never the members themselves),
 * warnings, provenance and a `next:` hint — the one result model both renderers
 * read, mirroring [MemberListing].
 *
 * Counts are *declared* counts from [ClassInfo]: `show` answers "what is this
 * type", while `members --inherited` answers "what can I call on it".
 */
public data class ClassCard(
    public val target: ClassInfo,
    public val counts: MemberCounts,
    public val warnings: List<Warning>,
    public val provenance: List<Provenance>,
) {
    /** Text layout per PROPOSAL.md §8.1: kind-led lines, explicit next hint. */
    public fun renderText(color: Boolean = false): String {
        val info = target
        val lines = mutableListOf("${kindWord(info.kind)} ${info.name.binaryName}")
        lines.add("  ${declarationLine(info)}")
        superclassLine(info)?.let { lines.add("  $it") }
        interfacesLine(info)?.let { lines.add("  $it") }
        if (info.deprecated) lines.add("  deprecated")
        if (info.isKotlin) lines.add("  kotlin")
        lines.add("  ${countLine(counts)}")
        for (warning in warnings) lines.add("warning ${warning.code}: ${warning.message}")
        lines.add(provenanceText())
        lines.add("next: jdx members ${info.name.binaryName} --inherited")
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

    /** Full JSON envelope; every text fact appears here structurally (D-007). */
    public fun toJson(command: String): String {
        val info = target
        val resultJson = buildString {
            append("{\"kind\":").append(JsonEscape.quote(kindWord(info.kind)))
            append(",\"declaration\":").append(JsonEscape.quote(declarationLine(info)))
            append(",\"modifiers\":[")
            append(modifiersOf(info.access).joinToString(",") { JsonEscape.quote(it) })
            append("]")
            val superclass = info.superclass?.binaryName
            if (superclass != null) append(",\"superclass\":").append(JsonEscape.quote(superclass))
            append(",\"interfaces\":[")
            append(info.interfaces.joinToString(",") { JsonEscape.quote(it.binaryName) })
            append("]")
            append(",\"counts\":").append(counts.toJson())
            if (info.deprecated) append(",\"deprecated\":true")
            if (info.isKotlin) append(",\"kotlin\":true")
            append("}")
        }
        return envelopeJson(
            ok = true,
            command = command,
            query = info.name.binaryName,
            resultJson = resultJson,
            truncation = null,
            warnings = warnings,
            provenance = provenance,
        )
    }
}

/**
 * Builds the card from a resolved [ClassInfo]: declared constructors, methods
 * and fields counted separately (`<init>` is a constructor, never a method).
 */
public fun buildClassCard(
    target: ClassInfo,
    provenance: List<Provenance>,
    warnings: List<Warning> = emptyList(),
): ClassCard {
    val constructors = target.methods.count { it.name == "<init>" }
    val methods = target.methods.count { it.name != "<init>" && it.name != "<clinit>" }
    return ClassCard(
        target = target,
        counts = MemberCounts(
            constructors = constructors,
            methods = methods,
            fields = target.fields.size,
        ),
        warnings = warnings,
        provenance = provenance,
    )
}

private fun kindWord(kind: TypeKind): String = when (kind) {
    TypeKind.CLASS -> "class"
    TypeKind.INTERFACE -> "interface"
    TypeKind.ENUM -> "enum"
    TypeKind.RECORD -> "record"
    TypeKind.ANNOTATION -> "annotation"
    TypeKind.OBJECT -> "object"
    TypeKind.COMPANION -> "companion"
}

private fun kindKeyword(kind: TypeKind): String = when (kind) {
    TypeKind.ANNOTATION -> "@interface"
    else -> kindWord(kind)
}

/** JVM modifiers worth printing on a declaration line, in JLS order. */
private fun modifiersOf(access: Access): List<String> = buildList {
    when {
        access.has(AccessFlag.PUBLIC) -> add("public")
        access.has(AccessFlag.PROTECTED) -> add("protected")
        access.has(AccessFlag.PRIVATE) -> add("private")
    }
    if (access.has(AccessFlag.ABSTRACT)) add("abstract")
    if (access.has(AccessFlag.STATIC)) add("static")
    if (access.has(AccessFlag.FINAL)) add("final")
}

private fun declarationLine(info: ClassInfo): String {
    val modifiers = modifiersOf(info.access)
    val keyword = kindKeyword(info.kind)
    val simple = info.name.simpleName
    return ((modifiers + keyword + simple).joinToString(" "))
}

private fun superclassLine(info: ClassInfo): String? {
    // Interfaces and java.lang.Object normalise superclass to null (ClassInfo KDoc).
    val superclass = info.superclass?.binaryName ?: return null
    return "extends $superclass"
}

private fun interfacesLine(info: ClassInfo): String? {
    if (info.interfaces.isEmpty()) return null
    // Interfaces extend superinterfaces; classes implement them.
    val verb = if (info.kind == TypeKind.INTERFACE) "extends" else "implements"
    return "$verb ${info.interfaces.joinToString(", ") { it.binaryName }}"
}

private fun countLine(counts: MemberCounts): String {
    fun plural(count: Int, singular: String): String =
        "$count $singular" + if (count == 1) "" else "s"
    return "members: " + listOf(
        plural(counts.constructors, "constructor"),
        plural(counts.methods, "method"),
        plural(counts.fields, "field"),
    ).joinToString(", ")
}
