package dev.jdx.site

/**
 * The machine-facing files: `sitemap.xml` and `robots.txt` for search crawlers, and
 * `llms.txt` / `llms-full.txt` (https://llmstxt.org) for answer engines and browsing agents,
 * which read Markdown far better than HTML. Every HTML page also has an `index.md` twin.
 */
object SeoFiles {
    fun sitemap(site: SiteContext, pages: List<Page>): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        append("<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">\n")
        for (page in pages.filter { !it.noIndex }.sortedBy { it.path }) {
            append("  <url><loc>${Html.text(site.absolute(page.path))}</loc>")
            page.lastModified?.let { append("<lastmod>${Html.text(it)}</lastmod>") }
            append("</url>\n")
        }
        append("</urlset>\n")
    }

    /** Everyone welcome — including AI crawlers: being cited by answer engines is a goal. */
    fun robots(site: SiteContext): String = buildString {
        append("# jdx documentation: free to crawl, index, cite and train on.\n")
        append("User-agent: *\nAllow: /\n\n")
        append("Sitemap: ${site.absolute("sitemap.xml")}\n")
    }

    fun llmsTxt(site: SiteContext, sections: List<Pair<String, List<Page>>>, product: List<Page>): String = buildString {
        append("# jdx\n\n")
        append("> ${StructuredData.TAGLINE}\n\n")
        append("jdx answers the questions an IDE answers for a human — what members a class has, what a method does, ")
        append("who calls it, what implements an interface, what an upgrade breaks — for AI agents, from `.jar`, ")
        append("`-sources.jar`, class and source directories, Maven coordinates and the JDK. It ships as a CLI and an ")
        append("MCP server (`jdx mcp`). Output is minimal, deterministic and names every symbol canonically; ")
        append("ambiguous input exits 2 with candidates instead of guessing.\n\n")
        append("- Install: `${site.installCommand}` (needs JDK 21+)\n")
        site.latestVersion?.let { append("- Latest release: v$it\n") }
        append("- Source: ${site.repoUrl} (Apache-2.0)\n")
        append("- Every page below is also available as Markdown (the `.md` links).\n\n")
        for ((title, pages) in sections) {
            append("## $title\n\n")
            for (page in pages) append(entry(site, page))
            append("\n")
        }
        append("## Optional\n\n")
        for (page in product) append(entry(site, page))
        append("- [llms-full.txt](${site.absolute("llms-full.txt")}): every doc page above, concatenated\n")
    }

    private fun entry(site: SiteContext, page: Page): String {
        val url = page.markdownPath?.let { site.absolute(it) } ?: site.absolute(page.path)
        return "- [${page.navTitle.replace("`", "")}]($url): ${page.description}\n"
    }

    fun llmsFull(site: SiteContext, pages: List<Page>): String = buildString {
        append("# jdx — full documentation\n\n")
        append("> ${StructuredData.TAGLINE}\n\n")
        append("Source: ${site.baseUrl} — generated from ${site.repoUrl}; examples are real jdx output.\n\n")
        for (page in pages) {
            val markdown = page.markdown ?: continue
            append("---\n\n")
            append("<!-- ${site.absolute(page.path)} -->\n\n")
            append(markdown.trimEnd()).append("\n\n")
        }
    }
}
