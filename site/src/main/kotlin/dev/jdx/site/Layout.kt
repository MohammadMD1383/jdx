package dev.jdx.site

import java.net.URI

/** Site-wide facts every page needs. Built once by [SiteMain], read-only afterwards. */
data class SiteContext(
    /** Public URL of the site root, always ending in `/`. */
    val baseUrl: String,
    val repoUrl: String,
    /** Latest published release, e.g. `1.5.0` (no `v`); null when none is known. */
    val latestVersion: String?,
    val latestReleaseDate: String?,
    /** Short commit the site was built from (footer provenance); null outside git. */
    val commit: String?,
    val googleVerification: String? = null,
    val bingVerification: String? = null,
    val css: String,
    val script: String,
) {
    init {
        require(baseUrl.endsWith("/")) { "base URL must end with '/': $baseUrl" }
    }

    /** Path part of [baseUrl] (`/jdx/` on github.io, `/` on a custom domain). */
    val basePath: String = URI(baseUrl).path.ifEmpty { "/" }

    fun absolute(path: String): String = baseUrl + path
    fun rootRelative(path: String): String = basePath + path

    val installCommand: String =
        "curl -fsSL https://raw.githubusercontent.com/${repoSlug()}/main/install-release.sh | bash"

    fun repoSlug(): String = URI(repoUrl).path.trim('/')
}

enum class PageKind { LANDING, PRODUCT, DOC }

data class Crumb(val title: String, val path: String)

/** One output page. [path] is relative to the site root: `""`, `docs/quickstart/`, `404.html`. */
data class Page(
    val path: String,
    val title: String,
    val description: String,
    val bodyHtml: String,
    val kind: PageKind,
    /** The `<title>`; defaults to "<title> · jdx". */
    val headTitle: String = "$title · jdx",
    /** Short line under the H1. */
    val lead: String? = null,
    /** Agent-readable twin published next to the page as `index.md`. */
    val markdown: String? = null,
    /** Repository file the page is generated from (edit link); null for generated pages. */
    val sourcePath: String? = null,
    val lastModified: String? = null,
    val toc: List<HeadingInfo> = emptyList(),
    val breadcrumbs: List<Crumb> = emptyList(),
    val jsonLd: List<Any> = emptyList(),
    val noIndex: Boolean = false,
    /** Nav label when it differs from the title. */
    val navTitle: String = title,
) {
    val isHtmlDirectory: Boolean get() = path.isEmpty() || path.endsWith("/")
    val outputFile: String get() = if (isHtmlDirectory) path + "index.html" else path
    val markdownPath: String? get() = if (markdown != null && isHtmlDirectory) path + "index.md" else null
    val ogImagePath: String get() = "og/" + (if (path.isEmpty()) "home" else path.trim('/').replace('/', '-').removeSuffix(".html")) + ".png"
}

/** The documentation hierarchy as the sidebar shows it. */
data class NavSection(val title: String, val items: List<NavItem>)

data class NavItem(val title: String, val path: String, val children: List<NavItem> = emptyList())

data class NavTree(val sections: List<NavSection>) {
    /** Depth-first page order: what prev/next walks. */
    val flat: List<NavItem> = sections.flatMap { section -> section.items.flatMap { listOf(it) + it.children } }

    fun sectionOf(path: String): NavSection? =
        sections.firstOrNull { section -> section.items.any { it.path == path || it.children.any { child -> child.path == path } } }
}

/** Renders full HTML documents. All markup lives here so pages stay content-only. */
class Layout(private val site: SiteContext, private val nav: NavTree) {

    fun render(page: Page): String = buildString {
        append("<!doctype html>\n<html lang=\"en\">\n<head>\n")
        append(head(page))
        append("</head>\n<body class=\"kind-${page.kind.name.lowercase()}\">\n")
        append("<a class=\"skip\" href=\"#main\">Skip to content</a>\n")
        append(header(page))
        when (page.kind) {
            PageKind.DOC -> append(docMain(page))
            PageKind.LANDING, PageKind.PRODUCT -> append(productMain(page))
        }
        append(footer())
        append("<script>").append(site.script).append("</script>\n")
        append("</body>\n</html>\n")
    }

    private fun head(page: Page): String = buildString {
        val canonical = site.absolute(page.path)
        append("<meta charset=\"utf-8\">\n")
        append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
        append("<title>${Html.text(page.headTitle)}</title>\n")
        append("<meta name=\"description\" content=\"${Html.attr(page.description)}\">\n")
        if (page.noIndex) {
            append("<meta name=\"robots\" content=\"noindex\">\n")
        } else {
            append("<link rel=\"canonical\" href=\"${Html.attr(canonical)}\">\n")
        }
        page.markdownPath?.let {
            append("<link rel=\"alternate\" type=\"text/markdown\" href=\"${Html.attr(site.absolute(it))}\" title=\"Markdown version\">\n")
        }
        append("<link rel=\"alternate\" type=\"text/plain\" href=\"${site.absolute("llms.txt")}\" title=\"llms.txt\">\n")
        append("<link rel=\"icon\" href=\"${site.rootRelative("favicon.svg")}\" type=\"image/svg+xml\">\n")
        append("<link rel=\"apple-touch-icon\" href=\"${site.rootRelative("apple-touch-icon.png")}\">\n")
        append("<link rel=\"ai-catalog\" href=\"${site.rootRelative("ai-catalog.json")}\" type=\"application/json\">\n")
        append("<link rel=\"sitemap\" type=\"application/xml\" href=\"${site.rootRelative("sitemap.xml")}\">\n")
        append("<meta name=\"theme-color\" content=\"#f7f6f2\" media=\"(prefers-color-scheme: light)\">\n")
        append("<meta name=\"theme-color\" content=\"#0c0e13\" media=\"(prefers-color-scheme: dark)\">\n")
        append("<meta name=\"color-scheme\" content=\"light dark\">\n")
        val ogType = if (page.kind == PageKind.DOC) "article" else "website"
        val image = site.absolute(page.ogImagePath)
        append("<meta property=\"og:type\" content=\"$ogType\">\n")
        append("<meta property=\"og:site_name\" content=\"jdx\">\n")
        append("<meta property=\"og:title\" content=\"${Html.attr(page.title)}\">\n")
        append("<meta property=\"og:description\" content=\"${Html.attr(page.description)}\">\n")
        append("<meta property=\"og:url\" content=\"${Html.attr(canonical)}\">\n")
        append("<meta property=\"og:image\" content=\"${Html.attr(image)}\">\n")
        append("<meta property=\"og:image:width\" content=\"${OgImage.WIDTH}\">\n")
        append("<meta property=\"og:image:height\" content=\"${OgImage.HEIGHT}\">\n")
        append("<meta property=\"og:image:alt\" content=\"${Html.attr(page.title)} — jdx\">\n")
        append("<meta property=\"og:image:type\" content=\"image/png\">\n")
        append("<meta property=\"og:locale\" content=\"en_US\">\n")
        append("<meta name=\"twitter:card\" content=\"summary_large_image\">\n")
        append("<meta name=\"twitter:title\" content=\"${Html.attr(page.title)}\">\n")
        append("<meta name=\"twitter:description\" content=\"${Html.attr(page.description)}\">\n")
        append("<meta name=\"twitter:image\" content=\"${Html.attr(image)}\">\n")
        append("<meta name=\"twitter:image:alt\" content=\"${Html.attr(page.title)} — jdx\">\n")
        page.lastModified?.let { append("<meta property=\"article:modified_time\" content=\"${Html.attr(it)}\">\n") }
        site.googleVerification?.let { append("<meta name=\"google-site-verification\" content=\"${Html.attr(it)}\">\n") }
        site.bingVerification?.let { append("<meta name=\"msvalidate.01\" content=\"${Html.attr(it)}\">\n") }
        for (data in page.jsonLd) {
            append("<script type=\"application/ld+json\">").append(JsonWriter.encode(data)).append("</script>\n")
        }
        append("<style>").append(site.css).append("</style>\n")
    }

    private fun header(page: Page): String {
        fun link(label: String, path: String, active: Boolean): String {
            val current = if (active) " aria-current=\"page\"" else ""
            return "<a href=\"${site.rootRelative(path)}\"$current>$label</a>"
        }
        val inDocs = page.path.startsWith("docs/")
        val version = site.latestVersion?.let {
            "<a class=\"version\" href=\"${site.rootRelative("changelog/")}\" title=\"Latest release\">v${Html.text(it)}</a>"
        }.orEmpty()
        return """
            |<header class="site-header">
            |<div class="wrap header-inner">
            |<a class="brand" href="${site.rootRelative("")}" aria-label="jdx home">${Brand.MARK}<span>jdx</span></a>
            |$version
            |<nav class="top-nav" aria-label="Main">
            |${link("Docs", "docs/", inDocs && !page.path.startsWith("docs/reference/"))}
            |${link("Reference", "docs/reference/", page.path.startsWith("docs/reference/"))}
            |${link("Integrations", "integrations/", page.path.startsWith("integrations/"))}
            |${link("FAQ", "faq/", page.path == "faq/").replace("<a ", "<a class=\"secondary\" ")}
            |${link("Changelog", "changelog/", page.path == "changelog/").replace("<a ", "<a class=\"secondary\" ")}
            |<a class="gh" href="${site.repoUrl}" aria-label="jdx on GitHub">${Brand.GITHUB}<span>GitHub</span></a>
            |</nav>
            |</div>
            |</header>
            |""".trimMargin()
    }

    private fun productMain(page: Page): String =
        "<main id=\"main\">\n${page.bodyHtml}\n</main>\n"

    private fun docMain(page: Page): String = buildString {
        val sidebar = sidebar(page.path)
        append("<div class=\"wrap docs-grid\">\n")
        append("<details class=\"docs-menu\"><summary>Documentation menu</summary>$sidebar</details>\n")
        append("<aside class=\"docs-sidebar\" aria-label=\"Documentation\">$sidebar</aside>\n")
        append("<main id=\"main\" class=\"docs-main\">\n<article class=\"prose\">\n")
        if (page.breadcrumbs.isNotEmpty()) {
            append("<nav class=\"crumbs\" aria-label=\"Breadcrumb\"><ol>")
            for (crumb in page.breadcrumbs) {
                append("<li><a href=\"${site.rootRelative(crumb.path)}\">${Html.text(crumb.title)}</a></li>")
            }
            append("</ol></nav>\n")
        }
        append("<h1>${Html.inlineCode(page.title)}</h1>\n")
        page.lead?.let { append("<p class=\"lead\">${Html.inlineCode(it)}</p>\n") }
        append(page.bodyHtml)
        append("\n</article>\n")
        append(pager(page.path))
        append(pageMeta(page))
        append("</main>\n")
        val tocItems = page.toc.filter { it.level in 2..3 }
        if (tocItems.size >= 2) {
            append("<aside class=\"docs-toc\" aria-label=\"On this page\"><p class=\"toc-title\">On this page</p><ul>")
            for (item in tocItems) {
                append("<li class=\"toc-${item.level}\"><a href=\"#${Html.attr(item.id)}\">${Html.text(item.text)}</a></li>")
            }
            append("</ul></aside>\n")
        }
        append("</div>\n")
    }

    private fun sidebar(currentPath: String): String = buildString {
        append("<nav class=\"side-nav\">")
        for (section in nav.sections) {
            append("<p class=\"side-title\">${Html.text(section.title)}</p><ul>")
            for (item in section.items) {
                append(sideLink(item, currentPath))
                if (item.children.isNotEmpty()) {
                    // Long generated lists (every command) collapse unless the reader is inside them.
                    val open = if (item.children.any { it.path == currentPath } || item.path == currentPath) " open" else ""
                    append("<li><details class=\"side-group\"$open><summary>All commands</summary><ul class=\"side-children\">")
                    item.children.forEach { append(sideLink(it, currentPath)) }
                    append("</ul></details></li>")
                }
            }
            append("</ul>")
        }
        append("</nav>")
    }

    private fun sideLink(item: NavItem, currentPath: String): String {
        val current = if (item.path == currentPath) " aria-current=\"page\"" else ""
        return "<li><a href=\"${site.rootRelative(item.path)}\"$current>${Html.inlineCode(item.title)}</a></li>"
    }

    private fun pager(path: String): String {
        val index = nav.flat.indexOfFirst { it.path == path }
        if (index < 0) return ""
        val previous = nav.flat.getOrNull(index - 1)
        val next = nav.flat.getOrNull(index + 1)
        return buildString {
            append("<nav class=\"pager\" aria-label=\"Previous and next\">")
            previous?.let {
                append("<a class=\"prev\" href=\"${site.rootRelative(it.path)}\" rel=\"prev\"><span>Previous</span>${Html.inlineCode(it.title)}</a>")
            }
            next?.let {
                append("<a class=\"next\" href=\"${site.rootRelative(it.path)}\" rel=\"next\"><span>Next</span>${Html.inlineCode(it.title)}</a>")
            }
            append("</nav>\n")
        }
    }

    private fun pageMeta(page: Page): String = buildString {
        append("<p class=\"page-meta\">")
        val parts = mutableListOf<String>()
        page.sourcePath?.let {
            parts += "<a href=\"${site.repoUrl}/edit/main/${Html.attr(it)}\">Edit this page on GitHub</a>"
        }
        page.markdownPath?.let {
            parts += "<a href=\"${site.rootRelative(it)}\">View as Markdown</a>"
        }
        page.lastModified?.let { parts += "Updated <time datetime=\"${Html.attr(it)}\">${Html.text(it.take(10))}</time>" }
        append(parts.joinToString(" · "))
        append("</p>\n")
    }

    private fun footer(): String {
        val commit = site.commit?.let {
            " Built from <a href=\"${site.repoUrl}/commit/${Html.attr(it)}\"><code>${Html.text(it)}</code></a>."
        }.orEmpty()
        fun link(label: String, path: String) = "<li><a href=\"${site.rootRelative(path)}\">$label</a></li>"
        fun external(label: String, url: String) = "<li><a href=\"$url\">$label</a></li>"
        return """
            |<footer class="site-footer">
            |<div class="wrap footer-grid">
            |<div class="footer-brand"><a class="brand" href="${site.rootRelative("")}">${Brand.MARK}<span>jdx</span></a>
            |<p>An IDE for AI agents, as a command line tool. Apache-2.0.</p></div>
            |<div><p class="footer-title">Product</p><ul>
            |${link("Overview", "")}${link("Integrations", "integrations/")}${link("FAQ", "faq/")}${link("Changelog", "changelog/")}
            |</ul></div>
            |<div><p class="footer-title">Docs</p><ul>
            |${link("Getting started", "docs/introduction/")}${link("Guides", "docs/#guides")}${link("Reference", "docs/reference/")}${link("Advanced", "docs/#advanced")}
            |</ul></div>
            |<div><p class="footer-title">Project</p><ul>
            |${external("GitHub", site.repoUrl)}${external("Releases", "${site.repoUrl}/releases")}${external("Issues", "${site.repoUrl}/issues")}${external("License", "${site.repoUrl}/blob/main/LICENSE")}
            |</ul></div>
            |<div><p class="footer-title">For agents</p><ul>
            |${link("llms.txt", "llms.txt")}${link("llms-full.txt", "llms-full.txt")}${link("Sitemap", "sitemap.xml")}${link("Agent cheat sheet", "docs/reference/cheat-sheet/")}
            |</ul></div>
            |</div>
            |<p class="wrap footer-note">Every example on this site is real output from jdx, regenerated on every change.$commit</p>
            |</footer>
            |""".trimMargin()
    }
}

/** Inline SVG marks (no extra requests, inherit currentColor). */
object Brand {
    const val MARK: String =
        "<svg class=\"mark\" viewBox=\"0 0 32 32\" width=\"28\" height=\"28\" aria-hidden=\"true\">" +
            "<rect width=\"32\" height=\"32\" rx=\"7\" fill=\"var(--accent)\"/>" +
            "<path d=\"M9 11l5 5-5 5\" fill=\"none\" stroke=\"var(--accent-ink)\" stroke-width=\"2.6\" stroke-linecap=\"round\" stroke-linejoin=\"round\"/>" +
            "<path d=\"M16 22h7\" stroke=\"var(--accent-ink)\" stroke-width=\"2.6\" stroke-linecap=\"round\"/></svg>"

    const val GITHUB: String =
        "<svg viewBox=\"0 0 16 16\" width=\"18\" height=\"18\" aria-hidden=\"true\"><path fill=\"currentColor\" d=\"M8 0C3.58 0 0 3.58 0 8c0 3.54 " +
            "2.29 6.53 5.47 7.59.4.07.55-.17.55-.38 0-.19-.01-.82-.01-1.49-2.01.37-2.53-.49-2.69-.94-.09-.23-.48-.94-.82-1.13-.28-.15-.68-.52-.01-.53.63-.01 " +
            "1.08.58 1.23.82.72 1.21 1.87.87 2.33.66.07-.52.28-.87.51-1.07-1.78-.2-3.64-.89-3.64-3.95 0-.87.31-1.59.82-2.15-.08-.2-.36-1.02.08-2.12 0 0 " +
            ".67-.21 2.2.82.64-.18 1.32-.27 2-.27.68 0 1.36.09 2 .27 1.53-1.04 2.2-.82 2.2-.82.44 1.1.16 1.92.08 2.12.51.56.82 1.27.82 2.15 0 3.07-1.87 " +
            "3.75-3.65 3.95.29.25.54.73.54 1.48 0 1.07-.01 1.93-.01 2.2 0 .21.15.46.55.38A8.013 8.013 0 0016 8c0-4.42-3.58-8-8-8z\"/></svg>"
}
