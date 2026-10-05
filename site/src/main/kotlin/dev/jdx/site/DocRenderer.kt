package dev.jdx.site

import org.commonmark.node.Document
import java.io.File
import java.net.URI

/** A Markdown source rendered for the site: HTML for people, Markdown for agents. */
data class RenderedDoc(
    val title: String,
    val description: String,
    val html: String,
    val markdown: String,
    val headings: List<HeadingInfo>,
    val codeSnippets: List<String>,
)

/**
 * Turns repository Markdown into site content. Owns link rewriting: a relative link to another
 * published doc becomes that page's URL (anchors preserved), a link to any other repository
 * file becomes its GitHub URL, an image is copied into `assets/`, and a link to a file that
 * does not exist fails the build — docs links are checked as strictly as code.
 */
class DocRenderer(
    private val site: SiteContext,
    private val repoRoot: File,
    /** Repository path -> site path of every published doc. */
    private val publishedDocs: Map<String, String>,
    private val runner: JdxRunner,
) {
    /** Site path -> repository file, for every asset a doc referenced. */
    val assets: MutableMap<String, File> = sortedMapOf()

    fun render(sourcePath: String, descriptionOverride: String? = null): RenderedDoc {
        val file = File(repoRoot, sourcePath)
        if (!file.isFile) throw SiteBuildException("site manifest lists $sourcePath, which does not exist")
        val document = Markdown.parse(file.readText())
        return renderDocument(document, sourcePath, descriptionOverride)
    }

    fun renderDocument(document: Document, sourcePath: String, descriptionOverride: String? = null): RenderedDoc {
        val title = Markdown.title(document) ?: throw SiteBuildException("$sourcePath has no `# Title` heading")
        Markdown.removeTitle(document)
        val snippets = Markdown.codeSnippets(document)
        ExecBlocks.execute(document, runner, sourcePath)
        Markdown.rewriteLinks(document) { destination, isImage -> rewrite(sourcePath, destination, isImage) }
        val description = descriptionOverride ?: Markdown.firstParagraph(document)?.let(::clipDescription)
            ?: throw SiteBuildException("$sourcePath needs an opening paragraph (it becomes the meta description)")
        val headings = Markdown.headings(document)
        val html = toRootRelative(Markdown.renderHtml(document) { destination -> imageSize(destination) })
        val markdown = "# $title\n\n" + Markdown.renderMarkdown(document).trimEnd() + "\n"
        return RenderedDoc(title, description, html, markdown, headings, snippets)
    }

    /** Site-absolute URLs are kept in Markdown twins (agents fetch them out of context) but made root-relative in HTML. */
    fun toRootRelative(html: String): String =
        html.replace("href=\"${site.baseUrl}", "href=\"${site.basePath}")
            .replace("src=\"${site.baseUrl}", "src=\"${site.basePath}")

    private fun rewrite(fromSource: String, destination: String, isImage: Boolean): String? {
        if (destination.isEmpty() || destination.startsWith("#")) return null
        if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:").containsMatchIn(destination)) return null // has a scheme
        val path = destination.substringBefore('#')
        val fragment = destination.substringAfter('#', "").let { if (it.isEmpty()) "" else "#$it" }
        val baseDir = fromSource.substringBeforeLast('/', "")
        val repoPath = URI(null, null, if (baseDir.isEmpty()) path else "$baseDir/$path", null).normalize().path
            .removePrefix("./").trimEnd('/')
        if (repoPath.startsWith("../")) throw SiteBuildException("$fromSource links outside the repository: $destination")
        val target = File(repoRoot, repoPath)
        if (!target.exists()) throw SiteBuildException("$fromSource links to $destination, which does not exist in the repository")
        if (isImage || target.extension.lowercase() in ASSET_EXTENSIONS) {
            val assetPath = "assets/${target.name}"
            assets[assetPath] = target
            return site.absolute(assetPath)
        }
        publishedDocs[repoPath]?.let { return site.absolute(it) + fragment }
        val kind = if (target.isDirectory) "tree" else "blob"
        return "${site.repoUrl}/$kind/main/$repoPath$fragment"
    }

    private fun imageSize(destination: String): Pair<Int, Int>? {
        val assetPath = destination.removePrefix(site.baseUrl).removePrefix(site.basePath)
        val file = assets[assetPath] ?: return null
        return ImageSize.of(file)
    }

    companion object {
        private val ASSET_EXTENSIONS = setOf("gif", "png", "jpg", "jpeg", "svg", "webp", "mp4", "webm")

        /** Meta descriptions over ~160 characters get cut by search engines mid-word; cut cleanly instead. */
        fun clipDescription(text: String): String {
            val clean = text.replace(Regex("\\s+"), " ").trim()
            if (clean.length <= 160) return clean
            val cut = clean.take(157).substringBeforeLast(' ')
            return "$cut…"
        }
    }
}

/** Reads pixel dimensions from image headers so every `<img>` has width/height (no layout shift). */
object ImageSize {
    fun of(file: File): Pair<Int, Int>? {
        val header = file.inputStream().use { input -> ByteArray(32).also { input.read(it) } }
        fun u8(index: Int) = header[index].toInt() and 0xff
        return when {
            header.size >= 10 && String(header, 0, 3) == "GIF" -> (u8(6) or (u8(7) shl 8)) to (u8(8) or (u8(9) shl 8))
            header.size >= 24 && u8(1) == 'P'.code && u8(2) == 'N'.code && u8(3) == 'G'.code ->
                ((u8(16) shl 24) or (u8(17) shl 16) or (u8(18) shl 8) or u8(19)) to
                    ((u8(20) shl 24) or (u8(21) shl 16) or (u8(22) shl 8) or u8(23))
            else -> null
        }
    }
}
