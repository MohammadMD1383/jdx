package dev.jdx.site

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** One option row of Clikt help: every spelling it accepts plus its help text. */
data class CliOption(val signature: String, val names: List<String>, val description: String)

/** Parsed `jdx <path> --help`. */
data class CommandHelp(
    /** e.g. `["members"]` or `["ws", "create"]`. */
    val path: List<String>,
    val usage: String,
    val description: String,
    val options: List<CliOption>,
    val arguments: List<Pair<String, String>>,
    val subcommands: List<Pair<String, String>>,
    val raw: String,
) {
    val name: String get() = path.joinToString(" ")
    val flagNames: Set<String> get() = options.flatMap { it.names }.toSet()
}

/** One `jdx help --json` row: the IDE equivalent and the canonical example of a command. */
data class HelpRow(val command: String, val summary: String, val example: String)

/** One MCP tool as `jdx mcp` advertises it over `tools/list`. */
data class McpTool(val name: String, val description: String, val parameters: List<McpParameter>)

data class McpParameter(val name: String, val type: String, val description: String, val required: Boolean)

/**
 * Everything the site states about the CLI, read from the real binary — never typed by hand.
 * If a command, flag or MCP tool appears on the site, it came from here.
 */
class CliFacts(
    val rootHelp: CommandHelp,
    val commands: List<CommandHelp>,
    val helpRows: List<HelpRow>,
    val agentCheatSheet: String,
    val mcpTools: List<McpTool>,
    val mcpInstructions: String?,
) {
    /** Top-level command names, in `jdx --help` order. */
    val commandNames: List<String> get() = rootHelp.subcommands.map { it.first }

    fun command(path: List<String>): CommandHelp? = commands.firstOrNull { it.path == path }

    /** Flags accepted anywhere (before or after the subcommand). */
    val globalFlags: Set<String> get() = rootHelp.flagNames

    fun helpRow(commandName: String): HelpRow? =
        helpRows.firstOrNull { it.command.substringBefore(' ') == commandName }

    /** `--agent a|b|c` values from `jdx setup --help`. */
    val setupAgents: List<String>
        get() {
            val description = command(listOf("setup"))?.description.orEmpty()
            val match = Regex("--agent ([a-z0-9|-]+)").find(description)
                ?: throw SiteBuildException("`jdx setup --help` no longer lists `--agent a|b|c`; update CliFacts.setupAgents")
            return match.groupValues[1].split('|')
        }

    companion object {
        fun load(runner: JdxRunner): CliFacts {
            val root = help(runner, emptyList())
            val commands = mutableListOf<CommandHelp>()
            for ((name, _) in root.subcommands) {
                val command = help(runner, listOf(name))
                commands += command
                for ((sub, _) in command.subcommands) commands += help(runner, listOf(name, sub))
            }
            val helpJson = runOk(runner, listOf("help", "--json"))
            val rows = Json.parseToJsonElement(helpJson).jsonObject["result"]!!.jsonObject["rows"]!!.jsonArray.map { row ->
                val fields = row.jsonObject
                HelpRow(
                    command = fields["command"]!!.jsonPrimitive.content,
                    summary = fields["summary"]!!.jsonPrimitive.content,
                    example = fields["example"]?.jsonPrimitive?.content.orEmpty(),
                )
            }
            val cheatSheet = runOk(runner, listOf("help", "--agent"))
            val (tools, instructions) = mcpTools(runner)
            return CliFacts(root, commands, rows, cheatSheet, tools, instructions)
        }

        private fun runOk(runner: JdxRunner, arguments: List<String>, stdin: String? = null): String {
            val result = runner.run(arguments, stdin)
            if (result.exitCode != 0) {
                throw SiteBuildException("jdx ${arguments.joinToString(" ")} exited ${result.exitCode}: ${result.stderr}")
            }
            return result.stdout
        }

        private fun help(runner: JdxRunner, path: List<String>): CommandHelp =
            parseHelp(path, runOk(runner, path + "--help"))

        /** Parses Clikt's help layout (`Usage:`, description, `Options:`, `Arguments:`, `Commands:`). */
        fun parseHelp(path: List<String>, raw: String): CommandHelp {
            val lines = raw.lines()
            val usage = lines.firstOrNull { it.startsWith("Usage: ") }?.removePrefix("Usage: ")?.trim()
                ?: throw SiteBuildException("`jdx ${path.joinToString(" ")} --help` has no Usage line")
            val sections = mutableMapOf<String, MutableList<String>>()
            val description = StringBuilder()
            var current: String? = null
            for (line in lines.drop(lines.indexOfFirst { it.startsWith("Usage: ") } + 1)) {
                when {
                    line in setOf("Options:", "Arguments:", "Commands:") -> current = line.removeSuffix(":")
                    current == null -> if (line.isNotBlank()) description.append(line.trim()).append(' ')
                    line.isNotBlank() -> sections.getOrPut(current) { mutableListOf() } += line
                }
            }
            val row = Regex("""^\s{2}(\S.*?)\s{2,}(\S.*)$""")
            fun rows(section: String): List<Pair<String, String>> = sections[section].orEmpty().mapNotNull { line ->
                row.find(line)?.let { it.groupValues[1].trim() to it.groupValues[2].trim() }
            }
            val options = rows("Options").map { (signature, text) ->
                val names = Regex("""(?<![\w-])(--?[A-Za-z][\w-]*)""").findAll(signature.substringBefore('=').substringBefore(" <"))
                    .map { it.groupValues[1] }.toList()
                CliOption(signature, names, text)
            }
            return CommandHelp(
                path = path,
                usage = usage,
                description = description.toString().trim(),
                options = options,
                arguments = rows("Arguments"),
                subcommands = rows("Commands"),
                raw = raw.trimEnd(),
            )
        }

        /** Speaks just enough MCP (initialize + tools/list) to list the tools `jdx mcp` serves. */
        private fun mcpTools(runner: JdxRunner): Pair<List<McpTool>, String?> {
            val requests = listOf(
                """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"jdx-site","version":"1"}}}""",
                """{"jsonrpc":"2.0","method":"notifications/initialized"}""",
                """{"jsonrpc":"2.0","id":2,"method":"tools/list"}""",
            ).joinToString("\n", postfix = "\n")
            val result = runner.run(listOf("mcp"), requests)
            val responses = result.stdout.lines().filter { it.isNotBlank() }.mapNotNull { line ->
                runCatching { Json.parseToJsonElement(line).jsonObject }.getOrNull()
            }
            val toolsResponse = responses.firstOrNull { it["id"]?.jsonPrimitive?.content == "2" }
                ?: throw SiteBuildException("`jdx mcp` did not answer tools/list (exit ${result.exitCode}): ${result.stderr}")
            val instructions = responses.firstOrNull { it["id"]?.jsonPrimitive?.content == "1" }
                ?.get("result")?.jsonObject?.get("instructions")?.jsonPrimitive?.content
            val tools = (toolsResponse["result"]!!.jsonObject["tools"] as JsonArray).map { element ->
                val tool = element.jsonObject
                val schema = tool["inputSchema"]?.jsonObject
                val required = schema?.get("required")?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty().toSet()
                val properties = schema?.get("properties") as JsonObject?
                McpTool(
                    name = tool["name"]!!.jsonPrimitive.content,
                    description = tool["description"]?.jsonPrimitive?.content.orEmpty(),
                    parameters = properties.orEmpty().map { (name, value) ->
                        val property = value.jsonObject
                        McpParameter(
                            name = name,
                            type = property["type"]?.jsonPrimitive?.content ?: "any",
                            description = property["description"]?.jsonPrimitive?.content.orEmpty(),
                            required = name in required,
                        )
                    },
                )
            }
            return tools to instructions
        }
    }
}
