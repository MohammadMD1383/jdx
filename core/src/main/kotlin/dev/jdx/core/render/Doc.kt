package dev.jdx.core.render

import dev.jdx.core.model.Provenance
import dev.jdx.core.model.Warning

/** Default cap on shown doc lines; the footer names `--max-lines` to see more. */
public const val DEFAULT_DOC_MAX_LINES: Int = 200

/**
 * What a [DocBlock] documents: a whole type, or one member kind. Member words
 * match [MemberKind]; types and enum entries have no [MemberKind] counterpart.
 */
public enum class DocSubject(public val word: String) {
    TYPE("type"),
    CONSTRUCTOR("constructor"),
    METHOD("method"),
    FIELD("field"),
    ENUM_ENTRY("enum-entry"),
}

/**
 * Renders one raw javadoc comment to plain-text lines (T-025, PROPOSAL.md §7.1).
 *
 * [raw] is the `JavadocComment` content the `sources` seam hands over: comment
 * delimiters already gone, one leading `*` per line still present. Rendering:
 * leading stars stripped, HTML tags dropped, `{@code}`/`{@link}`/`{@literal}`/
 * `{@value}` unwrapped to their text, `{@inheritDoc}` replaced with
 * [inheritDocReplacement] (or dropped when `null` — the caller resolves the
 * supertype text), `@param`/`@return`/`@throws`/`@since`/`@deprecated`/`@see`
 * kept as a tidy block after a blank line. Returns an empty list for blank
 * input; never throws on hostile input.
 *
 * HTML stripping is deliberately conservative: only a known tag set
 * (`p br code pre b i em strong ul ol li table tr td th a img div span
 * h1-h6 blockquote hr`) is dropped, so `List<String>` in prose survives.
 * Inline-tag content is never HTML-stripped (`{@literal <T>}` keeps `<T>`).
 */
public fun renderJavadoc(raw: String, inheritDocReplacement: String? = null): List<String> {
    val stripped = raw.lines().map { stripStar(it).trimEnd() }
    val firstTag = stripped.indexOfFirst { it.trimStart().startsWith("@") }
    val description = (if (firstTag == -1) stripped else stripped.subList(0, firstTag))
        .map(::renderDocText).dropBlankEnds()
    val tags = if (firstTag == -1) {
        emptyList()
    } else {
        stripped.subList(firstTag, stripped.size).map(::renderDocText).dropBlankEnds()
    }
    if (description.isEmpty() && tags.isEmpty()) return emptyList()
    val rendered = when {
        description.isEmpty() -> tags
        tags.isEmpty() -> description
        else -> description + "" + tags
    }
    return rendered.map { replaceInheritDoc(it, inheritDocReplacement) }
}

private fun List<String>.dropBlankEnds(): List<String> =
    dropWhile { it.isBlank() }.dropLastWhile { it.isBlank() }

/**
 * Strips one leading javadoc star (`* foo` becomes `foo`, a bare `*` becomes
 * empty) after whitespace. Star-less lines — the content of a single-line
 * comment keeps its padding — are leading-trimmed the same way, so rendered
 * lines never start with comment-layout whitespace.
 */
private fun stripStar(line: String): String {
    val trimmed = line.trimStart()
    if (!trimmed.startsWith("*")) return trimmed
    val rest = trimmed.drop(1)
    return if (rest.startsWith(" ")) rest.drop(1) else rest
}

/** Renders one comment line: inline tags unwrapped, known HTML dropped, entities unescaped. */
private fun renderDocText(line: String): String {
    val out = StringBuilder()
    var index = 0
    while (index < line.length) {
        when {
            line.startsWith("{@", index) -> {
                val close = line.indexOf('}', index + 2)
                if (close == -1) {
                    out.append(line.substring(index))
                    break
                }
                val body = line.substring(index + 2, close)
                if (body.trimStart().takeWhile { !it.isWhitespace() }.equals("inheritDoc", ignoreCase = true)) {
                    // `{@inheritDoc}` is resolved by the caller (supertype text
                    // as [inheritDocReplacement]) — pass it through verbatim so
                    // the replacement below still finds it.
                    out.append(line.substring(index, close + 1))
                } else {
                    out.append(unescapeEntities(unwrapInlineTag(body)))
                }
                index = close + 1
            }
            line[index] == '<' -> {
                val close = line.indexOf('>', index + 1)
                val tag = if (close == -1) null else line.substring(index + 1, close).trimStart('/').substringBefore(' ').substringBefore('\t').lowercase()
                if (close != -1 && tag in HTML_TAGS) {
                    index = close + 1
                } else {
                    out.append(line[index])
                    index++
                }
            }
            line[index] == '&' -> {
                val entity = HTML_ENTITIES.keys.firstOrNull { line.startsWith(it, index) }
                if (entity == null) {
                    out.append('&')
                    index++
                } else {
                    out.append(HTML_ENTITIES.getValue(entity))
                    index += entity.length
                }
            }
            else -> {
                out.append(line[index])
                index++
            }
        }
    }
    return out.toString()
}

/** Unescapes the five entities [renderDocText] understands, for inline-tag content. */
private fun unescapeEntities(text: String): String {
    val out = StringBuilder()
    var index = 0
    while (index < text.length) {
        if (text[index] == '&') {
            val entity = HTML_ENTITIES.keys.firstOrNull { text.startsWith(it, index) }
            if (entity == null) {
                out.append('&')
                index++
            } else {
                out.append(HTML_ENTITIES.getValue(entity))
                index += entity.length
            }
        } else {
            out.append(text[index])
            index++
        }
    }
    return out.toString()
}

private val HTML_TAGS: Set<String> = setOf(
    "p", "br", "code", "pre", "b", "i", "em", "strong", "u",
    "ul", "ol", "li", "dl", "dt", "dd",
    "table", "tr", "td", "th", "thead", "tbody",
    "a", "img", "div", "span",
    "h1", "h2", "h3", "h4", "h5", "h6",
    "blockquote", "hr",
)

private val HTML_ENTITIES: Map<String, String> = mapOf(
    "&lt;" to "<",
    "&gt;" to ">",
    "&amp;" to "&",
    "&quot;" to "\"",
    "&#39;" to "'",
    "&nbsp;" to " ",
)

/**
 * Unwraps one `{@tag content}` body (braces already removed): links keep
 * their label (or the reference when labelless), code/literal/value keep
 * their content, unknown tags drop the tag name and keep the content.
 */
private fun unwrapInlineTag(body: String): String {
    val space = body.indexOfFirst { it.isWhitespace() }
    val name = (if (space == -1) body else body.substring(0, space)).lowercase()
    val content = if (space == -1) "" else body.substring(space + 1).trim()
    return when (name) {
        "link", "linkplain" -> {
            // `{@link com.Foo#bar the label}` → `the label`; labelless → the ref.
            val labelAt = content.indexOfFirst { it.isWhitespace() }
            if (labelAt == -1) content else content.substring(labelAt + 1).trim()
        }
        else -> content
    }
}

private fun replaceInheritDoc(line: String, replacement: String?): String =
    if (replacement == null) {
        line.replace(INHERIT_DOC, "")
    } else {
        line.replace(INHERIT_DOC, replacement)
    }

private val INHERIT_DOC: Regex = Regex("""\{@inheritDoc[^}]*\}""")

/**
 * The answer to a `jdx doc` query (T-025, PROPOSAL.md §7.1): one symbol's
 * rendered javadoc — the one result model both renderers read, mirroring
 * [BodyBlock].
 *
 * [lines] are the shown slice of the rendered (or `--raw` verbatim) comment
 * after `--max-lines` truncation; [startLine]/[endLine] are the 1-based
 * inclusive file range the doc was sliced from (the declaration range, or the
 * comment's own range when the seam recovers it). [inheritedFrom] names the
 * documenting supertype's binary name when the doc was inherited (IntelliJ
 * quick-doc semantics) — `null` for direct docs. Text⊆JSON (D-007) holds:
 * every shown line appears in the JSON `text`.
 */
public data class DocBlock(
    public val canonicalRef: String,
    public val declaringType: String,
    public val subject: DocSubject,
    public val file: String,
    public val startLine: Int,
    public val endLine: Int,
    public val lines: List<String>,
    public val raw: Boolean,
    public val inheritedFrom: String?,
    public val truncation: Truncation?,
    public val warnings: List<Warning>,
    public val provenance: List<Provenance>,
) {
    /** Text layout: ref header, source line, doc lines, truncation, warnings, next hint. */
    public fun renderText(color: Boolean = false): String {
        val out = mutableListOf(canonicalRef)
        var source = "  source: ${provenance.firstOrNull()?.artifact ?: "?"} · $file:$startLine-$endLine"
        if (inheritedFrom != null) source += " (inherited from $inheritedFrom)"
        out.add(source)
        out.addAll(lines)
        truncation?.let { out.add("${it.shown} of ${it.total} lines shown (${it.hint} to see more)") }
        for (warning in warnings) out.add("warning ${warning.code}: ${warning.message}")
        out.add("next: jdx show $declaringType")
        val plain = out.joinToString("\n")
        return if (color) Ansi.colorizeListing(plain) else plain
    }

    /** Full JSON envelope; every text fact appears here structurally (D-007). */
    public fun toJson(command: String): String {
        val resultJson = buildString {
            append("{\"ref\":").append(JsonEscape.quote(canonicalRef))
            append(",\"declaring\":").append(JsonEscape.quote(declaringType))
            append(",\"kind\":").append(JsonEscape.quote(subject.word))
            inheritedFrom?.let { append(",\"inheritedFrom\":").append(JsonEscape.quote(it)) }
            append(",\"file\":").append(JsonEscape.quote(file))
            append(",\"lines\":[").append(startLine).append(",").append(endLine).append("]")
            append(",\"text\":").append(JsonEscape.quote(lines.joinToString("\n")))
            append("}")
        }
        return envelopeJson(
            ok = true,
            command = command,
            query = canonicalRef,
            resultJson = resultJson,
            truncation = truncation,
            warnings = warnings,
            provenance = provenance,
        )
    }
}

/**
 * Builds the block from a rendered (or `--raw` verbatim) comment: cuts the
 * tail to whole lines at [maxLines]. An exact fit is not truncation; a
 * negative limit behaves as zero.
 */
public fun buildDocBlock(
    canonicalRef: String,
    declaringType: String,
    subject: DocSubject,
    file: String,
    startLine: Int,
    endLine: Int,
    rendered: List<String>,
    provenance: List<Provenance>,
    warnings: List<Warning> = emptyList(),
    raw: Boolean = false,
    inheritedFrom: String? = null,
    maxLines: Int = Int.MAX_VALUE,
): DocBlock {
    val (kept, truncation) = truncateEntities(rendered, maxLines) { total -> "--max-lines $total" }
    return DocBlock(
        canonicalRef = canonicalRef,
        declaringType = declaringType,
        subject = subject,
        file = file,
        startLine = startLine,
        endLine = endLine,
        lines = kept,
        raw = raw,
        inheritedFrom = inheritedFrom,
        truncation = truncation,
        warnings = warnings,
        provenance = provenance,
    )
}
