package dev.jdx.mcp

import dev.jdx.core.rpc.RpcCommand
import dev.jdx.core.rpc.RpcRequest
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The MCP tool table (T-043): one `jdx_<wire>` tool per [RpcCommand].
 *
 * This table is the single source of truth the task demands — schemas are
 * **generated** from it ([inputSchema]), never hand-written twice, and the
 * wire request is built from the same rows ([requestFor]). Param names are the
 * D-058 wire spellings (CLI long-flag names without the dashes, values as
 * text), shared with the daemon dispatch (T-082) and the transparent CLI
 * client (T-042), so the three adapters cannot drift: a renamed param breaks
 * the tier-2 parity tests, not a client at runtime.
 *
 * Descriptions mirror the CLI `--help` texts; defaults are the CLI defaults
 * (absent means default — the dispatch owns them, this table only forwards).
 */
public enum class McpParamKind(public val schemaType: String) {
    STRING("string"),
    INTEGER("integer"),
    BOOLEAN("boolean"),
    ;
}

/**
 * One tool argument: its wire [name], JSON [kind], human [description], and
 * whether MCP clients must supply it. Only `query` is ever required — every
 * other param has a CLI default the dispatch applies when absent.
 */
public data class McpParam(
    public val name: String,
    public val kind: McpParamKind,
    public val description: String,
    public val required: Boolean = false,
)

/**
 * One tool: exactly one [RpcCommand] plus the params it accepts. The MCP tool
 * name is `jdx_<wire>` (the [RpcCommand.wire] contract, T-040).
 */
public data class McpToolDefinition(
    public val command: RpcCommand,
    public val description: String,
    public val params: List<McpParam>,
) {
    /** The MCP tool name: `jdx_<wire>`. */
    public val toolName: String = "jdx_${command.wire}"
}

/** The query every tool takes: the symbol, pattern or glob the command asks about. */
private fun queryParam(): McpParam = McpParam(
    name = "query",
    kind = McpParamKind.STRING,
    description = "The symbol reference, type, pattern or glob the command asks about. " +
        "Quote refs containing # or (). Empty only where the command allows it (ls, tree).",
    required = true,
)

/** An optional query: `ls`/`tree` default it to `*`, transport commands take none. */
private fun optionalQueryParam(): McpParam = McpParam(
    name = "query",
    kind = McpParamKind.STRING,
    description = "Package glob (ls) or artifact glob (tree); empty lists everything.",
)

/** An echoed label: `version`/`doctor`/`health` answer from status, the query rides along. */
private fun labelParam(): McpParam = McpParam(
    name = "query",
    kind = McpParamKind.STRING,
    description = "Optional label echoed back in the envelope (empty is fine).",
)

/**
 * The per-call workspace override. Not forwarded to the wire params — it
 * selects which stored workspace answers, like the daemon's socket (T-041).
 */
private fun workspaceParam(): McpParam = McpParam(
    name = "workspace",
    kind = McpParamKind.STRING,
    description = "Named workspace to answer from (default: the workspace the server was " +
        "started with). Stored workspaces only.",
)

private fun limitParam(default: Int): McpParam = McpParam(
    name = "limit",
    kind = McpParamKind.INTEGER,
    description = "Maximum rows shown (default $default); the rest become a truncation footer.",
)

private fun inParam(): McpParam = McpParam(
    name = "in",
    kind = McpParamKind.STRING,
    description = "Only matches from artifacts whose label matches this glob.",
)

private fun excludeParam(): McpParam = McpParam(
    name = "exclude",
    kind = McpParamKind.STRING,
    description = "Skip matches from artifacts whose label matches this glob.",
)

private fun depthParam(default: Int): McpParam = McpParam(
    name = "depth",
    kind = McpParamKind.INTEGER,
    description = "How many tree levels to walk (default $default).",
)

private fun viewParam(): McpParam = McpParam(
    name = "view",
    kind = McpParamKind.STRING,
    description = "Declaration projection: kotlin (the default, true Kotlin declarations " +
        "from @Metadata) or jvm (the raw JVM projection).",
)

private fun memberFilterParams(): List<McpParam> = listOf(
    McpParam(
        name = "kind", kind = McpParamKind.STRING,
        description = "Member kind to list: method, field, ctor, property or all (default all).",
    ),
    McpParam(
        name = "access", kind = McpParamKind.STRING,
        description = "Visibility to list: public, protected, package, private or all " +
            "(default: public + protected).",
    ),
    McpParam(
        name = "static", kind = McpParamKind.BOOLEAN,
        description = "Only static members (mutually exclusive with instance).",
    ),
    McpParam(
        name = "instance", kind = McpParamKind.BOOLEAN,
        description = "Only instance members (mutually exclusive with static).",
    ),
    McpParam(name = "from", kind = McpParamKind.STRING, description = "Only members inherited from this supertype."),
    McpParam(name = "grep", kind = McpParamKind.STRING, description = "Only members whose name matches this regex."),
    McpParam(
        name = "includeSynthetic", kind = McpParamKind.BOOLEAN,
        description = "Include bridge/synthetic members, hidden by default.",
    ),
    limitParam(50),
    McpParam(
        name = "withDoc", kind = McpParamKind.BOOLEAN,
        description = "Include the first javadoc sentence per member.",
    ),
    McpParam(
        name = "sort", kind = McpParamKind.STRING,
        description = "Row order: kind (the default), name (flat name-first) or declaring.",
    ),
    viewParam(),
)

/** Every v1 wire command as a typed MCP tool, in [RpcCommand] order. */
public val ALL_MCP_TOOLS: List<McpToolDefinition> = listOf(
    McpToolDefinition(
        command = RpcCommand.SHOW,
        description = "Go to declaration: the class card for a type (kind, supertypes, source, provenance).",
        params = listOf(queryParam(), workspaceParam()),
    ),
    McpToolDefinition(
        command = RpcCommand.MEMBERS,
        description = "List the members of a type — the '.' completion equivalent. " +
            "Inherited members are included by default, grouped by declaring type.",
        params = listOf(queryParam(), workspaceParam()) + memberFilterParams() + McpParam(
            name = "declared", kind = McpParamKind.BOOLEAN,
            description = "Only members declared on this exact type (overrides the inherited default).",
        ),
    ),
    McpToolDefinition(
        command = RpcCommand.OUTLINE,
        description = "File Structure: one dense line per member declared on a type (never inherited).",
        params = listOf(queryParam(), workspaceParam()) + memberFilterParams(),
    ),
    McpToolDefinition(
        command = RpcCommand.BODY,
        description = "Open the method: the source body of one member, sliced verbatim with provenance.",
        params = listOf(
            queryParam(), workspaceParam(),
            McpParam(name = "context", kind = McpParamKind.INTEGER, description = "Extra lines around the body (default 0)."),
            McpParam(name = "lineNumbers", kind = McpParamKind.BOOLEAN, description = "Prefix every line with its 1-based number."),
            McpParam(name = "maxLines", kind = McpParamKind.INTEGER, description = "Cap the body slice (default 200)."),
            McpParam(name = "withSignature", kind = McpParamKind.BOOLEAN, description = "Prepend the member signature line."),
            McpParam(name = "withDoc", kind = McpParamKind.BOOLEAN, description = "Append the member javadoc."),
            McpParam(
                name = "engine", kind = McpParamKind.STRING,
                description = "Force a reconstruction engine: vineflower or javap (default: paired sources first).",
            ),
        ),
    ),
    McpToolDefinition(
        command = RpcCommand.SOURCE,
        description = "Open the file: whole source for a type, or a slice of it, with provenance.",
        params = listOf(
            queryParam(), workspaceParam(),
            McpParam(name = "lines", kind = McpParamKind.STRING, description = "Line window A:B (1-based, inclusive)."),
            McpParam(name = "around", kind = McpParamKind.STRING, description = "Center the slice on this member ref."),
            McpParam(name = "context", kind = McpParamKind.INTEGER, description = "Extra lines around the slice (default 0)."),
            McpParam(name = "lineNumbers", kind = McpParamKind.BOOLEAN, description = "Prefix every line with its 1-based number."),
            McpParam(name = "maxLines", kind = McpParamKind.INTEGER, description = "Cap the slice (default 200)."),
            McpParam(
                name = "engine", kind = McpParamKind.STRING,
                description = "Force a reconstruction engine: vineflower or javap (default: paired sources first).",
            ),
        ),
    ),
    McpToolDefinition(
        command = RpcCommand.SIGNATURE,
        description = "Parameter info: one signature line per overload, with real parameter names.",
        params = listOf(
            queryParam(), workspaceParam(),
            McpParam(
                name = "includeSynthetic", kind = McpParamKind.BOOLEAN,
                description = "Include bridge/synthetic members, hidden by default.",
            ),
            limitParam(50),
            viewParam(),
        ),
    ),
    McpToolDefinition(
        command = RpcCommand.DOC,
        description = "Quick documentation for a type or member, falling back to the nearest " +
            "documenting supertype like IntelliJ quick-doc.",
        params = listOf(
            queryParam(), workspaceParam(),
            McpParam(name = "inherited", kind = McpParamKind.BOOLEAN, description = "Allow the supertype fallback (the default; accepted for explicitness)."),
            McpParam(name = "no-inherited", kind = McpParamKind.BOOLEAN, description = "Only docs written on this exact symbol (mutually exclusive with inherited)."),
            McpParam(name = "raw", kind = McpParamKind.BOOLEAN, description = "Raw javadoc text instead of the rendered form."),
            McpParam(name = "maxLines", kind = McpParamKind.INTEGER, description = "Cap the doc text (default 200)."),
        ),
    ),
    McpToolDefinition(
        command = RpcCommand.SEARCH,
        description = "Search everywhere: symbols by glob, regex or fuzzy name, with did-you-mean.",
        params = listOf(
            queryParam(), workspaceParam(),
            McpParam(
                name = "kind", kind = McpParamKind.STRING,
                description = "Symbol kind: all, type, class, interface, enum, record, annotation, " +
                    "object, companion, method, field, package or module (default all).",
            ),
            McpParam(name = "regex", kind = McpParamKind.BOOLEAN, description = "Treat the pattern as a regex."),
            McpParam(name = "fuzzy", kind = McpParamKind.BOOLEAN, description = "Levenshtein fallback when nothing matches."),
            inParam(),
            McpParam(name = "package", kind = McpParamKind.STRING, description = "Only symbols under this package."),
            limitParam(50),
        ),
    ),
    McpToolDefinition(
        command = RpcCommand.RESOLVE,
        description = "What is this symbol? Short-name resolution with copy-pasteable candidates.",
        params = listOf(queryParam(), workspaceParam(), limitParam(50)),
    ),
    McpToolDefinition(
        command = RpcCommand.LS,
        description = "External library browser: packages in the workspace roots.",
        params = listOf(optionalQueryParam(), workspaceParam(), limitParam(50)),
    ),
    McpToolDefinition(
        command = RpcCommand.TREE,
        description = "External library browser: artifacts and their package trees.",
        params = listOf(
            optionalQueryParam(), workspaceParam(),
            McpParam(name = "depth", kind = McpParamKind.INTEGER, description = "Tree depth (default 8)."),
            McpParam(name = "counts", kind = McpParamKind.BOOLEAN, description = "Annotate nodes with class counts."),
            limitParam(50),
        ),
    ),
    McpToolDefinition(
        command = RpcCommand.USAGES,
        description = "Find usages of a type or member across the workspace jars and source dirs.",
        params = listOf(
            queryParam(), workspaceParam(),
            McpParam(
                name = "kind", kind = McpParamKind.STRING,
                description = "Edge kind: all, call, read, write, ref, new, throw or annotation (default all).",
            ),
            inParam(), excludeParam(), limitParam(50),
            McpParam(name = "context", kind = McpParamKind.INTEGER, description = "Snippet lines around each hit (default 0)."),
        ),
    ),
    McpToolDefinition(
        command = RpcCommand.HIERARCHY,
        description = "Type hierarchy: supertypes upward and subtypes downward across the workspace.",
        params = listOf(
            queryParam(), workspaceParam(),
            McpParam(name = "up", kind = McpParamKind.BOOLEAN, description = "Walk supertypes (default: both directions unless down is set)."),
            McpParam(name = "down", kind = McpParamKind.BOOLEAN, description = "Walk subtypes (default: both directions unless up is set)."),
            McpParam(name = "direct", kind = McpParamKind.BOOLEAN, description = "Direct parents/children only."),
            depthParam(Int.MAX_VALUE), inParam(), excludeParam(), limitParam(50),
        ),
    ),
    McpToolDefinition(
        command = RpcCommand.IMPLEMENTORS,
        description = "Who implements this interface or extends this class, transitively.",
        params = listOf(
            queryParam(), workspaceParam(),
            McpParam(name = "direct", kind = McpParamKind.BOOLEAN, description = "Direct implementors only."),
            depthParam(Int.MAX_VALUE), inParam(), excludeParam(), limitParam(50),
        ),
    ),
    McpToolDefinition(
        command = RpcCommand.CALLERS,
        description = "Call hierarchy in: who calls this method, as a depth-bounded tree.",
        params = listOf(queryParam(), workspaceParam(), depthParam(1), inParam(), excludeParam(), limitParam(50)),
    ),
    McpToolDefinition(
        command = RpcCommand.CALLS,
        description = "Call hierarchy out: what this method calls, as a depth-bounded tree.",
        params = listOf(
            queryParam(), workspaceParam(), depthParam(1), inParam(), excludeParam(),
            McpParam(name = "externalOnly", kind = McpParamKind.BOOLEAN, description = "Prune callees in the target's own artifact (dependency view)."),
            limitParam(50),
        ),
    ),
    McpToolDefinition(
        command = RpcCommand.SAMPLES,
        description = "How is this symbol really used? Ranked call sites with source snippets as evidence.",
        params = listOf(
            queryParam(), workspaceParam(), limitParam(3), inParam(), excludeParam(),
            McpParam(name = "preferSources", kind = McpParamKind.BOOLEAN, description = "Rank callers from sources-paired artifacts first."),
        ),
    ),
    McpToolDefinition(
        command = RpcCommand.VERSION,
        description = "The jdx version this server was built as.",
        params = listOf(labelParam(), workspaceParam()),
    ),
    McpToolDefinition(
        command = RpcCommand.DOCTOR,
        description = "Environment report. The server refuses this over MCP — it describes the " +
            "server's machine, not the client's — run `jdx doctor` in a shell instead.",
        params = listOf(labelParam(), workspaceParam()),
    ),
    McpToolDefinition(
        command = RpcCommand.HEALTH,
        description = "Liveness probe: uptime, workspace served and queries answered.",
        params = listOf(labelParam(), workspaceParam()),
    ),
)

/** Looks up a tool by its `jdx_<wire>` name, or returns `null`. Never throws. */
public fun toolForName(name: String): McpToolDefinition? = ALL_MCP_TOOLS.firstOrNull { it.toolName == name }

/**
 * Generates the MCP input schema for this tool from its [McpParam] rows: one
 * `{"type":…, "description":…}` property per param, `query` required exactly
 * when the command needs a subject. Deterministic — equal tables give equal
 * schemas, so the tool listing is stable across restarts.
 */
public fun McpToolDefinition.inputSchema(): ToolSchema = ToolSchema(
    properties = buildJsonObject {
        for (param in params) {
            put(param.name, buildJsonObject {
                put("type", param.kind.schemaType)
                put("description", param.description)
            })
        }
    },
    required = params.filter { it.required }.map { it.name },
)

/**
 * Builds the wire [RpcRequest] for this tool from already-scalarised params.
 * `query` and `workspace` are consumed here (subject and root selection);
 * every other entry forwards verbatim as a wire param (D-058). Unknown entries
 * are ignored — the dispatch owns validation and answers exit 3 naming the
 * real problem. Never throws.
 */
public fun McpToolDefinition.requestFor(query: String, params: Map<String, String>): RpcRequest {
    val wireParams = params.filterKeys { key -> key != "query" && key != "workspace" && hasParam(key) }
    return RpcRequest(command, query, wireParams)
}

/** Whether this tool declares a param of this name (the schema and the wire read one table). */
public fun McpToolDefinition.hasParam(name: String): Boolean = params.any { it.name == name }

/**
 * The JSON value spelling of one scalar MCP argument: strings as-is, numbers
 * and booleans canonicalised to their text (the T-040 leniency, mirroring
 * [RpcRequest.decode]). Returns `null` — read as absent — for JSON `null`, so
 * clients may explicitly null an optional param. Arrays and objects have no
 * single text meaning; callers report those as exit-3 usage errors.
 */
public fun scalarText(value: kotlinx.serialization.json.JsonElement): String? = when (value) {
    is kotlinx.serialization.json.JsonNull -> null
    is kotlinx.serialization.json.JsonPrimitive ->
        if (value.isString) value.content else value.toString()
    else -> "__structured__"
}

/** Splits raw tool arguments into a query, a workspace override and wire params. Never throws. */
public fun splitArgs(raw: JsonObject): SplitArgs = try {
    var query = ""
    var workspace: String? = null
    val params = LinkedHashMap<String, String>()
    val structured = mutableListOf<String>()
    for ((key, value) in raw) {
        val text = scalarText(value)
        when {
            key == "query" && text != null -> query = text
            key == "workspace" && text != null -> workspace = text
            text == null -> Unit // explicit null: absent
            text == "__structured__" -> structured.add(key)
            else -> params[key] = text
        }
    }
    SplitArgs(query, workspace, params, structured)
} catch (_: Exception) {
    SplitArgs("", null, emptyMap(), emptyList())
}

/** One call's arguments after [splitArgs]: subject, root override, wire params, hostile keys. */
public data class SplitArgs(
    public val query: String,
    public val workspace: String?,
    public val params: Map<String, String>,
    /** Keys whose value was an array or object: exit-3 usage errors naming the param. */
    public val structured: List<String>,
)
