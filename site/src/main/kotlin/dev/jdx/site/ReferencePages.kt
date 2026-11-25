package dev.jdx.site

/**
 * The generated half of the Reference section: one page per command, the agent cheat sheet,
 * and the MCP tool list — all from [CliFacts], i.e. from the binary itself. Nothing here is
 * hand-written about a flag; only the worked examples are chosen here, and those are executed.
 */
class ReferencePages(
    private val site: SiteContext,
    private val facts: CliFacts,
    private val runner: JdxRunner,
    private val lastModified: String?,
) {
    /** A Related-section target: another command, or a guide/page by its own title. */
    sealed interface RelatedTarget {
        data class Command(val name: String) : RelatedTarget
        data class Page(val anchor: String, val path: String) : RelatedTarget
    }
    fun commandPath(name: String): String = "docs/reference/$name/"

    val cheatSheetPath = "docs/reference/cheat-sheet/"
    val mcpToolsPath = "docs/reference/mcp-tools/"

    /** Command lines run for examples, so the caller can execute them in parallel up front. */
    fun exampleCommandLines(): List<String> = EXAMPLES.values.toList()

    fun commandPages(): List<Page> = facts.commandNames.map { name -> commandPage(name) }

    private fun commandPage(name: String): Page {
        val help = facts.command(listOf(name)) ?: throw SiteBuildException("no help for jdx $name")
        val row = facts.helpRow(name)
        val markdown = StringBuilder()
        val html = StringBuilder()
        val title = "jdx $name"
        val lead = row?.summary?.let { "IDE equivalent: $it" }
        val description = DocRenderer.clipDescription("jdx $name: ${firstSentence(help.description)}")

        markdown.append("# $title\n\n")
        lead?.let { markdown.append("$it\n\n") }
        markdown.append(help.description).append("\n\n")
        html.append("<p>${Html.inlineCode(help.description)}</p>\n")

        section(html, markdown, "Usage", "usage")
        val usage = "jdx ${help.usage.removePrefix("jdx ")}"
        html.append(Html.codeBlock(usage, "text", executed = false))
        markdown.append("```text\n$usage\n```\n\n")

        EXAMPLES[name]?.let { example ->
            section(html, markdown, "Example", "example")
            val output = runExample(name, example)
            html.append(Html.codeBlock(output, "console", executed = true))
            markdown.append("```console\n$output\n```\n\n")
        }

        if (help.subcommands.isNotEmpty()) {
            section(html, markdown, "Subcommands", "subcommands")
            for ((sub, _) in help.subcommands) {
                val subHelp = facts.command(listOf(name, sub)) ?: continue
                val subTitle = "jdx $name $sub"
                val id = "$name-$sub"
                html.append("<h3 id=\"$id\"><code>${Html.text(subTitle)}</code><a class=\"anchor\" href=\"#$id\" aria-label=\"Link to this section\">#</a></h3>\n")
                markdown.append("### `$subTitle`\n\n")
                html.append("<p>${Html.inlineCode(subHelp.description)}</p>\n")
                markdown.append(subHelp.description).append("\n\n")
                val subUsage = "jdx ${subHelp.usage.removePrefix("jdx ")}"
                html.append(Html.codeBlock(subUsage, "text", executed = false))
                markdown.append("```text\n$subUsage\n```\n\n")
                optionsTable(html, markdown, subHelp.options)
                argumentsTable(html, markdown, subHelp.arguments)
            }
        }

        if (help.options.isNotEmpty()) {
            section(html, markdown, "Options", "options")
            optionsTable(html, markdown, help.options)
        }
        if (help.arguments.isNotEmpty()) {
            section(html, markdown, "Arguments", "arguments")
            argumentsTable(html, markdown, help.arguments)
        }

        section(html, markdown, "Full help text", "full-help-text")
        val helpCommand = "$ jdx $name --help\n${help.raw}"
        html.append("<details><summary>Show <code>jdx $name --help</code></summary>")
        html.append(Html.codeBlock(helpCommand, "console", executed = true))
        html.append("</details>\n")
        markdown.append("```console\n$helpCommand\n```\n\n")

        val related = relatedLinks(name)
        if (related != null) {
            // Descriptive anchors (the help-sheet summary, or the guide's own title), so a
            // crawler can tell the pages apart. Replaces the single "Learn more" line.
            section(html, markdown, "Related", "related")
            html.append("<ul class=\"command-index\">\n")
            for (link in related) {
                html.append("<li><a href=\"${site.rootRelative(link.path)}\">${link.html}</a></li>\n")
                val suffix = link.commandName?.let { " (`jdx $it`)" }.orEmpty()
                markdown.append("- [${markdownLinkText(link.markdownLabel)}](${site.absolute(link.path)})$suffix\n")
            }
            html.append("</ul>\n")
        } else {
            RELATED_GUIDES[name]?.let { (guideTitle, guidePath) ->
                section(html, markdown, "Learn more", "learn-more")
                html.append("<p>Guide: <a href=\"${site.rootRelative(guidePath)}\">${Html.text(guideTitle)}</a> · ")
                html.append("<a href=\"${site.rootRelative("docs/reference/")}\">All commands</a></p>\n")
                markdown.append("Guide: [$guideTitle](${site.absolute(guidePath)}) · [All commands](${site.absolute("docs/reference/")})\n")
            }
            val seeAlso = SEE_ALSO[name].orEmpty()
            if (seeAlso.isNotEmpty()) {
                html.append("<p>See also: ")
                html.append(
                    seeAlso.joinToString(" · ") { (guideTitle, guidePath) ->
                        "<a href=\"${site.rootRelative(guidePath)}\">${Html.text(guideTitle)}</a>"
                    },
                )
                html.append("</p>\n")
                markdown.append("\nSee also: ")
                markdown.append(
                    seeAlso.joinToString(" · ") { (guideTitle, guidePath) ->
                        "[$guideTitle](${site.absolute(guidePath)})"
                    },
                )
                markdown.append("\n")
            }
            RELATED_COMMANDS[name]?.let { relatedCommands ->
                html.append("<p>Related commands: ")
                html.append(
                    relatedCommands.joinToString(" · ") { other ->
                        "<a href=\"${site.rootRelative(commandPath(other))}\"><code>jdx ${Html.text(other)}</code></a>"
                    },
                )
                html.append("</p>\n")
                markdown.append("\nRelated commands: ")
                markdown.append(
                    relatedCommands.joinToString(" · ") { other ->
                        "[`jdx $other`](${site.absolute(commandPath(other))})"
                    },
                )
                markdown.append("\n")
            }
        }

        val path = commandPath(name)
        val headings = buildList {
            add(HeadingInfo(2, "Usage", "usage"))
            if (EXAMPLES.containsKey(name)) add(HeadingInfo(2, "Example", "example"))
            if (help.subcommands.isNotEmpty()) add(HeadingInfo(2, "Subcommands", "subcommands"))
            if (help.options.isNotEmpty()) add(HeadingInfo(2, "Options", "options"))
            if (help.arguments.isNotEmpty()) add(HeadingInfo(2, "Arguments", "arguments"))
            if (name in RELATED) add(HeadingInfo(2, "Related", "related"))
        }
        return Page(
            path = path,
            title = title,
            headTitle = "$title — ${row?.summary ?: firstSentence(help.description)} · jdx",
            description = description,
            bodyHtml = html.toString(),
            kind = PageKind.DOC,
            lead = lead,
            markdown = markdown.toString(),
            lastModified = lastModified,
            toc = headings,
            breadcrumbs = listOf(Crumb("Docs", "docs/"), Crumb("Reference", "docs/reference/")),
            jsonLd = listOf(
                StructuredData.techArticle(site, path, title, description, lastModified),
                StructuredData.breadcrumbs(site, listOf("Docs" to "docs/", "Reference" to "docs/reference/", title to path)),
            ),
            navTitle = name,
        )
    }

    fun cheatSheetPage(): Page {
        val title = "Agent cheat sheet"
        val description = "The compact jdx block to paste into CLAUDE.md, AGENTS.md or a system prompt: every command, the ref syntax and the exit codes."
        val intro = "Paste this into your agent's instructions (`CLAUDE.md`, `AGENTS.md`, `.cursorrules`, a system prompt) " +
            "so it reaches for jdx instead of `unzip`, `javap` and `grep`. It is the verbatim output of `jdx help --agent`, " +
            "regenerated from the current build."
        val block = "$ jdx help --agent\n${facts.agentCheatSheet.trimEnd()}"
        val html = "<p>${Html.inlineCode(intro)}</p>\n" + Html.codeBlock(block, "console", executed = true) +
            "<p>Prefer typed tools? Run <code>jdx mcp</code> instead — see <a href=\"${site.rootRelative(mcpToolsPath)}\">MCP tools</a>.</p>\n"
        val markdown = "# $title\n\n$intro\n\n```console\n$block\n```\n"
        return Page(
            path = cheatSheetPath,
            title = title,
            description = description,
            bodyHtml = html,
            kind = PageKind.DOC,
            markdown = markdown,
            lastModified = lastModified,
            breadcrumbs = listOf(Crumb("Docs", "docs/"), Crumb("Reference", "docs/reference/")),
            jsonLd = listOf(StructuredData.techArticle(site, cheatSheetPath, title, description, lastModified)),
        )
    }

    fun mcpToolsPage(): Page {
        val title = "MCP tools"
        val description = "Every tool the jdx MCP server exposes to Claude Code, Cursor, Codex and other MCP clients, with parameters — generated from jdx mcp."
        val html = StringBuilder()
        val markdown = StringBuilder("# $title\n\n")
        val intro = "`jdx mcp` serves ${facts.mcpTools.size} typed tools over the Model Context Protocol (stdio). " +
            "This list is what the server itself answers to `tools/list` in the current build."
        html.append("<p>${Html.inlineCode(intro)}</p>\n")
        markdown.append(intro).append("\n\n")
        facts.mcpInstructions?.let {
            html.append("<blockquote><p>${Html.text(it)}</p></blockquote>\n")
            markdown.append("> $it\n\n")
        }
        val headings = mutableListOf<HeadingInfo>()
        for (tool in facts.mcpTools) {
            headings += HeadingInfo(2, tool.name, tool.name)
            html.append("<h2 id=\"${tool.name}\"><code>${tool.name}</code><a class=\"anchor\" href=\"#${tool.name}\" aria-label=\"Link to this section\">#</a></h2>\n")
            html.append("<p>${Html.inlineCode(tool.description)}</p>\n")
            markdown.append("## `${tool.name}`\n\n${tool.description}\n\n")
            if (tool.parameters.isNotEmpty()) {
                html.append("<div class=\"table\"><table><thead><tr><th>Parameter</th><th>Type</th><th>Description</th></tr></thead><tbody>")
                markdown.append("| Parameter | Type | Description |\n|---|---|---|\n")
                for (parameter in tool.parameters) {
                    val required = if (parameter.required) " <span class=\"req\">required</span>" else ""
                    val detail = parameterDetail(parameter)
                    html.append("<tr><td><code>${Html.text(parameter.name)}</code>$required</td><td>${Html.text(parameter.type)}</td><td>${Html.inlineCode(detail)}</td></tr>")
                    markdown.append("| `${parameter.name}`${if (parameter.required) " (required)" else ""} | ${parameter.type} | ${cell(detail)} |\n")
                }
                html.append("</tbody></table></div>\n")
                markdown.append("\n")
            }
        }
        return Page(
            path = mcpToolsPath,
            title = title,
            description = description,
            bodyHtml = html.toString(),
            kind = PageKind.DOC,
            markdown = markdown.toString(),
            lastModified = lastModified,
            toc = headings,
            breadcrumbs = listOf(Crumb("Docs", "docs/"), Crumb("Reference", "docs/reference/")),
            jsonLd = listOf(StructuredData.techArticle(site, mcpToolsPath, title, description, lastModified)),
        )
    }

    private fun runExample(name: String, line: String): String {
        val command = ShellWords.parseJdxCommand(line)
        val result = runner.run(command.arguments, command.stdin)
        if (result.exitCode != 0) {
            throw SiteBuildException(
                "reference example for `jdx $name` failed (exit ${result.exitCode}): $line\n${result.stdout}${result.stderr}\n" +
                    "Fix ReferencePages.EXAMPLES or the CLI.",
            )
        }
        val output = (result.stdout + result.stderr).trimEnd()
        val lines = output.lines()
        val shown = if (lines.size > EXAMPLE_MAX_LINES) {
            lines.take(EXAMPLE_MAX_LINES) + "… ${lines.size - EXAMPLE_MAX_LINES} more lines (run it yourself to see them all)"
        } else {
            lines
        }
        return "$ $line\n" + shown.joinToString("\n")
    }

    /** One descriptive link in a command page's Related list. [html] is the anchor inner HTML. */
    private data class RelatedLink(
        val html: String,
        val markdownLabel: String,
        val path: String,
        val commandName: String?,
    )

    /** The generated "Command pages" block prepended to `/docs/reference/`. */
    data class CommandCatalogue(val html: String, val markdown: String, val headings: List<HeadingInfo>)

    /**
     * Grouped links from the command catalogue (`/docs/reference/`) to every command page.
     * The anchor is the one-line summary `jdx help` prints, plus the command name — never
     * the bare name alone. Fails the build when a command is missing from [COMMAND_GROUPS]
     * or from the help sheet, so a new command cannot ship without a descriptive link.
     */
    fun commandCatalogue(): CommandCatalogue {
        val names = facts.commandNames.toSet()
        val grouped = COMMAND_GROUPS.flatMap { it.second }
        val duplicates = grouped.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        if (duplicates.isNotEmpty()) {
            throw SiteBuildException("COMMAND_GROUPS lists ${duplicates.sorted()} more than once")
        }
        val missing = (names - grouped.toSet()).sorted()
        val extra = (grouped.toSet() - names).sorted()
        if (missing.isNotEmpty() || extra.isNotEmpty()) {
            throw SiteBuildException(
                "COMMAND_GROUPS does not match `jdx --help` (missing $missing, extra $extra). " +
                    "Add the command to a group in ReferencePages.",
            )
        }
        val html = StringBuilder()
        val markdown = StringBuilder()
        val headings = mutableListOf<HeadingInfo>()
        val intro = "Each command has its own page, generated from `jdx <command> --help`. " +
            "The link is the one-line summary `jdx help` prints."
        html.append("<h2 id=\"command-pages\">Command pages")
        html.append("<a class=\"anchor\" href=\"#command-pages\" aria-label=\"Link to this section\">#</a></h2>\n")
        html.append("<p>${Html.inlineCode(intro)}</p>\n")
        markdown.append("## Command pages\n\n$intro\n\n")
        headings += HeadingInfo(2, "Command pages", "command-pages")
        for ((title, commands) in COMMAND_GROUPS) {
            val id = "command-pages-" + title.lowercase().filter { it.isLetterOrDigit() || it == ' ' }.trim().replace(' ', '-')
            html.append("<h3 id=\"$id\">${Html.text(title)}")
            html.append("<a class=\"anchor\" href=\"#$id\" aria-label=\"Link to this section\">#</a></h3>\n")
            html.append("<ul class=\"command-index\">\n")
            markdown.append("### $title\n\n")
            headings += HeadingInfo(3, title, id)
            for (name in commands) {
                val summary = helpSummary(name)
                val path = commandPath(name)
                html.append("<li><a href=\"${site.rootRelative(path)}\">")
                html.append("${Html.inlineCode(summary)} (<code>jdx ${Html.text(name)}</code>)</a></li>\n")
                markdown.append("- [${markdownLinkText(summary)}](${site.absolute(path)}) (`jdx $name`)\n")
            }
            html.append("</ul>\n")
            markdown.append("\n")
        }
        return CommandCatalogue(html.toString(), markdown.toString().trimEnd() + "\n", headings)
    }

    /** The `jdx help` one-liner for [name]. Missing rows fail the build rather than inventing a label. */
    fun helpSummary(name: String): String =
        facts.helpRow(name)?.summary
            ?: throw SiteBuildException("no `jdx help` summary for `$name` — add it to HELP_ROWS")

    /**
     * 2–5 descriptive links for the commands Google has not discovered from sibling pages.
     * Command anchors are [helpSummary]; page anchors are that page's own title.
     */
    private fun relatedLinks(name: String): List<RelatedLink>? {
        val targets = RELATED[name] ?: return null
        if (targets.size !in 2..5) {
            throw SiteBuildException("`jdx $name` Related section has ${targets.size} links; keep 2–5")
        }
        return targets.map { target ->
            when (target) {
                is RelatedTarget.Command -> {
                    if (target.name !in facts.commandNames) {
                        throw SiteBuildException("Related link from `$name` names unknown command `${target.name}`")
                    }
                    val summary = helpSummary(target.name)
                    RelatedLink(
                        html = "${Html.inlineCode(summary)} (<code>jdx ${Html.text(target.name)}</code>)",
                        markdownLabel = summary,
                        path = commandPath(target.name),
                        commandName = target.name,
                    )
                }
                is RelatedTarget.Page -> RelatedLink(
                    html = Html.text(target.anchor),
                    markdownLabel = target.anchor,
                    path = target.path,
                    commandName = null,
                )
            }
        }
    }

    private fun section(html: StringBuilder, markdown: StringBuilder, title: String, id: String) {
        html.append("<h2 id=\"$id\">${Html.text(title)}<a class=\"anchor\" href=\"#$id\" aria-label=\"Link to this section\">#</a></h2>\n")
        markdown.append("## $title\n\n")
    }

    private fun optionsTable(html: StringBuilder, markdown: StringBuilder, options: List<CliOption>) {
        if (options.isEmpty()) return
        html.append("<div class=\"table\"><table><thead><tr><th>Option</th><th>Description</th></tr></thead><tbody>")
        markdown.append("| Option | Description |\n|---|---|\n")
        for (option in options) {
            html.append("<tr><td><code>${Html.text(option.signature)}</code></td><td>${Html.inlineCode(option.description)}</td></tr>")
            markdown.append("| `${cell(option.signature)}` | ${cell(option.description)} |\n")
        }
        html.append("</tbody></table></div>\n")
        markdown.append("\n")
    }

    private fun argumentsTable(html: StringBuilder, markdown: StringBuilder, arguments: List<Pair<String, String>>) {
        if (arguments.isEmpty()) return
        html.append("<div class=\"table\"><table><thead><tr><th>Argument</th><th>Description</th></tr></thead><tbody>")
        markdown.append("| Argument | Description |\n|---|---|\n")
        for ((argument, text) in arguments) {
            html.append("<tr><td><code>${Html.text(argument)}</code></td><td>${Html.inlineCode(text)}</td></tr>")
            markdown.append("| `${cell(argument)}` | ${cell(text)} |\n")
        }
        html.append("</tbody></table></div>\n")
        markdown.append("\n")
    }

    private fun cell(text: String): String = text.replace("|", "\\|")

    /** CommonMark link text: escape the punctuation that would end the link or start code. */
    private fun markdownLinkText(text: String): String =
        text.replace("\\", "\\\\").replace("[", "\\[").replace("]", "\\]").replace("`", "\\`")

    /**
     * The parameter cell: the server's description, plus enum, default and examples
     * when `tools/list` advertises them. Those live on the JSON Schema, so the page
     * stays aligned with the MCP definition without restating them by hand.
     */
    private fun parameterDetail(parameter: McpParameter): String = buildString {
        append(parameter.description)
        if (parameter.enumValues.isNotEmpty()) {
            append(" Allowed: ")
            append(parameter.enumValues.joinToString(", "))
            append('.')
        }
        if (parameter.defaultValue != null) {
            append(" Default: ")
            append(parameter.defaultValue)
            append('.')
        }
        if (parameter.examples.isNotEmpty()) {
            append(" Examples: ")
            append(parameter.examples.joinToString(", "))
            append('.')
        }
    }

    private fun firstSentence(text: String): String =
        Regex("""^(.+?[.!?])(\s|$)""").find(text)?.groupValues?.get(1) ?: text

    companion object {
        private const val EXAMPLE_MAX_LINES = 30

        /** Pinned fixture for examples: Gson is the project's everyday correctness fixture (AGENTS.md §11). */
        const val GSON = "com.google.code.gson:gson:2.14.0"

        /** Prefetched once per build so examples without `--fetch` resolve from the sandbox cache. */
        val PREFETCH: List<String> = listOf("show", "com.google.gson.Gson", "--coord", GSON, "--fetch")

        /**
         * One executed example per read-only command. Commands with side effects (ws, cache,
         * daemon, serve, mcp, setup, upgrade, kotlin) or machine-specific output (doctor,
         * version, bench) get none — their pages show usage and options only.
         */
        val EXAMPLES: Map<String, String> = linkedMapOf(
            "show" to "jdx show com.google.gson.Gson --coord $GSON",
            "members" to "jdx members com.google.gson.JsonArray --declared --limit 12 --coord $GSON",
            "outline" to "jdx outline com.google.gson.JsonPrimitive --coord $GSON",
            "signature" to "jdx signature 'com.google.gson.Gson#fromJson' --limit 5 --coord $GSON",
            "doc" to "jdx doc 'com.google.gson.Gson#toJson(Object)' --coord $GSON",
            "body" to "jdx body 'com.google.gson.Gson#toJson(Object)' --coord $GSON",
            "source" to "jdx source com.google.gson.JsonNull --lines 17:45 --coord $GSON",
            "search" to "jdx search '*TypeAdapter*' --kind class --limit 10 --coord $GSON",
            "resolve" to "jdx resolve JsonParser --coord $GSON",
            "ls" to "jdx ls com.google.gson.stream --coord $GSON",
            "tree" to "jdx tree 'gson*' --depth 3 --coord $GSON",
            "usages" to "jdx usages com.google.gson.JsonNull --limit 10 --coord $GSON",
            "annotated" to "jdx annotated deprecated --limit 10 --coord $GSON",
            "hierarchy" to "jdx hierarchy com.google.gson.JsonElement --coord $GSON",
            "implementors" to "jdx implementors com.google.gson.TypeAdapterFactory --direct --limit 10 --coord $GSON",
            "callers" to "jdx callers 'com.google.gson.Gson#getAdapter(java.lang.Class)' --coord $GSON",
            "calls" to "jdx calls 'com.google.gson.Gson#toJson(java.lang.Object)' --coord $GSON",
            "samples" to "jdx samples 'com.google.gson.JsonParser#parseString(String)' --coord $GSON",
            "literals" to "jdx literals 'com.google.gson' --coord $GSON --limit 5 --no-jdk",
            "services" to "jdx services java.sql.Driver --coord org.postgresql:postgresql:42.7.4 --fetch --no-jdk",
            "modules" to "jdx modules java.sql --limit 5",
            "manifest" to "jdx manifest Multi-Release --coord $GSON --no-jdk",
            "resources" to "jdx resources META-INF --coord $GSON --limit 5 --no-jdk",
            "diff" to "jdx diff com.google.code.gson:gson:2.11.0 $GSON --fetch",
            "batch" to "echo '{\"command\":\"show\",\"query\":\"java.util.Map\"}' | jdx batch",
        )

        /** The guide that explains each command in context (unlisted commands link to the catalogue only). */
        val RELATED_GUIDES: Map<String, Pair<String, String>> = buildMap {
            val reading = "Reading code" to "docs/reading-code/"
            val navigating = "Usages, hierarchy & calls" to "docs/navigating-code/"
            val serving = "MCP, daemon, HTTP & batch" to "docs/serving/"
            listOf("show", "members", "outline", "signature", "doc", "body", "source", "search", "resolve", "ls", "tree", "kotlin")
                .forEach { put(it, reading) }
            listOf(
                "usages", "annotated", "hierarchy", "implementors", "callers", "calls", "samples",
                "literals", "services", "modules", "manifest", "resources",
            )
                .forEach { put(it, navigating) }
            listOf("daemon", "serve", "mcp", "batch", "bench").forEach { put(it, serving) }
            listOf("ws", "cache").forEach { put(it, "Classpath & workspaces" to "docs/classpath/") }
            listOf("setup", "help").forEach { put(it, "Use with AI agents" to "docs/ai-agents/") }
            listOf("version", "upgrade", "doctor").forEach { put(it, "Installation" to "docs/installation/") }
            put("diff", "API diff for upgrades" to "docs/api-diff/")
        }

        private val MIXIN_GUIDE = "Find a Mixin target in Minecraft" to "docs/minecraft-mixin-targets/"

        private val VERSION_DIFF_GUIDE =
            "See what changed between Minecraft or mod versions" to "docs/minecraft-version-diff/"

        private val KOTLIN_JAR_GUIDE =
            "Read a Kotlin library API from a compiled jar" to "docs/kotlin-jar-api/"

        /** Extra guide links. A command may name more than one. */
        val SEE_ALSO: Map<String, List<Pair<String, String>>> = mapOf(
            "resolve" to listOf("Find which JAR contains a class" to "docs/find-class-in-jar/"),
            "search" to listOf("Find which JAR contains a class" to "docs/find-class-in-jar/"),
            "ls" to listOf("Find which JAR contains a class" to "docs/find-class-in-jar/"),
            "tree" to listOf("Find which JAR contains a class" to "docs/find-class-in-jar/"),
            "hierarchy" to listOf(MIXIN_GUIDE),
            "callers" to listOf(MIXIN_GUIDE),
            "calls" to listOf(MIXIN_GUIDE),
            "signature" to listOf(MIXIN_GUIDE, KOTLIN_JAR_GUIDE),
            "members" to listOf(KOTLIN_JAR_GUIDE),
            "kotlin" to listOf(KOTLIN_JAR_GUIDE),
            "doc" to listOf(
                "Read Javadoc from a jar in the terminal" to "docs/read-javadoc-from-jar/",
                KOTLIN_JAR_GUIDE,
            ),
            "body" to listOf(MIXIN_GUIDE, KOTLIN_JAR_GUIDE),
            "diff" to listOf(VERSION_DIFF_GUIDE),
        )

        /**
         * Sibling command pages, so a reader (and a crawler) can move between the
         * reference pages for one task without going back to the catalogue.
         */
        /**
         * Every top-level command, in the same groups as `docs/COMMANDS.md`. The catalogue
         * page links each one with its `jdx help` summary. A command missing here fails the build.
         */
        val COMMAND_GROUPS: List<Pair<String, List<String>>> = listOf(
            "Read" to listOf("show", "members", "outline", "signature", "doc", "body", "source"),
            "Search and browse" to listOf("search", "resolve", "ls", "tree"),
            "Graph" to listOf(
                "usages", "annotated", "hierarchy", "implementors", "callers", "calls",
                "samples", "literals", "services", "modules", "manifest", "resources",
            ),
            "API diff" to listOf("diff"),
            "Environment" to listOf("version", "upgrade", "doctor", "setup", "ws", "cache", "kotlin", "help", "bench"),
            "Serving" to listOf("daemon", "mcp", "serve", "batch"),
        )

        private fun cmd(name: String) = RelatedTarget.Command(name)
        private fun page(anchor: String, path: String) = RelatedTarget.Page(anchor, path)

        /**
         * Commands reported as unknown to Google. Each list is 2–5 links: related commands
         * (anchor = the `jdx help` summary) and the guide that already demonstrates the command
         * (anchor = that guide's title).
         */
        val RELATED: Map<String, List<RelatedTarget>> = mapOf(
            "batch" to listOf(
                cmd("daemon"),
                cmd("serve"),
                cmd("mcp"),
                page("MCP, daemon, HTTP & batch", "docs/serving/"),
                page("An MCP server for Java and Kotlin bytecode", "docs/java-mcp-server/"),
            ),
            "bench" to listOf(
                page("MCP, daemon, HTTP & batch", "docs/serving/#performance"),
                cmd("daemon"),
                cmd("members"),
                cmd("search"),
            ),
            "daemon" to listOf(
                page("MCP, daemon, HTTP & batch", "docs/serving/"),
                page("An MCP server for Java and Kotlin bytecode", "docs/java-mcp-server/"),
                cmd("batch"),
                cmd("serve"),
                cmd("mcp"),
            ),
            "help" to listOf(
                page("Agent cheat sheet", "docs/reference/cheat-sheet/"),
                page("Use with AI agents", "docs/ai-agents/"),
                cmd("setup"),
                cmd("mcp"),
            ),
            "services" to listOf(
                page("Usages, hierarchy & calls", "docs/navigating-code/#spi--serviceloader-registrations"),
                page("Find which JAR contains a class", "docs/find-class-in-jar/"),
                cmd("modules"),
                cmd("resources"),
            ),
            "upgrade" to listOf(
                cmd("version"),
                page("Installation", "docs/installation/"),
                page("Changelog", "changelog/"),
                cmd("doctor"),
            ),
            "version" to listOf(
                cmd("upgrade"),
                page("Installation", "docs/installation/"),
                cmd("doctor"),
            ),
            "ws" to listOf(
                page("Classpath & workspaces", "docs/classpath/"),
                page("An MCP server for Java and Kotlin bytecode", "docs/java-mcp-server/"),
                cmd("batch"),
                cmd("daemon"),
                cmd("cache"),
            ),
        )

        val RELATED_COMMANDS: Map<String, List<String>> = mapOf(
            "search" to listOf("resolve", "members", "hierarchy"),
            "resolve" to listOf("search", "members", "hierarchy"),
            "members" to listOf("outline", "signature", "search"),
            "outline" to listOf("members", "signature", "body"),
            "signature" to listOf("body", "members", "outline"),
            "body" to listOf("signature", "callers", "calls"),
            "hierarchy" to listOf("members", "callers", "resolve"),
            "callers" to listOf("calls", "body", "hierarchy"),
            "calls" to listOf("callers", "body", "signature"),
        )
    }
}
