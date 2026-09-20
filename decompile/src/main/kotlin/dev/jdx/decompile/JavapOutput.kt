package dev.jdx.decompile

/**
 * One member's disassembly inside `javap -c -p -s` output (T-027): the raw
 * declaration line plus its detail lines (`descriptor:`, `flags:`, the `Code:`
 * block, …). [startLine]/[endLine] are 1-based inclusive rows in the full
 * output, so the service slices and numbers `body`/`source` answers exactly
 * like the Vineflower path.
 */
public data class JavapMemberSection(
    /** The raw declaration line, trimmed (`public U identity(U);`). */
    public val declaration: String,
    /** The erased JVM descriptor from the `descriptor:` line, or `null`. */
    public val descriptor: String?,
    /** Every section line, declaration first. */
    public val lines: List<String>,
    /** 1-based inclusive range in the full output. */
    public val startLine: Int,
    /** 1-based inclusive range in the full output. */
    public val endLine: Int,
)

/**
 * Splits `javap -c -p -s` output into member sections (T-027). Pure string
 * work: no process, no disk — tier 1.
 *
 * The `Compiled from` header, the class declaration and the closing `}` are
 * filtered first (a class declaration ends with `{`, which no member detail
 * line does); what remains splits on blank lines, and every block whose first
 * line ends with `;` is a member section. Filtering first (rather than
 * block-matching) matters because real `javap` prints no blank line between
 * the class declaration and the first member. The `;` rule (not a keyword
 * allowlist) is what separates declarations from detail lines: field
 * descriptors (`descriptor: Lpkg/Foo;`) can themselves end with `;`, but they
 * never start a block.
 */
public fun splitJavapSections(output: String): List<JavapMemberSection> {
    val lines = output.split('\n').map { line -> line.removeSuffix("\r") }
    val sections = mutableListOf<JavapMemberSection>()
    var block = mutableListOf<Pair<Int, String>>()
    fun flush() {
        if (block.isNotEmpty()) {
            val first = block.first().second.trim()
            if (first.endsWith(";")) {
                val descriptor = block.map { it.second.trim() }
                    .firstOrNull { it.startsWith("descriptor:") }
                    ?.removePrefix("descriptor:")?.trim()
                sections.add(
                    JavapMemberSection(
                        declaration = first,
                        descriptor = descriptor,
                        lines = block.map { it.second },
                        startLine = block.first().first + 1,
                        endLine = block.last().first + 1,
                    ),
                )
            }
        }
        block = mutableListOf()
    }
    lines.forEachIndexed { index, line ->
        val trimmed = line.trim()
        when {
            line.isBlank() -> flush()
            trimmed.startsWith("Compiled from") || trimmed.endsWith("{") || trimmed == "}" -> Unit
            else -> block.add(index to line)
        }
    }
    flush()
    return sections
}

/**
 * The simple member name carried by a `javap` declaration line: `identity`
 * for methods, the field name for fields, `<init>` for constructors (printed
 * as the FQN — they contain a `.`, unlike method names), `<clinit>` for the
 * class initialiser. Mirrors the test-oracle normalisation, stated once here
 * so the engine and the oracle cannot drift.
 */
public fun javapMemberName(declaration: String): String {
    val bare = declaration.trim().removeSuffix(";").trim()
    if (bare == "static {}") return "<clinit>"
    if ('(' in bare) {
        val nameToken = bare.substringBefore('(').trim().substringAfterLast(' ')
        return if ('.' in nameToken) "<init>" else nameToken
    }
    return bare.substringAfterLast(' ')
}

/**
 * Finds the section for one bytecode member (T-027): the erased JVM
 * [descriptor] is the match — it is unique per class — with the declaration
 * name breaking the (practically impossible) tie of two sections sharing a
 * descriptor. `memberName` is `<init>` for constructors. Returns `null` when
 * no section carries the descriptor.
 */
public fun findJavapSection(
    sections: List<JavapMemberSection>,
    memberName: String,
    descriptor: String,
): JavapMemberSection? {
    val candidates = sections.filter { it.descriptor == descriptor }
    if (candidates.isEmpty()) return null
    if (candidates.size == 1) return candidates.single()
    return candidates.firstOrNull { javapMemberName(it.declaration) == memberName }
        ?: candidates.first()
}
