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

        RELATED_GUIDES[name]?.let { (guideTitle, guidePath) ->
            section(html, markdown, "Learn more", "learn-more")
            html.append("<p>Guide: <a href=\"${site.rootRelative(guidePath)}\">${Html.text(guideTitle)}</a> · ")
            html.append("<a href=\"${site.rootRelative("docs/reference/")}\">All commands</a></p>\n")
            markdown.append("Guide: [$guideTitle](${site.absolute(guidePath)}) · [All commands](${site.absolute("docs/reference/")})\n")
        }

        val path = commandPath(name)
        val headings = buildList {
            add(HeadingInfo(2, "Usage", "usage"))
            if (EXAMPLES.containsKey(name)) add(HeadingInfo(2, "Example", "example"))
            if (help.subcommands.isNotEmpty()) add(HeadingInfo(2, "Subcommands", "subcommands"))
            if (help.options.isNotEmpty()) add(HeadingInfo(2, "Options", "options"))
            if (help.arguments.isNotEmpty()) add(HeadingInfo(2, "Arguments", "arguments"))
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
                    html.append("<tr><td><code>${Html.text(parameter.name)}</code>$required</td><td>${Html.text(parameter.type)}</td><td>${Html.inlineCode(parameter.description)}</td></tr>")
                    markdown.append("| `${parameter.name}`${if (parameter.required) " (required)" else ""} | ${parameter.type} | ${cell(parameter.description)} |\n")
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
            "hierarchy" to "jdx hierarchy com.google.gson.JsonElement --coord $GSON",
            "implementors" to "jdx implementors com.google.gson.TypeAdapterFactory --direct --limit 10 --coord $GSON",
            "callers" to "jdx callers 'com.google.gson.Gson#getAdapter(java.lang.Class)' --coord $GSON",
            "calls" to "jdx calls 'com.google.gson.Gson#toJson(java.lang.Object)' --coord $GSON",
            "samples" to "jdx samples 'com.google.gson.JsonParser#parseString(String)' --coord $GSON",
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
            listOf("usages", "hierarchy", "implementors", "callers", "calls", "samples").forEach { put(it, navigating) }
            listOf("daemon", "serve", "mcp", "batch", "bench").forEach { put(it, serving) }
            listOf("ws", "cache").forEach { put(it, "Classpath & workspaces" to "docs/classpath/") }
            listOf("setup", "help").forEach { put(it, "Use with AI agents" to "docs/ai-agents/") }
            listOf("version", "upgrade", "doctor").forEach { put(it, "Installation" to "docs/installation/") }
            put("diff", "API diff for upgrades" to "docs/api-diff/")
        }
    }
}
