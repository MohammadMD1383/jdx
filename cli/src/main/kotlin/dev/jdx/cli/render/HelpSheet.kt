package dev.jdx.cli.render

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.encodeToJsonElement

/**
 * `jdx help [--agent]` cheat sheets (T-049, PROPOSAL.md §3.8).
 *
 * One metadata table ([HELP_ROWS]) feeds both renderers so the human sheet
 * and the paste-ready agent block cannot drift: `renderHelpText(agent =
 * false)` is the human layout, `agent = true` the compact block designed to
 * be dropped into a `CLAUDE.md`, system prompt or MCP tool description.
 * Pure strings: deterministic, no timestamps, no paths (CLAUDE.md §2).
 */
public data class HelpRow(val command: String, val summary: String, val example: String? = null)

/** Every command the sheet teaches, in stable presentation order. */
public val HELP_ROWS: List<HelpRow> = listOf(
    HelpRow("show <type>", "Go to declaration", "jdx show com.google.gson.Gson"),
    HelpRow("members <type> --inherited", "Code completion after `.`", "jdx members com.google.gson.Gson --inherited"),
    HelpRow("outline <type>", "File structure", "jdx outline com.google.gson.Gson"),
    HelpRow("signature <member>", "Parameter info", "jdx signature 'Gson#toJson(Object)'"),
    HelpRow("doc <symbol>", "Quick documentation", "jdx doc 'Gson#toJson(Object)'"),
    HelpRow("body <member>", "Open the method", "jdx body 'Gson#toJson(Object)'"),
    HelpRow("source <type>", "Open the file / decompile", "jdx source com.google.gson.Gson"),
    HelpRow("search <pattern>", "Search everywhere", "jdx search '*Http*Client' --kind class"),
    HelpRow("resolve <name>", "What is this symbol?", "jdx resolve Gson"),
    HelpRow("ls [package]", "External library browser (packages/types)", "jdx ls com.google.gson"),
    HelpRow("tree [artifact]", "External library browser (artifacts)", "jdx tree gson"),
    HelpRow("usages <symbol>", "Find usages", "jdx usages com.google.gson.TypeAdapter"),
    HelpRow("hierarchy <type>", "Type hierarchy", "jdx hierarchy java.util.Map"),
    HelpRow("implementors <type>", "Who implements this?", "jdx implementors java.util.Map"),
    HelpRow("callers <member>", "Call hierarchy (in)", "jdx callers 'Gson#toJson(Object)'"),
    HelpRow("calls <member>", "Call hierarchy (out)", "jdx calls 'Gson#toJson(Object)'"),
    HelpRow("samples <symbol>", "Real call sites as usage examples", "jdx samples 'Gson#toJson(Object)'"),
    HelpRow("version", "Print the jdx version", "jdx version"),
    HelpRow("doctor", "Self-diagnosis (JDK, index, daemon, workspace)", "jdx doctor"),
    HelpRow("ws create|list|info|remove|add|use", "Project and library configuration", "jdx ws list"),
    HelpRow("cache info|gc|clear", "Index and cache maintenance", "jdx cache info"),
    HelpRow("daemon start|stop|status|restart", "Warm background JVM (5-min idle shutdown)", "jdx daemon status"),
    HelpRow("serve [--port 7070]", "Local HTTP/JSON API", "jdx serve"),
    HelpRow("mcp", "MCP stdio server (typed jdx_* tools)", "jdx mcp"),
    HelpRow("batch", "Many queries, one process (NDJSON in/out)", "jdx batch --json < queries.ndjson"),
    HelpRow("help [--agent]", "This cheat sheet", "jdx help --agent"),
)

@Serializable
public data class HelpRowJson(val command: String, val summary: String, val example: String? = null)

@Serializable
public data class HelpResult(val agent: Boolean, val rows: List<HelpRowJson>)

/** Builds the [HelpResult] for either sheet flavour from [HELP_ROWS]. */
public fun helpResult(agent: Boolean): HelpResult =
    HelpResult(agent, HELP_ROWS.map { HelpRowJson(it.command, it.summary, it.example) })

/** `jdx help --json`. */
public fun HelpResult.toJson(): String =
    envelopeJson(command = "help", ok = true, result = JdxJson.encodeToJsonElement(this))

/** `jdx help` text: [HelpResult.renderText] over [helpResult]. */
public fun HelpResult.renderText(): String = renderHelpText(agent)

private const val REF_LINE = "refs: com.example.Outer#method(Type), Outer#method, 'Owner#m(Object)' (quote: # and () are shell-hostile)"
private const val EXIT_LINE = "exit codes: 0 ok · 1 not found · 2 ambiguous (retry with a listed candidate) · 3 usage · 4 no workspace · 5 artifact error · 6 internal"
private const val JSON_LINE = "add --json to any command for the structured envelope (same information)"

/** Renders either sheet flavour. Total: never throws on any [agent] value. */
public fun renderHelpText(agent: Boolean): String {
    val rows = HELP_ROWS
    return if (!agent) {
        buildString {
            appendLine("jdx help")
            appendLine()
            appendLine("commands:")
            for (row in rows) {
                append("  jdx ").append(row.command).append(" — ").appendLine(row.summary)
            }
            appendLine()
            appendLine(REF_LINE)
            appendLine(EXIT_LINE)
            appendLine(JSON_LINE)
            append("full spec: docs/PROPOSAL.md")
        }
    } else {
        buildString {
            appendLine("jdx agent cheat sheet (paste into CLAUDE.md / system prompt):")
            for (row in rows) {
                append("jdx ").append(row.command).append(" — ").appendLine(row.summary)
            }
            appendLine(REF_LINE)
            appendLine(EXIT_LINE)
            append(JSON_LINE)
        }
    }
}
