package dev.jdx.site

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldBeUnique
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Keeps the site honest about the repository's Markdown without building jdx: every doc is
 * either published or deliberately excluded, and every published doc renders with all of its
 * repository links resolving. (The full build — executed examples, CLI-derived reference,
 * link check — runs in `.github/workflows/pages.yml` on every pull request.)
 */
@Tag("integration")
class SiteManifestTest {
    private val repo = File(System.getProperty("jdx.repoRoot") ?: error("jdx.repoRoot not set"))

    @Test
    fun `every Markdown file is published or explicitly excluded`() {
        val published = SiteManifest.allDocSources.map { it.sourcePath }.toSet()
        val unplaced = repo.walkTopDown()
            .onEnter { dir -> dir.name !in setOf(".git", "build", ".gradle", ".kotlin", "node_modules") }
            .filter { it.isFile && it.extension == "md" }
            .map { it.relativeTo(repo).invariantSeparatorsPath }
            .filter { path -> path !in published && SiteManifest.excludedMarkdown.keys.none { it.matches(path) } }
            .toList()
        unplaced.shouldBeEmpty() // add each to SiteManifest (or, with a reason, to excludedMarkdown)
    }

    @Test
    fun `site paths are unique`() {
        SiteManifest.allDocSources.map { it.path }.shouldBeUnique()
    }

    @Test
    fun `every published doc renders and its repository links resolve`() {
        val site = SiteContext(
            baseUrl = "https://example.test/jdx/",
            repoUrl = "https://github.com/o/jdx",
            latestVersion = null,
            latestReleaseDate = null,
            commit = null,
            css = "",
            script = "",
        )
        val lenient = JdxRunner { _, _ -> JdxResult(0, "output", "") }
        val published = SiteManifest.allDocSources.associate { it.sourcePath to it.path }
        for (source in SiteManifest.allDocSources) {
            val document = Markdown.parse(File(repo, source.sourcePath).readText())
            // Promised exits are honoured by a runner that returns whatever the doc expects.
            val runner = JdxRunner { arguments, stdin ->
                val line = ExecBlocks.commandLines(document).firstOrNull { ShellWords.parseJdxCommand(it).arguments == arguments }
                val exit = line?.let { ShellWords.expectedExit(it) } ?: 0
                lenient.run(arguments, stdin).copy(exitCode = exit)
            }
            DocRenderer(site, repo, published, runner).render(source.sourcePath, source.description)
        }
    }

    @Test
    fun `SEO guides lead with the query and an FAQ`() {
        data class Expect(val path: String, val title: String, val headPrefix: String)
        val pages = listOf(
            Expect("docs/guide/find-class-in-jar.md", "Find class in jar", "Find class in jar"),
            Expect(
                "docs/guide/minecraft-mods.md",
                "Inspect a Minecraft mod jar from the terminal",
                "Inspect Minecraft mod jar",
            ),
            Expect(
                "docs/guide/jd-gui-alternative.md",
                "JD-GUI alternative: decompile a jar without a GUI",
                "JD-GUI alternative",
            ),
            Expect(
                "docs/guide/dependency-source.md",
                "View source code of a Maven dependency",
                "View source code of a Maven dependency",
            ),
        )
        for (page in pages) {
            val source = SiteManifest.allDocSources.first { it.sourcePath == page.path }
            val head = source.headTitle ?: error("${page.path} needs a headTitle")
            head.startsWith(page.headPrefix) shouldBe true
            (head.length <= 60) shouldBe true
            val document = Markdown.parse(File(repo, page.path).readText())
            Markdown.title(document) shouldBe page.title
            val description = Markdown.firstParagraph(document) ?: error("${page.path} needs an opening paragraph")
            description.contains("jdx") shouldBe true
            (description.length in 140..160) shouldBe true
            ("Quick answer" in Markdown.headings(document).map { it.text }) shouldBe true
            val faq = Markdown.faqSection(document)
            (faq.size in 4..6) shouldBe true
            faq.forEach { (question, answer) ->
                question.endsWith("?") shouldBe true
                answer.isNotEmpty() shouldBe true
            }
        }
    }
}
