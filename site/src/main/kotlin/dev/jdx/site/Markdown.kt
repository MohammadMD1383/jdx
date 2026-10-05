package dev.jdx.site

import org.commonmark.ext.autolink.AutolinkExtension
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.node.AbstractVisitor
import org.commonmark.node.Code
import org.commonmark.node.Document
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.Heading
import org.commonmark.node.HtmlBlock
import org.commonmark.node.Image
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.Node
import org.commonmark.node.Paragraph
import org.commonmark.node.Text
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlNodeRendererContext
import org.commonmark.renderer.html.HtmlRenderer
import org.commonmark.renderer.markdown.MarkdownRenderer
import org.commonmark.renderer.NodeRenderer

/** A heading of a rendered page, with the GitHub-compatible id the site gives it. */
data class HeadingInfo(val level: Int, val text: String, val id: String)

/**
 * CommonMark + GFM tables + autolinks, rendered the way GitHub renders it where it matters
 * for links: heading ids follow GitHub's slug rules, so `docs/X.md#some-heading` links written
 * for GitHub keep working on the site unchanged.
 */
object Markdown {
    private val extensions = listOf(TablesExtension.create(), AutolinkExtension.create())
    private val parser: Parser = Parser.builder().extensions(extensions).build()

    fun parse(text: String): Document = parser.parse(text) as Document

    /** Concatenated literal text of a node's inline content (what GitHub slugs and what a TOC shows). */
    fun plainText(node: Node): String {
        val builder = StringBuilder()
        node.accept(object : AbstractVisitor() {
            override fun visit(text: Text) { builder.append(text.literal) }
            override fun visit(code: Code) { builder.append(code.literal) }
        })
        return builder.toString()
    }

    /** First H1's text, which every source doc uses as its title. */
    fun title(document: Document): String? =
        children(document).filterIsInstance<Heading>().firstOrNull { it.level == 1 }?.let(::plainText)?.trim()

    /** Removes the first H1 (the layout renders the page title itself). */
    fun removeTitle(document: Document) {
        children(document).filterIsInstance<Heading>().firstOrNull { it.level == 1 }?.unlink()
    }

    /** First paragraph's plain text — the fallback meta description. */
    fun firstParagraph(document: Document): String? =
        children(document).filterIsInstance<Paragraph>().firstOrNull()?.let(::plainText)
            ?.replace(Regex("\\s+"), " ")?.trim()

    fun children(node: Node): List<Node> {
        val result = mutableListOf<Node>()
        var child = node.firstChild
        while (child != null) {
            result += child
            child = child.next
        }
        return result
    }

    /**
     * The nodes of the section headed by the first heading whose text starts with
     * [headingPrefix], up to the next heading of the same or a higher level, moved into a new
     * document. Missing section = build failure: the landing page quotes README sections, and
     * a renamed heading must break the build, not silently empty the page.
     */
    fun extractSection(document: Document, headingPrefix: String, sourceName: String): Document {
        val heading = children(document).filterIsInstance<Heading>()
            .firstOrNull { plainText(it).trim().startsWith(headingPrefix) }
            ?: throw SiteBuildException("$sourceName has no heading starting with \"$headingPrefix\" (the site quotes it)")
        val section = Document()
        var node = heading.next
        while (node != null && !(node is Heading && node.level <= heading.level)) {
            val next = node.next
            section.appendChild(node)
            node = next
        }
        return section
    }

    /** Assigns GitHub-style ids to every heading, in document order. */
    fun headings(document: Node): List<HeadingInfo> {
        val slugger = GithubSlugger()
        val result = mutableListOf<HeadingInfo>()
        document.accept(object : AbstractVisitor() {
            override fun visit(heading: Heading) {
                val text = plainText(heading).trim()
                result += HeadingInfo(heading.level, text, slugger.slug(text))
            }
        })
        return result
    }

    /**
     * Shifts every heading so the shallowest one lands on [topLevel], keeping relative depth
     * (capped at H6). Embedding foreign Markdown (release notes) under a page heading must not
     * skip levels — screen readers navigate by them, and Lighthouse fails `heading-order`.
     */
    fun nestHeadings(document: Document, topLevel: Int): Document {
        val levels = mutableListOf<Int>()
        document.accept(object : AbstractVisitor() {
            override fun visit(heading: Heading) { levels += heading.level }
        })
        val shift = topLevel - (levels.minOrNull() ?: return document)
        document.accept(object : AbstractVisitor() {
            override fun visit(heading: Heading) {
                heading.level = (heading.level + shift).coerceIn(1, 6)
                visitChildren(heading)
            }
        })
        return document
    }

    /** Rewrites every link and image destination through [rewrite] (null = leave as is). */
    fun rewriteLinks(document: Node, rewrite: (destination: String, isImage: Boolean) -> String?) {
        document.accept(object : AbstractVisitor() {
            override fun visit(link: Link) {
                rewrite(link.destination, false)?.let { link.destination = it }
                visitChildren(link)
            }

            override fun visit(image: Image) {
                rewrite(image.destination, true)?.let { image.destination = it }
                visitChildren(image)
            }
        })
    }

    /** Every `<code>` span and code-block line in the document, for the docs linter. */
    fun codeSnippets(document: Node): List<String> {
        val result = mutableListOf<String>()
        document.accept(object : AbstractVisitor() {
            override fun visit(code: Code) { result += code.literal }
            override fun visit(fencedCodeBlock: FencedCodeBlock) {
                result += fencedCodeBlock.literal.lines()
            }
            override fun visit(indentedCodeBlock: IndentedCodeBlock) {
                result += indentedCodeBlock.literal.lines()
            }
        })
        return result
    }

    fun renderHtml(
        document: Node,
        escapeHtml: Boolean = false,
        idPrefix: String = "",
        imageSizes: (String) -> Pair<Int, Int>?,
    ): String {
        val headingIds = headings(document).map { idPrefix + it.id }.iterator()
        val renderer = HtmlRenderer.builder()
            .escapeHtml(escapeHtml)
            .extensions(extensions)
            .nodeRendererFactory { context -> HeadingRenderer(context, headingIds) }
            .nodeRendererFactory { context -> CodeBlockRenderer(context) }
            .nodeRendererFactory { context -> ImageRenderer(context, imageSizes) }
            .build()
        return renderer.render(document)
    }

    fun renderMarkdown(document: Node): String =
        MarkdownRenderer.builder().extensions(extensions).build().render(document)

    /** `<h2 id="x"><a class="anchor" href="#x">` — stable deep links for readers and citations. */
    private class HeadingRenderer(
        private val context: HtmlNodeRendererContext,
        private val ids: Iterator<String>,
    ) : NodeRenderer {
        override fun getNodeTypes(): Set<Class<out Node>> = setOf(Heading::class.java)

        override fun render(node: Node) {
            val heading = node as Heading
            val id = ids.next()
            val html = context.writer
            val tag = "h${heading.level}"
            html.line()
            html.tag(tag, mapOf("id" to id))
            var child = heading.firstChild
            while (child != null) {
                val next = child.next
                context.render(child)
                child = next
            }
            html.raw("<a class=\"anchor\" href=\"#$id\" aria-label=\"Link to this section\">#</a>")
            html.tag("/$tag")
            html.line()
        }
    }

    /** Code blocks get a copy button; `console` blocks get prompt/command/output styling. */
    private class CodeBlockRenderer(private val context: HtmlNodeRendererContext) : NodeRenderer {
        override fun getNodeTypes(): Set<Class<out Node>> =
            setOf(FencedCodeBlock::class.java, IndentedCodeBlock::class.java)

        override fun render(node: Node) {
            val (info, literal) = when (node) {
                is FencedCodeBlock -> node.info.orEmpty() to node.literal
                is IndentedCodeBlock -> "" to node.literal
                else -> return
            }
            val language = info.trim().substringBefore(' ')
            val executed = info.split(' ').contains(ExecBlocks.EXECUTED_MARKER)
            context.writer.raw(Html.codeBlock(literal, language, executed))
        }
    }

    /** Images get explicit width/height (no layout shift) and lazy loading. */
    private class ImageRenderer(
        private val context: HtmlNodeRendererContext,
        private val imageSizes: (String) -> Pair<Int, Int>?,
    ) : NodeRenderer {
        override fun getNodeTypes(): Set<Class<out Node>> = setOf(Image::class.java)

        override fun render(node: Node) {
            val image = node as Image
            val alt = plainText(image)
            val size = imageSizes(image.destination)
            val dimensions = size?.let { " width=\"${it.first}\" height=\"${it.second}\"" }.orEmpty()
            context.writer.raw(
                "<img src=\"${Html.attr(image.destination)}\" alt=\"${Html.attr(alt)}\"$dimensions " +
                    "loading=\"lazy\" decoding=\"async\">",
            )
        }
    }

    /** Raw HTML in docs is kept out of the site (GitHub-only markup such as `<details>` is fine). */
    fun htmlBlocks(document: Node): List<String> {
        val result = mutableListOf<String>()
        document.accept(object : AbstractVisitor() {
            override fun visit(htmlBlock: HtmlBlock) { result += htmlBlock.literal }
        })
        return result
    }
}

/**
 * GitHub's heading-anchor algorithm: lowercase, drop everything that is not a letter, digit,
 * space, hyphen or underscore, spaces to hyphens, and `-1`, `-2`, ... for repeats.
 */
class GithubSlugger {
    private val seen = mutableMapOf<String, Int>()

    fun slug(text: String): String {
        val base = text.lowercase()
            .filter { it.isLetterOrDigit() || it == ' ' || it == '-' || it == '_' }
            .replace(' ', '-')
        val count = seen[base]
        seen[base] = (count ?: 0) + 1
        return if (count == null) base else "$base-$count"
    }
}
