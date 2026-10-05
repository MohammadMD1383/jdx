package dev.jdx.site

import java.io.File
import java.net.URI
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Inputs of one site build (see `site/build.gradle.kts` for where each comes from). */
data class SiteOptions(
    val repoRoot: File,
    val outputDir: File,
    val baseUrl: String,
    val repoUrl: String = "https://github.com/MohammadMD1383/jdx",
    val version: String? = null,
    val releasesJson: File? = null,
    val googleVerification: String? = null,
    val bingVerification: String? = null,
    /** Draw per-page social cards (off in unit tests: Java2D is slow and font-dependent). */
    val ogImages: Boolean = true,
)

/** Everything the build wrote, for the summary line and for tests. */
data class SiteOutput(val files: Map<String, ByteArray>, val pages: List<Page>)

/**
 * Builds the whole site in memory, validates it (doc lint + link check), then writes it.
 * Validation failures throw [SiteBuildException] listing every problem at once — fixing docs
 * one CI round-trip per typo is exactly the friction that makes people stop fixing docs.
 */
class SiteBuilder(private val options: SiteOptions, private val runner: JdxRunner) {

    fun build(): SiteOutput {
        val repo = options.repoRoot
        val project = ProjectInfo(repo)
        val prefetch = runner.run(ReferencePages.PREFETCH, null)
        if (prefetch.exitCode != 0) {
            throw SiteBuildException("could not fetch the example fixture (${ReferencePages.GSON}): ${prefetch.stdout}${prefetch.stderr}")
        }
        val facts = CliFacts.load(runner)
        val releases = options.releasesJson?.takeIf { it.isFile }?.let { ProjectInfo.parseReleases(it.readText()) }.orEmpty()
        val latestTag = releases.firstOrNull()?.tag ?: project.latestTag()
        val site = SiteContext(
            baseUrl = options.baseUrl,
            repoUrl = options.repoUrl,
            latestVersion = options.version?.removePrefix("v") ?: latestTag?.removePrefix("v"),
            latestReleaseDate = releases.firstOrNull()?.date ?: latestTag?.let { project.tagDate(it) },
            commit = project.headCommit(),
            googleVerification = options.googleVerification,
            bingVerification = options.bingVerification,
            css = resource("site.css"),
            script = resource("site.js"),
        )

        val publishedDocs = SiteManifest.allDocSources.associate { it.sourcePath to it.path }
        val docRenderer = DocRenderer(site, repo, publishedDocs, runner)
        val reference = ReferencePages(site, facts, runner, project.lastModified("cli/src/main/kotlin/dev/jdx/cli"))
        val product = ProductPages(site, repo, facts, docRenderer, reference, project, releases)

        // Run every example up front, in parallel; rendering then hits the runner's memo.
        val docTexts = SiteManifest.allDocSources.associate { it.sourcePath to File(repo, it.sourcePath).readText() }
        val docLines = docTexts.flatMap { (source, text) -> ExecBlocks.commandLines(Markdown.parse(text)).map { source to it } }
        val generatedLines = (reference.exampleCommandLines() + product.heroCommands).map { "site generator" to it }
        prerun((docLines + generatedLines).map { it.second })
        val failedExamples = (docLines + generatedLines).mapNotNull { (source, line) ->
            val command = ShellWords.parseJdxCommand(line)
            val expected = ShellWords.expectedExit(line) ?: 0
            val result = runner.run(command.arguments, command.stdin)
            if (result.exitCode == expected) null else
                "$source: `$line` exited ${result.exitCode}, expected $expected\n" +
                    (result.stdout + result.stderr).trimEnd().lines().take(6).joinToString("\n") { "      $it" }
        }
        if (failedExamples.isNotEmpty()) {
            throw SiteBuildException(
                "examples no longer work (${failedExamples.size}) — published examples are real runs:\n" +
                    failedExamples.joinToString("\n") { "  - $it" },
            )
        }

        val lint = DocLint(facts)
        val problems = mutableListOf<String>()
        val sectionPages = linkedMapOf<SiteManifest.Section, List<Page>>()
        for (section in SiteManifest.sections) {
            sectionPages[section] = section.docs.map { source ->
                val rendered = docRenderer.render(source.sourcePath, source.description)
                if (source.lintCommands) problems += lint.check(rendered.codeSnippets, source.sourcePath)
                docPage(site, section, source, rendered, project)
            }
        }
        problems += lint.check(Markdown.codeSnippets(Markdown.parse(File(repo, "README.md").readText())), "README.md")
        problems += lint.check(Markdown.codeSnippets(Markdown.parse(docTexts.getValue(SiteManifest.faq.sourcePath))), SiteManifest.faq.sourcePath)

        val commandPages = reference.commandPages()
        val extraReference = listOf(reference.cheatSheetPage(), reference.mcpToolsPage())
        val docsIndex = product.docsIndex(sectionPages, commandPages, extraReference)
        val faqQuestions = product.faqQuestions()
        val landing = product.landing(runner, faqQuestions)
        val integrations = listOf(product.integrationsHub()) + facts.setupAgents.map { product.integrationPage(it) }
        val productPages = listOf(landing) + integrations + listOf(product.faqPage(), product.changelogPage())

        if (problems.isNotEmpty()) {
            throw SiteBuildException(
                "docs disagree with the CLI (${problems.size}):\n" + problems.joinToString("\n") { "  - $it" } +
                    "\nFix the docs (or the CLI); see site/AGENTS.md.",
            )
        }

        val nav = navTree(sectionPages, docsIndex, commandPages, extraReference)
        val docPages = listOf(docsIndex) + sectionPages.values.flatten() + extraReference + commandPages
        val allPages = productPages + docPages + product.notFoundPage()
        val layout = Layout(site, nav)

        val files = sortedMapOf<String, ByteArray>()
        val htmlFiles = sortedMapOf<String, String?>()
        for (page in allPages) {
            val html = layout.render(page)
            files[page.outputFile] = html.toByteArray()
            htmlFiles[page.outputFile] = html
            page.markdownPath?.let {
                files[it] = page.markdown!!.toByteArray()
                htmlFiles[it] = null
            }
        }
        val llmsSections = SiteManifest.sections.map { section ->
            section.title to (sectionPages[section].orEmpty() + if (section == SiteManifest.reference) extraReference + commandPages else emptyList())
        }
        val text = sortedMapOf(
            "robots.txt" to SeoFiles.robots(site),
            "sitemap.xml" to SeoFiles.sitemap(site, allPages),
            "llms.txt" to SeoFiles.llmsTxt(site, llmsSections, productPages),
            "llms-full.txt" to SeoFiles.llmsFull(site, listOf(landing) + docPages + productPages.drop(1)),
            "favicon.svg" to resource("favicon.svg"),
            "ai-catalog.json" to AiCatalog.render(site, facts, reference.cheatSheetPath + "index.md"),
        )
        customDomain(options.baseUrl)?.let { text["CNAME"] = "$it\n" }
        text.forEach { (path, content) -> files[path] = content.toByteArray(); htmlFiles[path] = null }
        for ((path, source) in docRenderer.assets) {
            files[path] = source.readBytes()
            htmlFiles[path] = null
        }
        htmlFiles["apple-touch-icon.png"] = null
        if (options.ogImages) files["apple-touch-icon.png"] = OgImage.icon(180)
        for (page in allPages) {
            htmlFiles[page.ogImagePath] = null
            if (options.ogImages) files[page.ogImagePath] = ogImage(site, page)
        }
        page404Check(htmlFiles)

        // Published output must never carry a machine path (AGENTS.md §2.5): a leaked path means
        // an example ran against something on the build machine instead of the sandbox.
        val machinePaths = listOf(repo.absolutePath, System.getProperty("user.home"), File(System.getProperty("java.io.tmpdir"), "jdx-site-cwd").path)
            .filter { it.length > 1 }
        val leaks = htmlFiles.filterValues { it != null }.flatMap { (path, content) ->
            machinePaths.filter { content!!.contains(it) }.map { "$path contains the machine path $it" }
        }
        if (leaks.isNotEmpty()) throw SiteBuildException("machine paths in output:\n" + leaks.joinToString("\n") { "  - $it" })

        val broken = LinkChecker.check(site.basePath, htmlFiles)
        if (broken.isNotEmpty()) {
            throw SiteBuildException("broken links (${broken.size}):\n" + broken.joinToString("\n") { "  - $it" })
        }
        return SiteOutput(files, allPages)
    }

    private fun docPage(
        site: SiteContext,
        section: SiteManifest.Section,
        source: SiteManifest.DocSource,
        rendered: RenderedDoc,
        project: ProjectInfo,
    ): Page {
        val modified = project.lastModified(source.sourcePath)
        val crumbs = listOf(Crumb("Docs", "docs/"), Crumb(section.title, "docs/#${section.anchor}"))
        return Page(
            path = source.path,
            title = rendered.title,
            headTitle = "${rendered.title} · jdx docs",
            description = rendered.description,
            bodyHtml = rendered.html,
            kind = PageKind.DOC,
            markdown = rendered.markdown,
            sourcePath = source.sourcePath,
            lastModified = modified,
            toc = rendered.headings,
            breadcrumbs = crumbs,
            navTitle = source.navTitle ?: rendered.title,
            jsonLd = listOf(
                StructuredData.techArticle(site, source.path, rendered.title, rendered.description, modified),
                StructuredData.breadcrumbs(site, listOf("Docs" to "docs/", section.title to "docs/#${section.anchor}", rendered.title to source.path)),
            ),
        )
    }

    private fun navTree(
        sectionPages: Map<SiteManifest.Section, List<Page>>,
        docsIndex: Page,
        commandPages: List<Page>,
        extraReference: List<Page>,
    ): NavTree = NavTree(
        SiteManifest.sections.map { section ->
            val pages = sectionPages[section].orEmpty()
            val items = when (section) {
                SiteManifest.gettingStarted ->
                    listOf(NavItem(docsIndex.navTitle, docsIndex.path)) + pages.map { NavItem(it.navTitle, it.path) }
                SiteManifest.reference ->
                    pages.map { page ->
                        NavItem(page.navTitle, page.path, children = commandPages.map { NavItem("`${it.title}`", it.path) })
                    } + extraReference.map { NavItem(it.navTitle, it.path) }
                else -> pages.map { NavItem(it.navTitle, it.path) }
            }
            NavSection(section.title, items)
        },
    )

    private fun ogImage(site: SiteContext, page: Page): ByteArray {
        val host = URI(site.baseUrl).host + site.basePath
        return OgImage.render(
            title = page.title.replace("`", ""),
            subtitle = DocRenderer.clipDescription(page.description).let { if (it.length > 120) it.take(117).substringBeforeLast(' ') + "…" else it },
            footer = (host + page.path).removeSuffix("404.html"),
            badge = site.latestVersion?.let { "v$it" },
        )
    }

    private fun prerun(lines: List<String>) {
        val commands = lines.distinct().map { ShellWords.parseJdxCommand(it) }
        val pool = Executors.newFixedThreadPool(PARALLELISM)
        try {
            commands.map { command -> pool.submit { runner.run(command.arguments, command.stdin) } }.forEach { it.get() }
        } finally {
            pool.shutdown()
            pool.awaitTermination(1, TimeUnit.MINUTES)
        }
    }

    /** GitHub Pages serves `404.html` for every miss: it must exist and be self-contained. */
    private fun page404Check(files: Map<String, String?>) {
        if ("404.html" !in files) throw SiteBuildException("404.html was not generated")
    }

    private fun customDomain(baseUrl: String): String? =
        URI(baseUrl).host?.takeIf { !it.endsWith(".github.io") && it != "localhost" && it != "127.0.0.1" }

    companion object {
        private const val PARALLELISM = 4

        fun resource(name: String): String =
            SiteBuilder::class.java.getResource("/site/$name")?.readText()
                ?: throw SiteBuildException("missing resource /site/$name")

        /** Writes [output] to [dir], replacing whatever was there (the dir is build output only). */
        fun write(output: SiteOutput, dir: File) {
            dir.deleteRecursively()
            for ((path, bytes) in output.files) {
                val file = File(dir, path)
                file.parentFile.mkdirs()
                file.writeBytes(bytes)
            }
        }
    }
}
