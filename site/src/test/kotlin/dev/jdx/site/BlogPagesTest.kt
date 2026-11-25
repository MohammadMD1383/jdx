package dev.jdx.site

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.property.Arb
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The blog is content plus a sort. These tests render the real posts with a stub CLI
 * (no `console exec` blocks) and check the generator's own ordering, feed, and tags.
 * `:site:buildSite` is what lints the commands against the real binary.
 */
class BlogPagesTest {
    private val repo = File(System.getProperty("jdx.repoRoot") ?: error("jdx.repoRoot not set"))

    @Test
    fun `seed posts are newest first and say what jdx actually does`() {
        val posts = renderReal()
        posts.map { it.slug }.toSet() shouldBe setOf("reading-minecraft-mod-internals", "why-a-cli-for-agents")
        posts.map { it.slug } shouldBe BlogPages.newestFirst(posts, { it.date }, { it.slug }).map { it.slug }
        posts.forEach { post ->
            (post.description.length <= 160) shouldBe true
            post.description.endsWith("…") shouldBe false
            post.date shouldBe "2026-11-25"
        }

        val minecraft = posts.first { it.slug == "reading-minecraft-mod-internals" }
        minecraft.description shouldContain "does not remap"
        minecraft.description shouldContain "--jars"
        minecraft.description shouldContain "mapping namespace"
        minecraft.rendered.markdown shouldContain "does not remap"
        minecraft.rendered.markdown shouldContain "no `--jars`"
        minecraft.rendered.markdown shouldContain "one mapping namespace"
        minecraft.rendered.markdown shouldContain "never runs Gradle or Maven"
        minecraft.rendered.html shouldContain site().rootRelative("docs/minecraft-mods/")
        minecraft.rendered.html shouldContain site().rootRelative("docs/minecraft-version-diff/")
        minecraft.rendered.html shouldNotContain "../guide/"
        minecraft.rendered.markdown shouldNotContain "v1.8"

        val why = posts.first { it.slug == "why-a-cli-for-agents" }
        why.description shouldContain "one question per command"
        why.rendered.markdown shouldContain "does not guess"
        why.rendered.markdown shouldContain "does not remap"
        why.rendered.markdown shouldContain "static initialisers never run"
        why.rendered.markdown shouldNotContain "v1.8"
        why.rendered.html shouldContain site().rootRelative("docs/quickstart/")
    }

    @Test
    fun `post pages carry the article tags and the index lists them in order`() {
        val site = site()
        val posts = renderReal()
        val index = BlogPages.indexPage(site, posts)
        val pages = BlogPages.postPages(site, posts)
        val layout = Layout(site, NavTree(emptyList()))
        val orderedTitles = posts.map { it.title }

        index.description shouldBe BlogPages.INDEX_DESCRIPTION
        index.lastModified shouldBe posts.first().date
        val indexHtml = layout.render(index)
        indexHtml shouldContain "<h1>Blog</h1>"
        indexHtml shouldContain "card-grid"
        indexHtml shouldContain site.rootRelative(BlogPages.FEED_PATH)
        indexHtml shouldContain "aria-current=\"page\">Blog</a>"
        indexHtml shouldContain "<meta property=\"og:type\" content=\"website\">"
        indexHtml shouldContain "<link rel=\"canonical\" href=\"${site.absolute(index.path)}\">"
        val cards = indexHtml.substringAfter("card-grid")
        var cursor = 0
        for (title in orderedTitles) {
            val at = cards.indexOf(title)
            (at > cursor) shouldBe true
            cursor = at
        }
        val llms = SeoFiles.llmsTxt(site, listOf("Blog" to (listOf(index) + pages)), emptyList())
        llms shouldContain site.absolute(index.path)

        val sitemap = SeoFiles.sitemap(site, listOf(index) + pages)
        sitemap shouldContain "<loc>${Html.text(site.absolute(index.path))}</loc>"
        for (page in pages) {
            val html = layout.render(page)
            val canonical = site.absolute(page.path)
            html shouldContain "<link rel=\"canonical\" href=\"${Html.attr(canonical)}\">"
            html shouldContain "<meta name=\"description\" content=\"${Html.attr(page.description)}\">"
            html shouldContain "<meta property=\"og:title\" content=\"${Html.attr(page.title)}\">"
            html shouldContain "<meta property=\"og:description\" content=\"${Html.attr(page.description)}\">"
            html shouldContain "<meta property=\"og:url\" content=\"${Html.attr(canonical)}\">"
            html shouldContain "<meta property=\"og:type\" content=\"article\">"
            html shouldContain "<meta property=\"article:published_time\" content=\"${page.published}\">"
            html shouldContain "\"@type\":\"BlogPosting\""
            html shouldContain "\"datePublished\":\"${page.published}\""
            html shouldContain "\"dateModified\":\"${page.published}\""
            html shouldContain "\"headline\":\"${page.title}\""
            html shouldContain "<time datetime=\"${page.date()}\">${page.date()}</time>"
            html shouldContain "application/atom+xml"
            html shouldContain "aria-current=\"page\">Blog</a>"
            html shouldNotContain "noindex"
            sitemap shouldContain "<loc>${Html.text(canonical)}</loc>"
            page.published shouldBe page.lastModified
        }
    }

    @Test
    fun `atom feed is newest first and escapes text`() {
        val site = site()
        val posts = renderReal()
        val feed = BlogPages.atomFeed(
            site,
            BlogPages.INDEX_DESCRIPTION,
            posts.map { BlogPages.FeedEntry(it.title, it.path, it.date, it.description) },
        )
        parseXml(feed)
        feed shouldContain "<updated>2026-11-25T00:00:00Z</updated>"
        feed shouldContain site.absolute(BlogPages.FEED_PATH)
        var cursor = feed.indexOf("<entry>")
        for (post in posts) {
            val at = feed.indexOf("<title>${post.title}</title>", cursor)
            (at > cursor) shouldBe true
            cursor = at
        }

        val hostile = BlogPages.atomFeed(
            site,
            "subtitle <with> & markup",
            listOf(BlogPages.FeedEntry("A & B", "blog/a/", "2026-11-25", "a<b>&c")),
        )
        parseXml(hostile)
        hostile shouldContain "A &amp; B"
        hostile shouldContain "a&lt;b&gt;&amp;c"
        hostile shouldContain "subtitle &lt;with&gt; &amp; markup"
        hostile shouldNotContain "a<b>"
    }

    @Test
    fun `a newer date is listed before an older one, and the same day follows slug`(@TempDir dir: File) {
        writePost(dir, "b", "2024-06-01")
        writePost(dir, "a", "2024-06-01")
        writePost(dir, "old", "2020-01-02")
        BlogPages.readAll(dir).map { it.slug } shouldBe listOf("a", "b", "old")
    }

    @Test
    fun `crlf front matter still parses`(@TempDir dir: File) {
        val blog = File(dir, "docs/blog").apply { mkdirs() }
        File(blog, "crlf.md").writeText("---\r\ndate: 2026-01-02\r\n---\r\n# Title\r\n\r\nA paragraph of the post.\r\n")
        BlogPages.readAll(dir).single().date shouldBe "2026-01-02"
    }

    @Test
    fun `description override is the meta description and is clipped`(@TempDir dir: File) {
        val body = "---\ndate: 2026-03-04\ndescription: ${"word ".repeat(40).trim()}\n---\n# Title\n\nVisible opening sentence.\n"
        writeRaw(dir, "clipped.md", body)
        val source = BlogPages.readAll(dir).single()
        val post = BlogPages.render(renderer(dir, listOf(source)), source)
        (post.description.length <= 160) shouldBe true
        post.description.endsWith("…") shouldBe true
        post.rendered.html shouldContain "Visible opening sentence."
    }

    @Test
    fun `front matter that is not a post fails the build`(@TempDir dir: File) {
        fun fails(name: String, text: String, snippet: String) {
            writeRaw(dir, name, text)
            val error = assertThrows<SiteBuildException> { BlogPages.readAll(dir) }
            error.message.orEmpty() shouldContain snippet
            File(dir, "docs/blog").deleteRecursively()
        }
        fails("nope.md", "# Title\n\nNo front matter.\n", "front matter")
        fails("bad.md", "---\ndate: 2026-02-31\n---\n# Title\n\nBody of the post.\n", "real calendar day")
        fails("text.md", "---\ndate: 16 Nov 2026\n---\n# Title\n\nBody of the post.\n", "YYYY-MM-DD")
        fails("who.md", "---\ndate: 2026-01-02\nauthor: someone\n---\n# Title\n\nBody of the post.\n", "unknown front matter")
        fails("Hello.md", "---\ndate: 2026-01-02\n---\n# Title\n\nBody of the post.\n", "lowercase slug")
    }

    @Test
    fun `newest first is date descending then slug ascending for any strings`() = runBlocking<Unit> {
        val items = Arb.bind(Arb.string(0..12), Arb.string(0..12)) { date, slug -> date to slug }
        checkAll(200, Arb.list(items, 0..24)) { pairs ->
            val sorted = BlogPages.newestFirst(pairs, { it.first }, { it.second })
            for (index in 0 until sorted.lastIndex) {
                val current = sorted[index]
                val next = sorted[index + 1]
                val byDate = current.first.compareTo(next.first)
                val inOrder = byDate > 0 || (byDate == 0 && current.second <= next.second)
                inOrder shouldBe true
            }
            sorted.groupingBy { it }.eachCount() shouldBe pairs.groupingBy { it }.eachCount()
        }
    }

    @Test
    fun `flat blog files are excluded from the docs hierarchy and nested ones are not`() {
        val excluded = SiteManifest.excludedMarkdown.entries.first { it.key.pattern.contains("blog") }
        excluded.key.matches("docs/blog/why-a-cli-for-agents.md") shouldBe true
        excluded.key.matches("docs/blog/nested/post.md") shouldBe false
        excluded.value.isNotBlank() shouldBe true
    }

    private fun renderReal(): List<BlogPages.BlogPost> {
        val sources = BlogPages.readAll(repo)
        return sources.map { BlogPages.render(renderer(repo, sources), it) }
    }

    private fun renderer(root: File, sources: List<BlogPages.BlogSource>): DocRenderer {
        val published = SiteManifest.allDocSources.associate { it.sourcePath to it.path } +
            sources.associate { it.sourcePath to it.path }
        return DocRenderer(site(), root, published, JdxRunner { _, _ -> JdxResult(0, "", "") })
    }

    private fun site(): SiteContext = SiteContext(
        baseUrl = "https://example.test/jdx/",
        repoUrl = "https://github.com/MohammadMD1383/jdx",
        latestVersion = null,
        latestReleaseDate = null,
        commit = null,
        css = "/* test */",
        script = "",
    )

    private fun writePost(repo: File, slug: String, date: String) {
        writeRaw(repo, "$slug.md", "---\ndate: $date\n---\n# $slug\n\nA short opening paragraph for $slug.\n")
    }

    private fun writeRaw(repo: File, name: String, text: String) {
        val blog = File(repo, "docs/blog").apply { mkdirs() }
        File(blog, name).writeText(text)
    }

    /** The publication day is `lastModified` for a blog post; the test reads it back from the page. */
    private fun Page.date(): String = published ?: error("blog post has no publication date")

    private fun parseXml(xml: String) {
        val factory = DocumentBuilderFactory.newInstance()
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        factory.isNamespaceAware = true
        factory.newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
    }
}
