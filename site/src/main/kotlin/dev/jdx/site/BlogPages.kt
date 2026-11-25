package dev.jdx.site

import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * The blog: `docs/blog/<slug>.md` becomes `/blog/<slug>/`, plus `/blog/` and an Atom feed.
 *
 * Posts stay out of [SiteManifest] on purpose. Doc URLs are flat under `docs/` and must not
 * move; a post is not a guide, and its URL is `/blog/...` from the day it is published.
 *
 * Order is date descending, then slug ascending. The `date` in the front matter is the
 * public date: the visible `<time>`, `article:published_time`, BlogPosting `datePublished`
 * and `dateModified`, the sitemap `lastmod`, and the Atom `published`/`updated` (midnight UTC,
 * because the file records a day and not a time). Change that date when readers should see
 * the post as updated. Newest-first is a string compare, which matches chronological order
 * for zero-padded `YYYY-MM-DD`.
 */
object BlogPages {
    const val DIRECTORY = "docs/blog"
    const val INDEX_PATH = "blog/"
    const val FEED_PATH = "blog/feed.xml"

    const val INDEX_DESCRIPTION: String =
        "Short notes on using jdx, newest first: what a command returns, and the limits that " +
            "matter when you point it at a jar, a coordinate, or the JDK."

    const val INDEX_HEAD_TITLE: String = "jdx blog — notes on jars, agents, and limits"

    /** One post file, front matter already split off. [body] is the CommonMark document. */
    data class BlogSource(
        val sourcePath: String,
        val slug: String,
        val date: String,
        /** Meta description override; null means the first paragraph. */
        val description: String?,
        val body: String,
    ) {
        val path: String get() = "blog/$slug/"
    }

    /** A rendered post. [description] is the clipped meta description, same words as the page. */
    data class BlogPost(
        val source: BlogSource,
        val rendered: RenderedDoc,
    ) {
        val slug: String get() = source.slug
        val date: String get() = source.date
        val path: String get() = source.path
        val sourcePath: String get() = source.sourcePath
        val title: String get() = rendered.title
        val description: String get() = rendered.description
    }

    /** One Atom entry. Split from [BlogPost] so a feed can be tested without rendering Markdown. */
    data class FeedEntry(val title: String, val path: String, val date: String, val summary: String)

    /**
     * Every post in [repo], newest first. Fails the build on a missing directory, an empty
     * directory, a bad slug, or front matter that is not a real `date`.
     */
    fun readAll(repo: File): List<BlogSource> {
        val dir = File(repo, DIRECTORY)
        if (!dir.isDirectory) {
            throw SiteBuildException("$DIRECTORY is missing; blog posts are Markdown files there")
        }
        val files = dir.listFiles { file -> file.isFile && file.extension.equals("md", ignoreCase = true) }?.toList()
            ?: throw SiteBuildException("$DIRECTORY could not be read")
        if (files.isEmpty()) throw SiteBuildException("$DIRECTORY has no posts")
        val parsed = files.map { readOne(repo, it) }
        val duplicate = parsed.groupBy { it.slug }.filterValues { it.size > 1 }.keys.sorted()
        if (duplicate.isNotEmpty()) {
            throw SiteBuildException("duplicate blog slug: ${duplicate.joinToString()}")
        }
        return newestFirst(parsed, { it.date }, { it.slug })
    }

    fun render(docRenderer: DocRenderer, source: BlogSource): BlogPost {
        val override = source.description?.let(DocRenderer::clipDescription)
        return BlogPost(source, docRenderer.renderText(source.sourcePath, source.body, override))
    }

    fun orderedPosts(posts: List<BlogPost>): List<BlogPost> = newestFirst(posts, { it.date }, { it.slug })

    /** [items] newest first. Equal dates keep slug order, so the listing cannot shuffle. */
    fun <T> newestFirst(items: List<T>, date: (T) -> String, slug: (T) -> String): List<T> =
        items.sortedWith(compareByDescending(date).thenBy(slug))

    fun indexPage(site: SiteContext, posts: List<BlogPost>): Page {
        val ordered = orderedPosts(posts)
        if (ordered.isEmpty()) throw SiteBuildException("blog index needs at least one post")
        val html = buildString {
            append(indexHero())
            append("<section class=\"band\"><div class=\"wrap\">")
            append("<ul class=\"card-grid\">")
            for (post in ordered) {
                append("<li><a class=\"card\" href=\"${site.rootRelative(post.path)}\">")
                append("<time datetime=\"${Html.attr(post.date)}\">${Html.text(post.date)}</time>")
                append("<strong>${Html.inlineCode(post.title)}</strong>")
                append("<span>${Html.text(post.description)}</span></a></li>")
            }
            append("</ul>")
            append("<p class=\"callout\"><a href=\"${site.rootRelative(FEED_PATH)}\">Atom feed</a>")
            append(" of these posts.</p>")
            append("</div></section>\n")
        }
        val drafts = Page(
            path = INDEX_PATH,
            title = "Blog",
            headTitle = INDEX_HEAD_TITLE,
            description = INDEX_DESCRIPTION,
            bodyHtml = html,
            kind = PageKind.PRODUCT,
            markdown = indexMarkdown(site, ordered),
            lastModified = ordered.first().date,
            navTitle = "Blog",
        )
        return drafts.copy(
            jsonLd = listOf(
                StructuredData.blogIndex(
                    site,
                    INDEX_DESCRIPTION,
                    ordered.map { Triple(it.title, it.path, it.date) },
                ),
                StructuredData.breadcrumbs(site, listOf("Home" to "", "Blog" to INDEX_PATH)),
            ),
        )
    }

    fun postPages(site: SiteContext, posts: List<BlogPost>): List<Page> {
        val ordered = orderedPosts(posts)
        return ordered.mapIndexed { index, post ->
            postPage(site, post, previous = ordered.getOrNull(index - 1), next = ordered.getOrNull(index + 1))
        }
    }

    /**
     * Atom 1.0 feed. [entries] are re-sorted newest first. Summaries are escaped text;
     * the post page is the HTML, so the feed does not repeat a second rendering whose
     * links would be root-relative and useless in a reader.
     */
    fun atomFeed(site: SiteContext, subtitle: String, entries: List<FeedEntry>): String {
        val ordered = newestFirst(entries, { it.date }, { it.path })
        if (ordered.isEmpty()) throw SiteBuildException("Atom feed needs at least one post")
        val updated = ordered.first().date
        return buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            append("<feed xmlns=\"http://www.w3.org/2005/Atom\">\n")
            append("  <title>jdx blog</title>\n")
            append("  <subtitle>${Html.text(subtitle)}</subtitle>\n")
            append("  <id>${Html.text(site.absolute(INDEX_PATH))}</id>\n")
            append("  <updated>${atomTime(updated)}</updated>\n")
            append("  <link rel=\"self\" type=\"application/atom+xml\" href=\"${Html.attr(site.absolute(FEED_PATH))}\"/>\n")
            append("  <link rel=\"alternate\" type=\"text/html\" href=\"${Html.attr(site.absolute(INDEX_PATH))}\"/>\n")
            append("  <author><name>jdx</name><uri>${Html.text(site.baseUrl)}</uri></author>\n")
            append("  <generator>jdx site</generator>\n")
            for (entry in ordered) {
                val url = site.absolute(entry.path)
                append("  <entry>\n")
                append("    <title>${Html.text(entry.title)}</title>\n")
                append("    <id>${Html.text(url)}</id>\n")
                append("    <link rel=\"alternate\" type=\"text/html\" href=\"${Html.attr(url)}\"/>\n")
                append("    <published>${atomTime(entry.date)}</published>\n")
                append("    <updated>${atomTime(entry.date)}</updated>\n")
                append("    <summary>${Html.text(entry.summary)}</summary>\n")
                append("  </entry>\n")
            }
            append("</feed>\n")
        }
    }

    private fun postPage(site: SiteContext, post: BlogPost, previous: BlogPost?, next: BlogPost?): Page {
        val html = buildString {
            append(postHero(post))
            append("<section class=\"band\"><div class=\"wrap narrow prose\">")
            append(post.rendered.html)
            append(pager(site, previous, next))
            append(postMeta(site, post))
            append("</div></section>\n")
        }
        val draft = Page(
            path = post.path,
            title = post.title,
            description = post.description,
            bodyHtml = html,
            kind = PageKind.PRODUCT,
            markdown = postMarkdown(post),
            sourcePath = post.sourcePath,
            lastModified = post.date,
            published = post.date,
            navTitle = post.title,
        )
        return draft.copy(
            jsonLd = buildList {
                add(
                    StructuredData.blogPosting(
                        site,
                        draft.path,
                        draft.title,
                        draft.description,
                        post.date,
                        draft.ogImagePath,
                    ),
                )
                add(
                    StructuredData.breadcrumbs(
                        site,
                        listOf("Home" to "", "Blog" to INDEX_PATH, post.title to post.path),
                    ),
                )
                if (post.rendered.faq.isNotEmpty()) add(StructuredData.faqPage(post.rendered.faq))
            },
        )
    }

    private fun indexHero(): String =
        "<section class=\"page-hero\"><div class=\"wrap\"><p class=\"eyebrow\">Blog</p>" +
            "<h1>Blog</h1><p class=\"hero-lead\">${Html.text(INDEX_DESCRIPTION)}</p></div></section>\n"

    private fun postHero(post: BlogPost): String =
        "<section class=\"page-hero\"><div class=\"wrap\"><p class=\"eyebrow\">Blog</p>" +
            "<h1>${Html.inlineCode(post.title)}</h1>" +
            "<p class=\"hero-lead\"><time datetime=\"${Html.attr(post.date)}\">${Html.text(post.date)}</time></p>" +
            "</div></section>\n"

    private fun pager(site: SiteContext, previous: BlogPost?, next: BlogPost?): String {
        if (previous == null && next == null) return ""
        // Previous/next follow the newest-first list. Equal dates are a slug tie, not a clock time.
        return buildString {
            append("<nav class=\"pager\" aria-label=\"Previous and next\">")
            previous?.let {
                append("<a class=\"prev\" href=\"${site.rootRelative(it.path)}\" rel=\"prev\">")
                append("<span>Previous</span>${Html.inlineCode(it.title)}</a>")
            }
            next?.let {
                append("<a class=\"next\" href=\"${site.rootRelative(it.path)}\" rel=\"next\">")
                append("<span>Next</span>${Html.inlineCode(it.title)}</a>")
            }
            append("</nav>\n")
        }
    }

    private fun postMeta(site: SiteContext, post: BlogPost): String {
        val edit = "${site.repoUrl}/edit/main/${post.sourcePath}"
        val markdown = site.rootRelative(post.path + "index.md")
        return "<p class=\"page-meta\">" +
            "<a href=\"${Html.attr(markdown)}\">View as Markdown</a> · " +
            "<a href=\"${Html.attr(edit)}\">Edit this page on GitHub</a> · " +
            "<a href=\"${site.rootRelative(INDEX_PATH)}\">All posts</a> · " +
            "<a href=\"${site.rootRelative(FEED_PATH)}\">Atom feed</a></p>\n"
    }

    private fun indexMarkdown(site: SiteContext, posts: List<BlogPost>): String = buildString {
        append("# Blog\n\n")
        append(INDEX_DESCRIPTION).append("\n\n")
        for (post in posts) {
            append("- [${post.title}](${site.absolute(post.path)}) (${post.date}): ${post.description}\n")
        }
        append("\n[Atom feed](${site.absolute(FEED_PATH)})\n")
    }

    /** Puts the date under the H1 so the Markdown twin carries the same day as the HTML. */
    private fun postMarkdown(post: BlogPost): String {
        val markdown = post.rendered.markdown
        val split = markdown.indexOf("\n\n")
        val dated = "${post.date}\n\n"
        if (split < 0) return markdown.trimEnd() + "\n\n" + dated
        return markdown.substring(0, split + 2) + dated + markdown.substring(split + 2)
    }

    /** Midnight UTC: the file has a calendar day, and the feed must not invent a clock time. */
    private fun atomTime(day: String): String = "${day}T00:00:00Z"

    private fun readOne(repo: File, file: File): BlogSource {
        val sourcePath = file.relativeTo(repo).invariantSeparatorsPath
        val slug = file.nameWithoutExtension
        if (!SLUG.matches(slug)) {
            throw SiteBuildException("$sourcePath: file name must be a lowercase slug (a-z, 0-9, hyphens)")
        }
        val text = file.readText().removePrefix("\uFEFF")
        val match = FRONT_MATTER.matchEntire(text)
            ?: throw SiteBuildException("$sourcePath: blog post must start with --- front matter and a closing ---")
        val keys = parseFrontMatter(match.groupValues[1], sourcePath)
        val date = parseDate(
            keys["date"] ?: throw SiteBuildException("$sourcePath: front matter needs `date: YYYY-MM-DD`"),
            sourcePath,
        )
        return BlogSource(sourcePath, slug, date, keys["description"], match.groupValues[2])
    }

    private fun parseFrontMatter(header: String, sourcePath: String): Map<String, String> {
        val keys = linkedMapOf<String, String>()
        for (raw in header.lines()) {
            if (raw.isBlank()) continue
            if (raw.first().isWhitespace()) {
                throw SiteBuildException("$sourcePath: front matter continuation lines are not supported: $raw")
            }
            val colon = raw.indexOf(':')
            if (colon <= 0) {
                throw SiteBuildException("$sourcePath: front matter line is not `key: value`: $raw")
            }
            val key = raw.substring(0, colon).trim()
            val value = raw.substring(colon + 1).trim()
            if (key !in ALLOWED_KEYS) {
                throw SiteBuildException(
                    "$sourcePath: unknown front matter key `$key` (allowed: ${ALLOWED_KEYS.sorted().joinToString()})",
                )
            }
            if (key in keys) throw SiteBuildException("$sourcePath: duplicate front matter key `$key`")
            if (value.isEmpty()) throw SiteBuildException("$sourcePath: front matter `$key` has no value")
            keys[key] = value
        }
        return keys
    }

    private fun parseDate(value: String, sourcePath: String): String {
        if (!ISO_DATE.matches(value)) {
            throw SiteBuildException("$sourcePath: date must be YYYY-MM-DD, was `$value`")
        }
        val parsed = try {
            LocalDate.parse(value)
        } catch (failure: DateTimeParseException) {
            throw SiteBuildException("$sourcePath: date is not a real calendar day: $value")
        }
        if (parsed.toString() != value) {
            throw SiteBuildException("$sourcePath: date must be YYYY-MM-DD, was `$value`")
        }
        return value
    }

    private val FRONT_MATTER = Regex("""\A---\r?\n(.*?)\r?\n---\r?\n(.*)\z""", setOf(RegexOption.DOT_MATCHES_ALL))
    private val SLUG = Regex("[a-z0-9]+(?:-[a-z0-9]+)*")
    private val ISO_DATE = Regex("""\d{4}-\d{2}-\d{2}""")
    private val ALLOWED_KEYS = setOf("date", "description")
}
