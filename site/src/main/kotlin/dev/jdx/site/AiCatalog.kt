package dev.jdx.site

/**
 * `ai-catalog.json` — an Agentic Resource Discovery (ARD 1.0) manifest, linked from every page
 * with `<link rel="ai-catalog">` so agents and registries can discover jdx's MCP server without
 * scraping prose. The MCP entry embeds a server card built from what `jdx mcp` itself answers
 * to `tools/list`, so the advertised tools are always the shipped ones.
 */
object AiCatalog {
    fun render(site: SiteContext, facts: CliFacts, cheatSheetMarkdownPath: String): String {
        val publisher = site.repoSlug().substringBefore('/').lowercase()
        val mcpEntry = linkedMapOf<String, Any?>(
            "identifier" to "urn:air:$publisher:jdx:mcp-server",
            "displayName" to "jdx MCP server",
            "type" to "application/mcp-server-card+json",
            "description" to StructuredData.TAGLINE,
            "data" to linkedMapOf(
                "name" to "jdx",
                "title" to "jdx — an IDE for AI agents",
                "description" to (facts.mcpInstructions ?: StructuredData.TAGLINE),
                "websiteUrl" to site.baseUrl,
                "repository" to linkedMapOf("url" to site.repoUrl, "source" to "github"),
                "transport" to linkedMapOf("type" to "stdio", "command" to "jdx", "args" to listOf("mcp")),
                "install" to site.installCommand,
                "tools" to facts.mcpTools.map { linkedMapOf("name" to it.name, "description" to it.description) },
            ),
            "tags" to StructuredData.KEYWORDS,
            "capabilities" to facts.mcpTools.map { it.name },
            "representativeQueries" to REPRESENTATIVE_QUERIES,
        ).apply {
            site.latestVersion?.let { put("version", it) }
            site.latestReleaseDate?.let { put("updatedAt", it) }
        }
        val skillEntry = linkedMapOf<String, Any?>(
            "identifier" to "urn:air:$publisher:jdx:agent-cheat-sheet",
            "displayName" to "jdx agent cheat sheet",
            "type" to "text/markdown; profile=\"urn:air:agent-skills\"",
            "url" to site.absolute(cheatSheetMarkdownPath),
            "description" to "Paste-ready instructions that teach a coding agent to use the jdx CLI: commands, symbol reference syntax and exit codes.",
            "tags" to listOf("Java", "Kotlin", "JVM", "CLI"),
            "representativeQueries" to listOf(
                "teach my coding agent to inspect java jars",
                "CLAUDE.md instructions for java library lookup",
            ),
        )
        val catalog = linkedMapOf(
            "specVersion" to "1.0",
            "host" to linkedMapOf("displayName" to "jdx", "documentationUrl" to site.absolute("docs/"), "logoUrl" to site.absolute("apple-touch-icon.png")),
            "entries" to listOf(mcpEntry, skillEntry),
        )
        return JsonWriter.encode(catalog) + "\n"
    }

    private val REPRESENTATIVE_QUERIES = listOf(
        "MCP server to inspect Java and Kotlin jars",
        "find usages and call hierarchy in JVM dependencies for an AI agent",
        "show a method body from a Maven dependency without reading the whole file",
        "javap alternative for Claude Code or Cursor",
        "detect breaking API changes when upgrading a Java library",
    )
}
