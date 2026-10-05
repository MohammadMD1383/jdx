package dev.jdx.site

import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.Document
import org.commonmark.node.Heading
import org.commonmark.node.Paragraph
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text
import java.io.File

/**
 * Landing, integrations, FAQ, changelog, docs index and 404.
 *
 * Copy discipline: product claims are QUOTED from README.md sections (by heading) rather
 * than retyped here, so the README stays the single source of marketing truth. The few
 * sentences written in this file are deliberately generic (true of any version).
 */
class ProductPages(
    private val site: SiteContext,
    private val repoRoot: File,
    private val facts: CliFacts,
    private val docRenderer: DocRenderer,
    private val reference: ReferencePages,
    private val project: ProjectInfo,
    private val releases: List<Release>,
) {
    /** The two commands the hero terminal runs (JDK-only, so they work with zero setup). */
    val heroCommands: List<String> = listOf(
        "jdx members java.util.HashMap --limit 5",
        "jdx body 'java.util.HashMap#get(java.lang.Object)'",
    )

    fun landing(runner: JdxRunner, faqQuestions: List<Pair<String, String>>): Page {
        val readmeText = File(repoRoot, "README.md").readText()
        val readme = Markdown.parse(readmeText)
        val proof = proofQuote(readme)
        val ideFeatures = featureCards(Markdown.extractSection(Markdown.parse(readmeText), "What makes it an", "README.md"))
        val agentFeatures = featureCards(Markdown.extractSection(Markdown.parse(readmeText), "What makes it for agents", "README.md"))
        val terminal = heroCommands.joinToString("\n") { line ->
            val command = ShellWords.parseJdxCommand(line)
            val result = runner.run(command.arguments, command.stdin)
            if (result.exitCode != 0) throw SiteBuildException("landing example failed (exit ${result.exitCode}): $line\n${result.stderr}")
            "$ $line\n" + (result.stdout + result.stderr).trimEnd()
        }
        val readmeTwin = docRenderer.renderDocument(Markdown.parse(readmeText), "README.md")
        val agents = facts.setupAgents
        val html = buildString {
            append(
                """
                |<section class="hero">
                |<div class="wrap hero-grid">
                |<div class="hero-copy">
                |<p class="eyebrow">For Claude Code, Cursor, Codex &amp; every MCP agent</p>
                |<h1>The IDE your AI agent is missing — for Java, Kotlin and every JVM jar.</h1>
                |<p class="hero-lead">${Html.text(StructuredData.TAGLINE.removePrefix("jdx is an IDE for AI agents, as a command line tool: ").replaceFirstChar { it.uppercase() })}</p>
                |${installBox()}
                |<p class="hero-cta"><a class="button primary" href="${site.rootRelative("docs/quickstart/")}">Quickstart</a>
                |<a class="button" href="${site.rootRelative("integrations/")}">Connect your agent</a>
                |<a class="button ghost" href="${site.repoUrl}">${Brand.GITHUB} Star on GitHub</a></p>
                |<p class="hero-facts">Apache-2.0 · JDK 21+ · Linux, macOS, Windows · read-only: never runs code from the jars it inspects</p>
                |</div>
                |<div class="hero-terminal">
                |<div class="terminal"><div class="terminal-bar" aria-hidden="true"><i></i><i></i><i></i><span>real jdx output</span></div>
                |${Html.codeBlock(terminal, "console", executed = false)}</div>
                |</div>
                |</div>
                |</section>
                |""".trimMargin(),
            )
            append("<section class=\"band proof\"><div class=\"wrap\">")
            append("<h2 id=\"proof\">Proof, not promises</h2>")
            append(proof)
            append("</div></section>\n")
            append("<section class=\"band\"><div class=\"wrap two-col\">")
            append("<div><h2 id=\"ide\">An IDE, not a better <code>javap</code></h2>$ideFeatures</div>")
            append("<div><h2 id=\"for-agents\">Built for agents, not humans</h2>$agentFeatures</div>")
            append("</div></section>\n")
            append(commandGrid())
            append(agentStrip(agents))
            append(demoSection())
            append(faqTeaser(faqQuestions))
            append(
                """
                |<section class="band cta"><div class="wrap">
                |<h2 id="get-started">Give your agent an IDE in 30 seconds</h2>
                |${installBox()}
                |<p class="hero-cta"><a class="button primary" href="${site.rootRelative("docs/installation/")}">Installation guide</a>
                |<a class="button" href="${site.rootRelative("docs/")}">Read the docs</a></p>
                |</div></section>
                |""".trimMargin(),
            )
        }
        return Page(
            path = "",
            title = "jdx — an IDE for AI agents",
            headTitle = "jdx — an IDE for AI agents: Java & Kotlin code navigation for Claude Code, Cursor and MCP",
            description = "jdx gives AI coding agents an IDE for the JVM: members, method bodies, usages, call graphs and API diffs for any jar, Maven coordinate or the JDK, as a CLI and MCP server.",
            bodyHtml = html,
            kind = PageKind.LANDING,
            markdown = readmeTwin.markdown,
            sourcePath = "README.md",
            lastModified = project.lastModified("README.md"),
            jsonLd = listOf(StructuredData.website(site), StructuredData.softwareApplication(site)),
        )
    }

    private fun installBox(): String =
        "<div class=\"install\"><span class=\"prompt\" aria-hidden=\"true\">$</span><code class=\"cmd\">${Html.text(site.installCommand)}</code>" +
            "<button class=\"copy\" type=\"button\" aria-label=\"Copy install command\">Copy</button></div>"

    /** The README's "Proof, not promises" blockquote (benchmark table), rendered as-is. */
    private fun proofQuote(readme: Document): String {
        val quote = Markdown.children(readme).filterIsInstance<BlockQuote>()
            .firstOrNull { Markdown.plainText(it).trim().startsWith("Proof, not promises") }
            ?: throw SiteBuildException("README.md has no `> **Proof, not promises.**` blockquote (the landing page quotes it)")
        val section = Document()
        Markdown.children(quote).forEach { section.appendChild(it) }
        (section.firstChild as? Paragraph)?.let { first ->
            (first.firstChild as? StrongEmphasis)?.unlink()
            (first.firstChild as? Text)?.let { it.literal = it.literal.trimStart() }
        }
        Markdown.rewriteLinks(section) { destination, _ -> rewriteReadmeLink(destination) }
        return "<div class=\"prose proof-body\">" + docRenderer.toRootRelative(Markdown.renderHtml(section) { null }) + "</div>"
    }

    private fun rewriteReadmeLink(destination: String): String? =
        if (destination.startsWith("docs/") && destination.endsWith(".sh")) "${site.repoUrl}/blob/main/$destination" else null

    /** README bullet list -> cards: the bold lead becomes the card title, the rest its body. */
    private fun featureCards(section: Document): String {
        val list = Markdown.children(section).filterIsInstance<BulletList>().firstOrNull()
            ?: throw SiteBuildException("README feature section has no bullet list")
        return buildString {
            append("<ul class=\"features\">")
            for (item in Markdown.children(list)) {
                val paragraph = item.firstChild as? Paragraph ?: continue
                val strong = paragraph.firstChild as? StrongEmphasis
                val titleHtml = strong?.let { node ->
                    val holder = Document()
                    val p = Paragraph()
                    Markdown.children(node).forEach { p.appendChild(it) }
                    holder.appendChild(p)
                    Markdown.renderHtml(holder) { null }.trim().removePrefix("<p>").removeSuffix("</p>")
                }
                strong?.unlink()
                (paragraph.firstChild as? Text)?.let { it.literal = it.literal.trimStart(' ', ',', '—', '-', ':', '.').replaceFirstChar { c -> c.uppercase() } }
                val holder = Document()
                Markdown.children(item).forEach { holder.appendChild(it) }
                val bodyHtml = docRenderer.toRootRelative(Markdown.renderHtml(holder) { null })
                append("<li>")
                titleHtml?.let { append("<strong class=\"feature-title\">$it</strong>") }
                append(bodyHtml)
                append("</li>")
            }
            append("</ul>")
        }
    }

    private fun commandGrid(): String = buildString {
        append("<section class=\"band\"><div class=\"wrap\">")
        append("<h2 id=\"commands\">One command per question</h2>")
        append("<p class=\"section-lead\">Every IDE gesture an agent needs, as a command with a precise, bounded answer. Click any for real output.</p>")
        append("<ul class=\"command-grid\">")
        for (row in facts.helpRows) {
            val name = row.command.substringBefore(' ')
            if (name !in facts.commandNames) continue
            append("<li><a href=\"${site.rootRelative(reference.commandPath(name))}\"><code>jdx ${Html.text(row.command)}</code>")
            append("<span>${Html.inlineCode(row.summary)}</span></a></li>")
        }
        append("</ul></div></section>\n")
    }

    private fun agentStrip(agents: List<String>): String = buildString {
        append("<section class=\"band agents\"><div class=\"wrap\">")
        append("<h2 id=\"agents\">Works with your agent</h2>")
        append("<p class=\"section-lead\">One MCP server, wired in with one command: <code>jdx setup --agent &lt;name&gt; --scope project</code>.</p>")
        append("<ul class=\"agent-list\">")
        for (agent in agents) {
            append("<li><a href=\"${site.rootRelative(integrationPath(agent))}\">${Html.text(agentName(agent))}</a></li>")
        }
        append("<li><a href=\"${site.rootRelative("integrations/")}#any-mcp-client\">Any MCP client</a></li>")
        append("</ul></div></section>\n")
    }

    private fun demoSection(): String {
        val demo = File(repoRoot, "docs/demo-callsites.gif")
        if (!demo.isFile) throw SiteBuildException("docs/demo-callsites.gif is gone (the landing page shows it)")
        docRenderer.assets["assets/${demo.name}"] = demo
        val (width, height) = ImageSize.of(demo) ?: (1200 to 675)
        return """
            |<section class="band"><div class="wrap">
            |<h2 id="demo">Same task, two agents</h2>
            |<p class="section-lead">Left: an agent with <code>javap</code> and <code>grep</code>. Right: the same agent with jdx. Task: find every <code>System.exit</code> call site in <code>java.base</code>.</p>
            |<img class="demo" src="${site.rootRelative("assets/${demo.name}")}" width="$width" height="$height" loading="lazy" decoding="async" alt="Side-by-side recording: baseline agent using javap and grep versus an agent using jdx to find all System.exit call sites in java.base">
            |</div></section>
            |""".trimMargin()
    }

    private fun faqTeaser(questions: List<Pair<String, String>>): String = buildString {
        if (questions.isEmpty()) return@buildString
        append("<section class=\"band\"><div class=\"wrap narrow\">")
        append("<h2 id=\"faq\">Questions</h2>")
        for ((question, answer) in questions.take(5)) {
            append("<details class=\"faq\"><summary>${Html.text(question)}</summary><p>${Html.text(answer)}</p></details>")
        }
        append("<p><a href=\"${site.rootRelative("faq/")}\">All questions →</a></p>")
        append("</div></section>\n")
    }

    // --- integrations -------------------------------------------------------------------------

    fun integrationPath(agent: String): String = "integrations/$agent/"

    fun agentName(agent: String): String = AGENT_NAMES[agent]
        ?: throw SiteBuildException("`jdx setup` supports agent '$agent' but ProductPages.AGENT_NAMES has no display name for it — add one")

    fun integrationsHub(): Page {
        val agents = facts.setupAgents
        val description = "Connect jdx to ${agents.joinToString(", ") { agentName(it) }} or any MCP client: one command gives your coding agent Java and Kotlin code navigation."
        val genericConfig = """{"mcpServers":{"jdx":{"command":"jdx","args":["mcp"]}}}"""
        val html = buildString {
            append(productHeader("Integrations", "jdx for AI coding agents", "One MCP server. ${agents.size} agents wired in by a single command — or any MCP client by hand."))
            append("<section class=\"band\"><div class=\"wrap\"><ul class=\"card-grid\">")
            for (agent in agents) {
                append("<li><a class=\"card\" href=\"${site.rootRelative(integrationPath(agent))}\"><strong>${Html.text(agentName(agent))}</strong>")
                append("<code>jdx setup --agent $agent --scope project</code></a></li>")
            }
            append("</ul></div></section>")
            append("<section class=\"band\"><div class=\"wrap narrow prose\">")
            append("<h2 id=\"any-mcp-client\">Any MCP client<a class=\"anchor\" href=\"#any-mcp-client\" aria-label=\"Link to this section\">#</a></h2>")
            append("<p>jdx speaks the Model Context Protocol over stdio. Point any client at the <code>jdx mcp</code> command:</p>")
            append(Html.codeBlock(genericConfig, "json", executed = false))
            append("<p>It exposes ${facts.mcpTools.size} typed tools — see <a href=\"${site.rootRelative(reference.mcpToolsPath)}\">MCP tools</a>.</p>")
            append("<h2 id=\"no-mcp\">No MCP? Use the CLI<a class=\"anchor\" href=\"#no-mcp\" aria-label=\"Link to this section\">#</a></h2>")
            append("<p>Every agent that can run shell commands can use jdx directly. Paste the <a href=\"${site.rootRelative(reference.cheatSheetPath)}\">agent cheat sheet</a> into its instructions.</p>")
            append("</div></section>")
        }
        val markdown = buildString {
            append("# jdx for AI coding agents\n\n$description\n\n")
            for (agent in agents) append("- [${agentName(agent)}](${site.absolute(integrationPath(agent))}): `jdx setup --agent $agent --scope project`\n")
            append("\n## Any MCP client\n\n```json\n$genericConfig\n```\n\nTools: ${site.absolute(reference.mcpToolsPath)}\n")
        }
        return Page(
            path = "integrations/",
            title = "jdx for AI coding agents",
            headTitle = "Integrations — jdx MCP server for ${agents.take(3).joinToString(", ") { agentName(it) }} and more",
            description = DocRenderer.clipDescription(description),
            bodyHtml = html,
            kind = PageKind.PRODUCT,
            markdown = markdown,
            lastModified = project.lastModified("cli/src/main/kotlin/dev/jdx/cli/commands/SetupCommand.kt"),
            jsonLd = listOf(StructuredData.breadcrumbs(site, listOf("Home" to "", "Integrations" to "integrations/"))),
        )
    }

    fun integrationPage(agent: String): Page {
        val name = agentName(agent)
        val path = integrationPath(agent)
        val setupHelp = facts.command(listOf("setup"))?.description.orEmpty()
        val specifics = setupHelp.split(Regex("""[;]\s*|\.\s+""")).map { it.trim().removeSuffix(".") }
            .filter { it.contains(name) || it.startsWith(agent) }
            .map { "$it." }
        val title = "jdx for $name"
        val description = "Give $name an IDE for Java, Kotlin and JVM jars: wire the jdx MCP server in one command, then ask for members, bodies, usages and call graphs."
        val commands = listOf(
            "jdx setup --agent $agent --scope project" to "Wire jdx into this project's $name config.",
            "jdx setup --agent $agent --scope system" to "Or wire it for every project (user-global config).",
            "jdx setup --agent $agent --scope project --check" to "Report whether it is wired, without writing anything.",
            "jdx setup --agent $agent --scope project --remove" to "Remove the entry again (other entries are never touched).",
        )
        val html = buildString {
            append(productHeader("Integrations", title, "Java and Kotlin code navigation for $name, over MCP."))
            append("<section class=\"band\"><div class=\"wrap narrow prose\">")
            append("<h2 id=\"setup\">Set up in two commands<a class=\"anchor\" href=\"#setup\" aria-label=\"Link to this section\">#</a></h2>")
            append("<p>1. <a href=\"${site.rootRelative("docs/installation/")}\">Install jdx</a> (needs JDK 21+):</p>")
            append(Html.codeBlock("$ ${site.installCommand}", "console", executed = false))
            append("<p>2. Wire it into $name, then restart $name:</p>")
            append(Html.codeBlock("$ ${commands[0].first}", "console", executed = false))
            if (specifics.isNotEmpty()) {
                append("<p>What it writes: ${Html.inlineCode(specifics.joinToString(" "))}</p>")
            }
            append("<h2 id=\"manage\">Check, scope and remove<a class=\"anchor\" href=\"#manage\" aria-label=\"Link to this section\">#</a></h2><ul>")
            for ((command, text) in commands.drop(1)) append("<li><code>${Html.text(command)}</code> — ${Html.text(text)}</li>")
            append("</ul><p>Re-running is a no-op, and unrelated entries in the config are merged, never clobbered.</p>")
            append("<h2 id=\"what-it-can-do\">What $name can then do<a class=\"anchor\" href=\"#what-it-can-do\" aria-label=\"Link to this section\">#</a></h2>")
            append("<p>$name gets ${facts.mcpTools.size} typed tools:</p><ul class=\"tool-list\">")
            for (tool in facts.mcpTools) {
                append("<li><a href=\"${site.rootRelative(reference.mcpToolsPath)}#${tool.name}\"><code>${tool.name}</code></a> — ${Html.inlineCode(firstSentence(tool.description))}</li>")
            }
            append("</ul>")
            append("<p>Prefer the CLI? Paste the <a href=\"${site.rootRelative(reference.cheatSheetPath)}\">agent cheat sheet</a> into $name's instructions instead.</p>")
            append("</div></section>")
        }
        val markdown = buildString {
            append("# $title\n\n$description\n\n## Set up\n\n```console\n$ ${site.installCommand}\n$ ${commands[0].first}\n```\n\n")
            if (specifics.isNotEmpty()) append("What it writes: ${specifics.joinToString(" ")}\n\n")
            append("## Manage\n\n")
            for ((command, text) in commands.drop(1)) append("- `$command` — $text\n")
            append("\n## Tools\n\n")
            for (tool in facts.mcpTools) append("- `${tool.name}` — ${firstSentence(tool.description)}\n")
        }
        return Page(
            path = path,
            title = title,
            headTitle = "$title — Java & Kotlin MCP server for $name · jdx",
            description = description,
            bodyHtml = html,
            kind = PageKind.PRODUCT,
            markdown = markdown,
            lastModified = project.lastModified("cli/src/main/kotlin/dev/jdx/cli/commands/SetupCommand.kt"),
            jsonLd = listOf(
                StructuredData.breadcrumbs(site, listOf("Home" to "", "Integrations" to "integrations/", name to path)),
                StructuredData.techArticle(site, path, title, description, null),
            ),
        )
    }

    // --- FAQ, changelog, docs index, 404 -------------------------------------------------------

    /** (question, plain-text answer) pairs from docs/FAQ.md: each H2 and the paragraphs under it. */
    fun faqQuestions(): List<Pair<String, String>> {
        val document = Markdown.parse(File(repoRoot, SiteManifest.faq.sourcePath).readText())
        val result = mutableListOf<Pair<String, String>>()
        var question: String? = null
        val answer = StringBuilder()
        fun flush() {
            question?.let { result += it to answer.toString().replace(Regex("\\s+"), " ").trim() }
            answer.clear()
        }
        for (node in Markdown.children(document)) {
            when {
                node is Heading && node.level == 2 -> { flush(); question = Markdown.plainText(node).trim() }
                node is Heading -> { flush(); question = null }
                question != null -> answer.append(Markdown.plainText(node)).append(' ')
            }
        }
        flush()
        return result
    }

    fun faqPage(): Page {
        val source = SiteManifest.faq
        val rendered = docRenderer.render(source.sourcePath, source.description)
        val questions = faqQuestions()
        val html = productHeader("FAQ", rendered.title, "Short answers. Every one links to the docs that prove it.") +
            "<section class=\"band\"><div class=\"wrap narrow prose\">${rendered.html}</div></section>"
        return Page(
            path = source.path,
            title = rendered.title,
            headTitle = "jdx FAQ — AI agent code navigation for Java & Kotlin",
            description = rendered.description,
            bodyHtml = html,
            kind = PageKind.PRODUCT,
            markdown = rendered.markdown,
            sourcePath = source.sourcePath,
            lastModified = project.lastModified(source.sourcePath),
            toc = rendered.headings,
            jsonLd = listOf(StructuredData.faqPage(questions)),
        )
    }

    fun changelogPage(): Page {
        val latestTag = releases.firstOrNull()?.tag ?: project.latestTag()
        val html = StringBuilder(productHeader("Changelog", "Changelog", "Every jdx release, newest first. Install or upgrade with one command."))
        val markdown = StringBuilder("# Changelog\n\n")
        html.append("<section class=\"band\"><div class=\"wrap narrow prose\">")
        if (latestTag != null) {
            val compare = "${site.repoUrl}/compare/$latestTag...main"
            html.append("<p class=\"callout\">Already on jdx? <code>jdx upgrade</code> moves a release install to the latest version. ")
            html.append("Changes merged since <code>${Html.text(latestTag)}</code> are on <a href=\"$compare\">main</a>; the docs on this site track main.</p>")
            markdown.append("Upgrade with `jdx upgrade`. Unreleased changes since $latestTag: $compare\n\n")
        }
        if (releases.isEmpty()) {
            html.append("<p>Release notes live on <a href=\"${site.repoUrl}/releases\">GitHub Releases</a>.</p>")
            markdown.append("Release notes: ${site.repoUrl}/releases\n")
        }
        for (release in releases) {
            val id = release.tag.replace('.', '-')
            val date = release.date.take(10)
            html.append("<h2 id=\"$id\"><a href=\"${Html.attr(release.url)}\">${Html.text(release.name)}</a>")
            html.append("<a class=\"anchor\" href=\"#$id\" aria-label=\"Link to this section\">#</a></h2>")
            html.append("<p class=\"release-date\"><time datetime=\"${Html.attr(release.date)}\">$date</time></p>")
            val body = demoteHeadings(Markdown.parse(release.body))
            // Release notes are written on GitHub, not reviewed like repo docs: raw HTML is escaped,
            // and heading ids are prefixed because every release repeats "What's Changed".
            html.append(Markdown.renderHtml(body, escapeHtml = true, idPrefix = "$id-") { null })
            markdown.append("## ${release.name} ($date)\n\n${release.body.trim()}\n\n")
        }
        html.append("</div></section>")
        return Page(
            path = "changelog/",
            title = "Changelog",
            headTitle = "jdx changelog — release notes",
            description = "Release notes for every jdx version: new commands, flags, agent integrations and fixes${latestTag?.let { ", up to $it" }.orEmpty()}.",
            bodyHtml = html.toString(),
            kind = PageKind.PRODUCT,
            markdown = markdown.toString(),
            lastModified = releases.firstOrNull()?.date ?: latestTag?.let { project.tagDate(it) },
        )
    }

    private fun demoteHeadings(document: Document): Document {
        document.accept(object : org.commonmark.node.AbstractVisitor() {
            override fun visit(heading: Heading) {
                heading.level = (heading.level + 2).coerceAtMost(6)
                visitChildren(heading)
            }
        })
        return document
    }

    fun docsIndex(sectionPages: Map<SiteManifest.Section, List<Page>>, commandPages: List<Page>, extraReference: List<Page>): Page {
        val html = StringBuilder()
        val markdown = StringBuilder("# Documentation\n\n")
        val intro = "Start at the top and go as deep as you need: getting started takes five minutes, the guides cover every task, " +
            "the reference is generated from the binary itself, and the advanced section is the full design."
        html.append("<p>${Html.text(intro)}</p>\n")
        markdown.append(intro).append("\n\n")
        val headings = mutableListOf<HeadingInfo>()
        for ((index, section) in SiteManifest.sections.withIndex()) {
            headings += HeadingInfo(2, section.title, section.anchor)
            html.append("<h2 id=\"${section.anchor}\"><span class=\"step\">${index + 1}</span>${Html.text(section.title)}")
            html.append("<a class=\"anchor\" href=\"#${section.anchor}\" aria-label=\"Link to this section\">#</a></h2>\n")
            html.append("<p>${Html.text(section.summary)}</p>\n<ul class=\"card-grid\">")
            markdown.append("## ${section.title}\n\n${section.summary}\n\n")
            val pages = sectionPages[section].orEmpty() + if (section == SiteManifest.reference) extraReference else emptyList()
            for (page in pages) {
                html.append("<li><a class=\"card\" href=\"${site.rootRelative(page.path)}\"><strong>${Html.inlineCode(page.navTitle)}</strong>")
                html.append("<span>${Html.text(page.description)}</span></a></li>")
                markdown.append("- [${page.navTitle}](${site.absolute(page.path)}): ${page.description}\n")
            }
            html.append("</ul>\n")
            if (section == SiteManifest.reference) {
                html.append("<p class=\"command-links\">Commands: ")
                html.append(commandPages.joinToString(" ") { "<a href=\"${site.rootRelative(it.path)}\"><code>${Html.text(it.navTitle)}</code></a>" })
                html.append("</p>\n")
                markdown.append("\nCommands: ")
                markdown.append(commandPages.joinToString(", ") { "[${it.navTitle}](${site.absolute(it.path)})" })
                markdown.append("\n")
            }
            markdown.append("\n")
        }
        return Page(
            path = "docs/",
            title = "Documentation",
            headTitle = "jdx documentation — getting started, guides, reference",
            description = "jdx documentation: install, quickstart, AI agent setup, guides for every task, the full command and MCP reference, and the design spec.",
            bodyHtml = html.toString(),
            kind = PageKind.DOC,
            markdown = markdown.toString(),
            toc = headings,
            lastModified = project.lastModified("docs"),
            navTitle = "Overview",
        )
    }

    fun notFoundPage(): Page = Page(
        path = "404.html",
        title = "Page not found",
        description = "This page does not exist. Try the jdx documentation or the command reference.",
        bodyHtml = productHeader("404", "Page not found", "The page may have moved. These always exist:") +
            "<section class=\"band\"><div class=\"wrap narrow\"><ul class=\"card-grid\">" +
            listOf("Documentation" to "docs/", "Quickstart" to "docs/quickstart/", "Command reference" to "docs/reference/", "Home" to "")
                .joinToString("") { (label, path) -> "<li><a class=\"card\" href=\"${site.rootRelative(path)}\"><strong>$label</strong></a></li>" } +
            "</ul></div></section>",
        kind = PageKind.PRODUCT,
        noIndex = true,
    )

    private fun productHeader(eyebrow: String, title: String, lead: String): String =
        "<section class=\"page-hero\"><div class=\"wrap\"><p class=\"eyebrow\">${Html.text(eyebrow)}</p>" +
            "<h1>${Html.inlineCode(title)}</h1><p class=\"hero-lead\">${Html.inlineCode(lead)}</p></div></section>\n"

    private fun firstSentence(text: String): String =
        Regex("""^(.+?[.!?])(\s|$)""").find(text)?.groupValues?.get(1) ?: text

    companion object {
        /** Display names for `jdx setup --agent` values. A new agent without a name fails the build on purpose. */
        val AGENT_NAMES: Map<String, String> = mapOf(
            "opencode" to "OpenCode",
            "claude-code" to "Claude Code",
            "cursor" to "Cursor",
            "kilo" to "Kilo Code",
            "cline" to "Cline",
            "codex" to "Codex CLI",
            "copilot" to "GitHub Copilot CLI",
        )
    }
}
