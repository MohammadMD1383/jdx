package dev.jdx.mcp

import dev.jdx.core.rpc.RpcCommand
import dev.jdx.core.rpc.RpcRequest
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
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
 * Descriptions state what the tool returns and which sibling answers the
 * neighbouring question. Defaults are the CLI defaults (absent means default —
 * the dispatch owns them; [defaultJson] only documents them). Enum values are
 * the spellings [dev.jdx.index.service.dispatch] accepts.
 *
 * Annotations are hints, not enforcement. Every tool is read-only and
 * idempotent. [McpToolDefinition.openWorld] stays false: no tool declares
 * `fetch`, and [requestFor] drops unknown keys, so a coordinate on `jdx_diff`
 * resolves from the local caches only. The daemon/MCP path never downloads
 * (issue #18).
 */
public enum class McpParamKind(public val schemaType: String) {
    STRING("string"),
    INTEGER("integer"),
    BOOLEAN("boolean"),
    ;
}

/**
 * One tool argument: its wire [name], JSON [kind], human [description], and
 * whether MCP clients must supply it. Only `query` (and `diff`'s `new`) is
 * ever required — every other param has a CLI default the dispatch applies
 * when absent.
 *
 * [enumValues], [defaultJson] and [examples] are copied into the generated
 * JSON Schema. An empty enum or example list is omitted. A null [defaultJson]
 * is omitted: absence is the default, and some defaults are not a single
 * legal value (`access` omitted means public and protected together).
 */
public data class McpParam(
    public val name: String,
    public val kind: McpParamKind,
    public val description: String,
    public val required: Boolean = false,
    public val enumValues: List<String> = emptyList(),
    public val defaultJson: JsonPrimitive? = null,
    public val examples: List<String> = emptyList(),
)

/**
 * One tool: exactly one [RpcCommand] plus the params it accepts. The MCP tool
 * name is `jdx_<wire>` (the [RpcCommand.wire] contract, T-040). [title] is the
 * display name; it is longer than [toolName] and does not rename the tool.
 */
public data class McpToolDefinition(
    public val command: RpcCommand,
    public val description: String,
    public val params: List<McpParam>,
    public val title: String,
    /**
     * MCP `openWorldHint`. False for every current tool: this server does not
     * forward `fetch` and does not contact the network while answering.
     */
    public val openWorld: Boolean = false,
) {
    /** The MCP tool name: `jdx_<wire>`. */
    public val toolName: String = "jdx_${command.wire}"

    /**
     * Hints for MCP clients. `destructiveHint` is false because [readOnlyHint]
     * is true — the spec's default of true would describe a tool that writes.
     * `idempotentHint` is true because repeating a call does not change the
     * jars, the workspace, or the answer apart from `jdx_health`'s counter.
     */
    public fun toolAnnotations(): ToolAnnotations = ToolAnnotations(
        readOnlyHint = true,
        destructiveHint = false,
        idempotentHint = true,
        openWorldHint = openWorld,
    )
}

/** Collapses a wrapped description into the single paragraph MCP clients show. */
private fun paragraph(text: String): String = text.trimIndent().trim().replace(Regex("\\s+"), " ")

private val TYPE_EXAMPLES: List<String> = listOf(
    "com.google.gson.Gson",
    "java.util.Map",
    "java.util.Map\$Entry",
)

private val MEMBER_EXAMPLES: List<String> = listOf(
    "com.google.gson.Gson#toJson(java.lang.Object)",
    "com.google.gson.Gson#toJson",
    "com.google.gson.Gson#<init>(...)",
)

private val SYMBOL_EXAMPLES: List<String> = listOf(
    "com.google.gson.Gson",
    "com.google.gson.Gson#toJson(java.lang.Object)",
    "com.google.gson.Gson#toJson",
)

private const val TYPE_QUERY: String =
    "Type reference. Fully qualified ('com.google.gson.Gson'), nested " +
        "('java.util.Map\$Entry' — '\$' separates nesting; 'Map.Entry' is also accepted), " +
        "or a short name ('Gson', 'Map'). A member reference is not a type. " +
        "An ambiguous short name lists every candidate instead of guessing."

private const val MEMBER_QUERY: String =
    "Member reference, Javadoc-style. One overload: " +
        "'com.google.gson.Gson#toJson(java.lang.Object)'. Every overload: " +
        "'com.google.gson.Gson#toJson'. Simple parameter names ('toJson(Object)') and an " +
        "exact JVM descriptor ('toJson(Ljava/lang/Object;)Ljava/lang/String;') are accepted. " +
        "Constructor: 'com.google.gson.Gson#<init>(...)'. '#', '.' or '::' separates the member; " +
        "fields need '#' or '::'. Nested types use '\$' ('java.util.Map\$Entry'). " +
        "An ambiguous ref lists every candidate instead of guessing."

private const val SYMBOL_QUERY: String =
    "Type or member reference. Type: 'com.google.gson.Gson'. One overload: " +
        "'com.google.gson.Gson#toJson(java.lang.Object)'. Every overload: " +
        "'com.google.gson.Gson#toJson'. Nested types use '\$' ('java.util.Map\$Entry'). " +
        "Short names ('Gson', 'Gson#toJson') are accepted. " +
        "An ambiguous ref lists every candidate instead of guessing."

private fun queryParam(
    description: String,
    required: Boolean = true,
    examples: List<String> = emptyList(),
): McpParam = McpParam(
    name = "query",
    kind = McpParamKind.STRING,
    description = description,
    required = required,
    examples = examples,
)

/** The per-call workspace override. Not forwarded to the wire params. */
private fun workspaceParam(): McpParam = McpParam(
    name = "workspace",
    kind = McpParamKind.STRING,
    description = "Named stored workspace to answer from (see `jdx ws`). " +
        "Default is the workspace this server was started with. Stored workspaces only: " +
        "this server does not auto-discover a project and does not take jar paths or " +
        "Maven coordinates on the call.",
    examples = listOf("default"),
)

private fun stringParam(
    name: String,
    description: String,
    required: Boolean = false,
    examples: List<String> = emptyList(),
): McpParam = McpParam(
    name = name,
    kind = McpParamKind.STRING,
    description = description,
    required = required,
    examples = examples,
)

private fun enumParam(
    name: String,
    description: String,
    values: List<String>,
    default: String? = null,
): McpParam = McpParam(
    name = name,
    kind = McpParamKind.STRING,
    description = description,
    enumValues = values,
    defaultJson = default?.let(::JsonPrimitive),
)

private fun flagParam(name: String, description: String, default: Boolean = false): McpParam = McpParam(
    name = name,
    kind = McpParamKind.BOOLEAN,
    description = description,
    defaultJson = JsonPrimitive(default),
)

private fun intParam(name: String, description: String, default: Int? = null): McpParam = McpParam(
    name = name,
    kind = McpParamKind.INTEGER,
    description = description,
    defaultJson = default?.let(::JsonPrimitive),
)

private fun limitParam(default: Int): McpParam = intParam(
    name = "limit",
    description = "Maximum rows shown (default $default). The rest become a truncation footer.",
    default = default,
)

private fun inParam(): McpParam = stringParam(
    name = "in",
    description = "Only matches from artifacts whose label matches this glob " +
        "(jar file name, JDK module, or source-directory name).",
    examples = listOf("gson*"),
)

private fun excludeParam(): McpParam = stringParam(
    name = "exclude",
    description = "Skip matches from artifacts whose label matches this glob " +
        "(jar file name, JDK module, or source-directory name).",
    examples = listOf("*-sources.jar"),
)

private fun viewParam(): McpParam = enumParam(
    name = "view",
    description = "Declaration projection. kotlin (default) is the true Kotlin declaration " +
        "from @Metadata: suspend, properties, nullability, default arguments. " +
        "jvm is the raw JVM projection: getters, Continuation parameters, mangled names.",
    values = listOf("kotlin", "jvm"),
    default = "kotlin",
)

private fun memberFilterParams(): List<McpParam> = listOf(
    enumParam(
        name = "kind",
        description = "Member kind to list.",
        values = listOf("all", "method", "field", "ctor", "property"),
        default = "all",
    ),
    enumParam(
        name = "access",
        description = "Visibility to list. Omit for the default, which is public and protected " +
            "together — that combination is not itself one of these values. " +
            "'public' means public only; 'all' means every visibility.",
        values = listOf("public", "protected", "package", "private", "all"),
    ),
    flagParam(
        name = "static",
        description = "Only static members. Mutually exclusive with instance; setting both is error code 3.",
    ),
    flagParam(
        name = "instance",
        description = "Only instance members. Mutually exclusive with static; setting both is error code 3.",
    ),
    stringParam(
        name = "from",
        description = "Only members inherited from this supertype (a type reference, for example 'java.lang.Object').",
        examples = listOf("java.lang.Object"),
    ),
    stringParam(
        name = "grep",
        description = "Only members whose name matches this Java regex (for example '^get'). " +
            "An invalid pattern is error code 3.",
        examples = listOf("^get"),
    ),
    flagParam(
        name = "includeSynthetic",
        description = "Include bridge and synthetic members. Hidden when omitted or false.",
    ),
    limitParam(50),
    flagParam(
        name = "withDoc",
        description = "Include the first javadoc sentence per member.",
    ),
    enumParam(
        name = "sort",
        description = "Row order. kind groups by member kind (the default). name is a flat name order. " +
            "declaring groups by the type that declares the member.",
        values = listOf("kind", "name", "declaring"),
        default = "kind",
    ),
    viewParam(),
)

private fun engineParam(): McpParam = enumParam(
    name = "engine",
    description = "Reconstruction engine. Omit to prefer paired sources, then Vineflower, then javap. " +
        "A Vineflower stub that only says it could not decompile falls through to javap. " +
        "vineflower forces a decompilation even when sources are paired. javap prints raw bytecode. " +
        "Decompiled and disassembled text is marked ⚠ reconstructed.",
    values = listOf("vineflower", "javap"),
)

private fun regexParam(): McpParam = flagParam(
    name = "regex",
    description = "Treat query as a Java regex. Omit or false for a substring or glob, depending on the tool.",
)

private fun ignoreCaseParam(): McpParam = flagParam(
    name = "ignoreCase",
    description = "Case-insensitive substring match. Ignored when regex is true; put (?i) in the pattern there.",
)

/** Every v1 wire command as a typed MCP tool, in [RpcCommand] order. */
public val ALL_MCP_TOOLS: List<McpToolDefinition> = listOf(
    McpToolDefinition(
        command = RpcCommand.SHOW,
        title = "Show a type's declaration card",
        description = paragraph(
            """
            Returns the class card for one type: kind, modifiers, supertypes, member counts,
            provenance, and the next command to run.
            Use it to see what a type is. Use jdx_members to list what you can call (inherited
            members included) and jdx_outline for members declared on this type only. A member
            reference is error code 3; use jdx_signature, jdx_doc, or jdx_body for a member.
            An ambiguous short name is not guessed: the call is error code 2 and lists every candidate.
            Structure comes from bytecode parsed with ASM. Never loads or executes the inspected class.
            """,
        ),
        params = listOf(
            queryParam(TYPE_QUERY, examples = TYPE_EXAMPLES),
            workspaceParam(),
        ),
    ),
    McpToolDefinition(
        command = RpcCommand.MEMBERS,
        title = "List the members of a type",
        description = paragraph(
            """
            Returns the members you can call on a type. Inherited members are included by default
            and grouped by the declaring type; java.lang.Object collapses to one line unless from
            names it. This is the completion list.
            Use it for the callable surface. Use jdx_outline for one dense line per member declared
            on this type only, jdx_signature for one member's parameters, and jdx_show for the type card.
            declared true limits the list to members declared on this exact type.
            An ambiguous short name is error code 2 and lists every candidate.
            Kotlin types render from @Metadata unless view is jvm. Parses with ASM and never loads
            or executes the inspected class.
            """,
        ),
        params = listOf(queryParam(TYPE_QUERY, examples = TYPE_EXAMPLES), workspaceParam()) +
            memberFilterParams() + flagParam(
            name = "declared",
            description = "Only members declared on this exact type. Omit or false to include inherited " +
                "members, which is the default.",
        ),
    ),
    McpToolDefinition(
        command = RpcCommand.OUTLINE,
        title = "Outline members declared on a type",
        description = paragraph(
            """
            Returns one dense line per member declared on this exact type. Inherited members are
            never included. This is the file-structure view.
            Use it to scan what the type itself declares. Use jdx_members when you also need
            inherited members, and jdx_signature or jdx_body to open one member.
            An ambiguous short name is error code 2 and lists every candidate.
            Parses with ASM and never loads or executes the inspected class.
            """,
        ),
        params = listOf(queryParam(TYPE_QUERY, examples = TYPE_EXAMPLES), workspaceParam()) +
            memberFilterParams(),
    ),
    McpToolDefinition(
        command = RpcCommand.BODY,
        title = "Show one member's source body",
        description = paragraph(
            """
            Returns one member's source body, sliced to that member, plus a provenance line naming
            the artifact and file. context adds lines around the slice; maxLines caps it (default 200).
            Use it to read an implementation. Use jdx_signature for the declaration only, jdx_doc for
            javadoc, and jdx_source for the whole file or an arbitrary line window.
            Paired sources are sliced verbatim. Without them, or when engine is set, the body is
            decompiled and the provenance line is marked ⚠ reconstructed.
            An ambiguous ref is error code 2 and lists every candidate. Overload choice is decided
            from bytecode before sources or a decompiler run.
            Parses with ASM and never loads or executes the inspected class.
            """,
        ),
        params = listOf(
            queryParam(MEMBER_QUERY, examples = MEMBER_EXAMPLES),
            workspaceParam(),
            intParam("context", "Extra source lines around the body. Default 0.", default = 0),
            flagParam("lineNumbers", "Prefix every shown line with its 1-based number."),
            intParam("maxLines", "Maximum lines of the body slice. Default 200.", default = 200),
            flagParam("withSignature", "Prepend the member signature line."),
            flagParam("withDoc", "Append the member javadoc."),
            engineParam(),
        ),
    ),
    McpToolDefinition(
        command = RpcCommand.SOURCE,
        title = "Show a type's source file",
        description = paragraph(
            """
            Returns a type's source file, or a slice of it, plus provenance. lines is a 1-based
            inclusive window A:B. around centers the slice on a member; context widens that window.
            maxLines caps the slice (default 200).
            Use it when one member is not enough. Use jdx_body for a single member body, jdx_outline
            for the member list, and jdx_doc for javadoc only.
            Paired sources are served verbatim. Without them, or when engine is set, the file is
            decompiled and the provenance line is marked ⚠ reconstructed. lines and around together
            are error code 3.
            An ambiguous type is error code 2 and lists every candidate.
            Parses with ASM and never loads or executes the inspected class.
            """,
        ),
        params = listOf(
            queryParam(TYPE_QUERY, examples = TYPE_EXAMPLES),
            workspaceParam(),
            stringParam(
                name = "lines",
                description = "Line window A:B, 1-based and inclusive, with 1 <= A <= B " +
                    "(for example '120:160'). Mutually exclusive with around.",
                examples = listOf("120:160"),
            ),
            stringParam(
                name = "around",
                description = "Center the slice on this member reference. Mutually exclusive with lines.",
                examples = listOf("com.google.gson.Gson#toJson(java.lang.Object)"),
            ),
            intParam(
                "context",
                "Extra source lines on each side of the around member. Default 0. Applies with around.",
                default = 0,
            ),
            flagParam("lineNumbers", "Prefix every shown line with its 1-based number."),
            intParam("maxLines", "Maximum lines of the slice. Default 200.", default = 200),
            engineParam(),
        ),
    ),
    McpToolDefinition(
        command = RpcCommand.SIGNATURE,
        title = "Show member signatures",
        description = paragraph(
            """
            Returns one signature line per overload, with real parameter names, generics, throws
            and defaults, plus provenance. A name without a parameter list lists every overload.
            Use it for parameter info. Use jdx_body to read the code, jdx_doc for javadoc, and
            jdx_members to see every member of the type. A type reference with no member is error code 3.
            An ambiguous owner is error code 2 and lists every candidate.
            Kotlin signatures come from @Metadata unless view is jvm. Parses with ASM and never
            loads or executes the inspected class.
            """,
        ),
        params = listOf(
            queryParam(MEMBER_QUERY, examples = MEMBER_EXAMPLES),
            workspaceParam(),
            flagParam(
                "includeSynthetic",
                "Include bridge and synthetic members. Hidden when omitted or false.",
            ),
            limitParam(50),
            viewParam(),
        ),
    ),
    McpToolDefinition(
        command = RpcCommand.DOC,
        title = "Show documentation for a symbol",
        description = paragraph(
            """
            Returns rendered javadoc for a type or member (HTML stripped, inline tags unwrapped)
            plus provenance. An undocumented member falls back to the nearest documenting supertype,
            and the answer says which one, unless no-inherited is true.
            Use it for the documentation. Use jdx_signature for the declaration, jdx_body for the
            code, and jdx_source for the file. The fallback stays on unless no-inherited is true;
            inherited is accepted for explicitness and inherited false leaves the fallback on.
            Setting both is error code 3. raw returns the verbatim comment. maxLines caps the text
            (default 200).
            An ambiguous member ref is error code 2 and lists every candidate.
            Never loads or executes the inspected class.
            """,
        ),
        params = listOf(
            queryParam(SYMBOL_QUERY, examples = SYMBOL_EXAMPLES),
            workspaceParam(),
            flagParam(
                name = "inherited",
                description = "Accepted for explicitness. The supertype documentation fallback stays on " +
                    "unless no-inherited is true. false does not disable the fallback. " +
                    "Setting this and no-inherited together is error code 3.",
                default = true,
            ),
            flagParam(
                name = "no-inherited",
                description = "Only documentation written on this exact symbol. " +
                    "Mutually exclusive with inherited; setting both is error code 3.",
            ),
            flagParam("raw", "Verbatim javadoc comment instead of the rendered plain text."),
            intParam("maxLines", "Maximum lines of doc text. Default 200.", default = 200),
        ),
    ),
    McpToolDefinition(
        command = RpcCommand.SEARCH,
        title = "Search symbols by name",
        description = paragraph(
            """
            Returns symbols whose names match the query. Each hit carries its canonical ref, kind
            and artifact. Globs by default. regex true uses a Java regex. fuzzy true retries a miss
            by Levenshtein distance. A bare word matches case-insensitively or as camel humps
            (HMap finds HashMap).
            Use it to find a name anywhere on the classpath. Use jdx_resolve to expand one short name
            into candidates, jdx_ls for packages, and jdx_usages or jdx_callers once you have a ref.
            Default kind all covers types, packages and modules only. It does not scan methods or
            fields; a miss under that default points at kind method. Pass kind method or field to
            scan members.
            Nothing matched is error code 1. Member scans parse bytecode with ASM and never load
            or execute classes.
            """,
        ),
        params = listOf(
            queryParam(
                description = "Name pattern. Glob by default ('*Http*Client', 'Json*'). " +
                    "A bare word ('HMap', 'toJson') matches case-insensitively or as camel humps. " +
                    "With regex true, a Java regex.",
                examples = listOf("*Http*Client", "toJson", "HMap"),
            ),
            workspaceParam(),
            enumParam(
                name = "kind",
                description = "Which symbols to scan. all (default) is types, packages and modules only. " +
                    "method or field is required to scan members. A miss under all points at method.",
                values = listOf(
                    "all", "type", "class", "interface", "enum", "record", "annotation",
                    "object", "companion", "method", "field", "package", "module",
                ),
                default = "all",
            ),
            regexParam(),
            flagParam("fuzzy", "On a miss, retry with Levenshtein distance. Off when omitted or false."),
            inParam(),
            stringParam(
                name = "package",
                description = "Only symbols under this package.",
                examples = listOf("com.google.gson"),
            ),
            limitParam(50),
        ),
    ),
    McpToolDefinition(
        command = RpcCommand.RESOLVE,
        title = "Resolve a short name to candidates",
        description = paragraph(
            """
            Returns every candidate for a short or partial name (a type, a member, or a package).
            Each row has a kind, an artifact, and a copy-pasteable canonical ref.
            Use it when a stack trace or snippet says Gson and you need the fully qualified ref.
            Use jdx_search for globs and regexes. Use jdx_show or jdx_members once one candidate
            is the one you want. Several candidates are a successful result (code 0). No candidate
            is error code 1, with did-you-mean suggestions.
            Parses with ASM and never loads or executes classes.
            """,
        ),
        params = listOf(
            queryParam(
                description = "Unqualified or partial name. Examples: 'Gson', 'com.google.gson.Gson', " +
                    "'Gson#toJson', a package name.",
                examples = listOf("Gson", "com.google.gson.Gson", "Gson#toJson"),
            ),
            workspaceParam(),
            limitParam(50),
        ),
    ),
    McpToolDefinition(
        command = RpcCommand.LS,
        title = "List packages in the workspace",
        description = paragraph(
            """
            Returns packages in the workspace roots. A glob lists matching packages with type counts.
            An exact package name lists the types in that package.
            Use it to browse a library by package. Use jdx_tree for one artifact's package tree, and
            jdx_search to find a symbol by name. Omit query to list every package.
            Reads class names from bytecode and never loads or executes classes.
            """,
        ),
        params = listOf(
            queryParam(
                description = "Package glob, or an exact package name to list its types. " +
                    "Empty lists every package. Examples: 'com.google.gson', 'com.google.*'.",
                required = false,
                examples = listOf("com.google.gson", "com.google.*"),
            ),
            workspaceParam(),
            limitParam(50),
        ),
    ),
    McpToolDefinition(
        command = RpcCommand.TREE,
        title = "Show artifact package trees",
        description = paragraph(
            """
            Returns artifacts and their package trees. query is an artifact-label glob. Omit it to
            cover every artifact.
            Use it to see how a jar is laid out. Use jdx_ls to list packages or the types in one
            exact package, and jdx_search to find a symbol. depth 0 shows only the top package
            segments (default depth is 8). counts adds a class count on each node.
            Reads class names from bytecode and never loads or executes classes.
            """,
        ),
        params = listOf(
            queryParam(
                description = "Artifact-label glob, for example 'gson*'. Empty covers every artifact.",
                required = false,
                examples = listOf("gson*"),
            ),
            workspaceParam(),
            intParam(
                "depth",
                "How many package segments to show. Default 8. 0 shows only the top segments. Must be >= 0.",
                default = 8,
            ),
            flagParam("counts", "Annotate each node with its class count."),
            limitParam(50),
        ),
    ),
    McpToolDefinition(
        command = RpcCommand.USAGES,
        title = "Find usages of a symbol",
        description = paragraph(
            """
            Returns where a type or member is referenced across the workspace jars and source
            directories: one row per referencing method, or per mentioning line in a source directory.
            Bytecode rows carry file:line when the class has a LineNumberTable.
            Use it for find-usages across every edge kind. Use jdx_callers for the incoming call tree
            only, jdx_calls for what a method calls, jdx_samples for a few ranked snippets, and
            jdx_annotated to start from an annotation or a recipe alias. kind throw is methods that
            declare the type in throws, not ATHROW sites. kind impl and kind override are rejected
            (error code 3); use jdx_hierarchy and jdx_implementors. Source-directory hits are textual
            whole-word mentions and show up only as ref.
            where requires kind annotation. An ambiguous short name is error code 2 and lists every
            candidate. Scans bytecode roots on each call (no persistent index) and never loads or
            executes classes.
            """,
        ),
        params = listOf(
            queryParam(SYMBOL_QUERY, examples = SYMBOL_EXAMPLES),
            workspaceParam(),
            enumParam(
                name = "kind",
                description = "Reference edge to keep. throw is throws declarations, not ATHROW sites. " +
                    "annotation lists classes and members carrying the annotation; where applies only then. " +
                    "impl and override are not usages: they error (code 3) and name jdx_hierarchy and " +
                    "jdx_implementors. all (default) keeps every supported kind.",
                values = listOf("all", "call", "read", "write", "ref", "new", "throw", "annotation"),
                default = "all",
            ),
            inParam(),
            excludeParam(),
            limitParam(50),
            intParam(
                "context",
                "Source lines around each hit. Default 0 (location only). Slices paired sources " +
                    "or source directories around the line; missing sources stay location-only. Capped at 15.",
                default = 0,
            ),
            stringParam(
                name = "where",
                description = "Comma-separated annotation value predicates key=value, AND-ed. " +
                    "Requires kind=annotation. Example: 'forRemoval=true'. Dotted keys walk nested " +
                    "annotations ('nested.k=1'). Brace arrays match order-insensitively. Omitted elements " +
                    "are filled from AnnotationDefault for matching only. The same filter inline is " +
                    "query '@Type(k=v)' with kind annotation.",
                examples = listOf("forRemoval=true"),
            ),
        ),
    ),
    McpToolDefinition(
        command = RpcCommand.ANNOTATED,
        title = "List symbols carrying an annotation",
        description = paragraph(
            """
            Returns classes and members that carry an annotation. Each row includes the rendered
            use, for example @java.lang.Deprecated(forRemoval=true).
            Use it as the annotation-driven view. The listing matches jdx_usages with kind annotation,
            and query also accepts recipe aliases: deprecated (java.lang.Deprecated), forRemoval
            (@Deprecated(forRemoval=true)), test and junit5 (org.junit.jupiter.api.Test), junit4
            (org.junit.Test), bean (org.springframework.context.annotation.Bean). A type or an
            annotation-use '@com.example.Tag(k=v)' (leading @ accepted) works too. Use jdx_usages
            for calls, reads, writes and the other edge kinds.
            An ambiguous type is error code 2 and lists every candidate. where clauses are AND-ed.
            Scans bytecode on each call and never loads or executes classes.
            """,
        ),
        params = listOf(
            queryParam(
                description = "Annotation type ('java.lang.Deprecated'), annotation-use " +
                    "('@java.lang.Deprecated(forRemoval=true)' or without the leading @), or a recipe " +
                    "alias: deprecated, forRemoval, test, junit5, junit4, bean.",
                examples = listOf(
                    "java.lang.Deprecated",
                    "@java.lang.Deprecated(forRemoval=true)",
                    "forRemoval",
                ),
            ),
            workspaceParam(),
            inParam(),
            excludeParam(),
            limitParam(50),
            intParam(
                "context",
                "Source lines around each hit. Default 0 (location only). Capped at 15.",
                default = 0,
            ),
            stringParam(
                name = "where",
                description = "Comma-separated annotation value predicates key=value, AND-ed. " +
                    "Example: 'forRemoval=true'. Dotted keys walk nested annotations. Brace arrays " +
                    "match order-insensitively. Omitted elements are filled from AnnotationDefault " +
                    "for matching only. The same filter inline is query '@Type(k=v)'.",
                examples = listOf("forRemoval=true"),
            ),
        ),
    ),
    McpToolDefinition(
        command = RpcCommand.HIERARCHY,
        title = "Show a type's supertypes and subtypes",
        description = paragraph(
            """
            Returns the type hierarchy: supertypes upward, interfaces included, and subtypes downward
            across the workspace. When both up and down are omitted or false, both directions are walked.
            Set one of them true and the other false to walk only that way.
            Use it for the full picture. Use jdx_implementors for subtypes and implementors only.
            A member reference is error code 3. direct limits the walk to one level. in and exclude
            filter subtypes by artifact label. Omit depth for an unlimited walk (must be >= 1 when set).
            An ambiguous short name is error code 2 and lists every candidate. Scans bytecode roots
            on each call and never loads or executes classes.
            """,
        ),
        params = listOf(
            queryParam(TYPE_QUERY, examples = TYPE_EXAMPLES),
            workspaceParam(),
            flagParam(
                name = "up",
                description = "Walk supertypes. false is the flag's absent value, not 'skip supertypes'. " +
                    "When both up and down are omitted or false, both directions are walked. " +
                    "Supertypes only: up true and down false.",
            ),
            flagParam(
                name = "down",
                description = "Walk subtypes. false is the flag's absent value, not 'skip subtypes'. " +
                    "When both up and down are omitted or false, both directions are walked. " +
                    "Subtypes only: down true and up false.",
            ),
            flagParam("direct", "Direct parents and children only (one level)."),
            intParam(
                "depth",
                "Maximum transitive levels. Omit for unlimited. Must be >= 1. Ignored when direct is true " +
                    "(that walk is one level).",
            ),
            inParam(),
            excludeParam(),
            limitParam(50),
        ),
    ),
    McpToolDefinition(
        command = RpcCommand.IMPLEMENTORS,
        title = "List implementors of a type",
        description = paragraph(
            """
            Returns who implements this interface or extends this class, transitively. This is the
            downward half of the hierarchy.
            Use it for "who implements this?". Use jdx_hierarchy when you also need supertypes.
            direct keeps only immediate children. A member reference is error code 3. Omit depth
            for an unlimited walk.
            An ambiguous short name is error code 2 and lists every candidate. Scans bytecode roots
            on each call and never loads or executes classes.
            """,
        ),
        params = listOf(
            queryParam(TYPE_QUERY, examples = TYPE_EXAMPLES),
            workspaceParam(),
            flagParam("direct", "Direct implementors only (one level)."),
            intParam("depth", "Maximum transitive levels. Omit for unlimited. Must be >= 1."),
            inParam(),
            excludeParam(),
            limitParam(50),
        ),
    ),
    McpToolDefinition(
        command = RpcCommand.CALLERS,
        title = "Show who calls a method",
        description = paragraph(
            """
            Returns who calls this method, as a depth-bounded tree (default depth 1, direct callers).
            Override-aware: call sites through overrides and declaring supertypes are included.
            Constructors, static methods and private methods stay exact. exact true matches invoke
            owners only. A cycle is marked …(cycle).
            Use it for the incoming call hierarchy. Use jdx_usages for every reference kind (reads,
            writes, annotations, throws declarations) as a flat list, jdx_calls for what this method
            calls, and jdx_samples for ranked example snippets. Type and field references are error code 3.
            An ambiguous short name is error code 2 and lists every candidate. Scans bytecode roots
            on each call and never loads or executes classes.
            """,
        ),
        params = listOf(
            queryParam(MEMBER_QUERY, examples = MEMBER_EXAMPLES),
            workspaceParam(),
            intParam(
                "depth",
                "How many caller levels to walk. Default 1 (direct callers). Must be >= 1.",
                default = 1,
            ),
            inParam(),
            excludeParam(),
            limitParam(50),
            flagParam(
                name = "exact",
                description = "Match invoke owners exactly. Omit or false for the default, which also " +
                    "includes calls through overrides and declaring supertypes. Constructors, static " +
                    "methods and private methods are exact either way.",
            ),
        ),
    ),
    McpToolDefinition(
        command = RpcCommand.CALLS,
        title = "Show what a method calls",
        description = paragraph(
            """
            Returns what this method calls, as a depth-bounded tree (default depth 1, direct callees).
            Callees are bytecode-exact: the invoke instruction names one owner. Override expansion is
            only on jdx_callers. A cycle is marked …(cycle).
            Use it for the outgoing call hierarchy. Use jdx_callers for incoming callers, jdx_usages
            for a flat list of every reference kind, and jdx_samples for example snippets.
            externalOnly drops callees in the method's own artifact. Type and field references are
            error code 3.
            An ambiguous short name is error code 2 and lists every candidate. Scans bytecode roots
            on each call and never loads or executes classes.
            """,
        ),
        params = listOf(
            queryParam(MEMBER_QUERY, examples = MEMBER_EXAMPLES),
            workspaceParam(),
            intParam(
                "depth",
                "How many callee levels to walk. Default 1 (direct callees). Must be >= 1.",
                default = 1,
            ),
            inParam(),
            excludeParam(),
            flagParam(
                name = "externalOnly",
                description = "Drop callees whose artifact is the same as the target method's. " +
                    "Leaves the dependency view.",
            ),
            limitParam(50),
        ),
    ),
    McpToolDefinition(
        command = RpcCommand.SAMPLES,
        title = "Show ranked real call sites",
        description = paragraph(
            """
            Returns a few real call sites for a symbol, ranked non-test before test, non-generated
            before generated, and fuller overloads first. Each row is the calling method. Paired
            sources supply an enclosing-method snippet; without them the row is location-only.
            The default limit is 3. maxLines caps each snippet (default 15).
            Use it to see how a symbol is actually called. Use jdx_usages for the full reference
            list, jdx_callers for the incoming call tree, and jdx_body to open one caller. A type
            ref matches calls to any of its members. A field ref is error code 3.
            An ambiguous short name is error code 2 and lists every candidate. Scans bytecode roots
            on each call and never loads or executes classes.
            """,
        ),
        params = listOf(
            queryParam(SYMBOL_QUERY, examples = SYMBOL_EXAMPLES),
            workspaceParam(),
            limitParam(3),
            inParam(),
            excludeParam(),
            flagParam(
                name = "preferSources",
                description = "Rank callers from artifacts that have paired sources first.",
            ),
            intParam(
                "maxLines",
                "Maximum source lines shown per snippet. Default 15.",
                default = 15,
            ),
        ),
    ),
    McpToolDefinition(
        command = RpcCommand.LITERALS,
        title = "Search string constants",
        description = paragraph(
            """
            Returns string constants that contain query: bytecode LDC loads, String field
            ConstantValue entries, and .properties resource keys and values. A properties hit is
            reported as prop path#key with the property value.
            Use it to find a literal such as a message key or a URL fragment. Use jdx_search to find
            symbols by name, and jdx_resources to list resource paths rather than property text.
            Substring match by default. regex true treats query as a Java regex. ignoreCase applies
            to substring mode only.
            Nothing matched is error code 1. Scans bytecode and resource entries on each call and
            never loads or executes classes.
            """,
        ),
        params = listOf(
            queryParam(
                description = "Substring to find in string constants and .properties keys or values. " +
                    "With regex true, a Java regex.",
                examples = listOf("plugin.update"),
            ),
            workspaceParam(),
            regexParam(),
            ignoreCaseParam(),
            inParam(),
            excludeParam(),
            limitParam(50),
        ),
    ),
    McpToolDefinition(
        command = RpcCommand.SERVICES,
        title = "List META-INF/services registrations",
        description = paragraph(
            """
            Returns META-INF/services SPI registrations across the workspace jars: the service
            interface and each provider class.
            Use it to see who provides a service interface. JPMS module-info provides and uses are
            jdx_modules; this tool reads only META-INF/services. Omit query to list every
            registration. A query matches the service interface name or a provider class name.
            Nothing matched is error code 1. Reads the service files as text and never loads or
            executes classes.
            """,
        ),
        params = listOf(
            queryParam(
                description = "Service interface or provider class name to match, as a substring. " +
                    "Empty lists every registration. Example: 'java.sql.Driver'. With regex true, a Java regex.",
                required = false,
                examples = listOf("java.sql.Driver"),
            ),
            workspaceParam(),
            regexParam(),
            ignoreCaseParam(),
            inParam(),
            excludeParam(),
            limitParam(50),
        ),
    ),
    McpToolDefinition(
        command = RpcCommand.MODULES,
        title = "List JPMS module descriptors",
        description = paragraph(
            """
            Returns JPMS module-info descriptors: requires, exports, opens, uses and provides.
            Use it for module metadata. META-INF/services registrations are jdx_services.
            MANIFEST.MF attributes are jdx_manifest. This tool reads module-info only. Omit query
            to list every module. A query matches the module name, an exported or opened package,
            a uses or provides service, or a provider class.
            Nothing matched is error code 1. Parses module-info with ASM and never loads or
            executes classes.
            """,
        ),
        params = listOf(
            queryParam(
                description = "Substring matched against the module name, an exported or opened package, " +
                    "a uses or provides service, or a provider class. Empty lists every module. " +
                    "Example: 'java.sql'. With regex true, a Java regex.",
                required = false,
                examples = listOf("java.sql"),
            ),
            workspaceParam(),
            regexParam(),
            ignoreCaseParam(),
            inParam(),
            excludeParam(),
            limitParam(50),
        ),
    ),
    McpToolDefinition(
        command = RpcCommand.MANIFEST,
        title = "List JAR manifest attributes",
        description = paragraph(
            """
            Returns META-INF/MANIFEST.MF attributes. The default section is the main attributes
            (Main-Class, Implementation-Version, Multi-Release, and the rest). entries true switches
            to per-entry JarEntry attributes (Name sections such as digests and Content-Type).
            Use it for manifest metadata. Resource paths and file text are jdx_resources. module-info
            is jdx_modules. This tool does not extract resource bytes. Omit query to list every
            attribute in the selected section. A query matches the attribute name or value, and the
            entry path when entries is true.
            Nothing matched is error code 1. Reads manifest text and never loads or executes classes.
            """,
        ),
        params = listOf(
            queryParam(
                description = "Substring matched against the attribute name or value, and against the " +
                    "entry path when entries is true. Empty lists every attribute in the selected section. " +
                    "Example: 'Multi-Release'. With regex true, a Java regex.",
                required = false,
                examples = listOf("Multi-Release"),
            ),
            workspaceParam(),
            flagParam(
                name = "entries",
                description = "List per-entry JarEntry attributes (Name sections) instead of the main section.",
            ),
            regexParam(),
            ignoreCaseParam(),
            inParam(),
            excludeParam(),
            limitParam(50),
        ),
    ),
    McpToolDefinition(
        command = RpcCommand.RESOURCES,
        title = "List or print non-class resources",
        description = paragraph(
            """
            Returns non-class resource paths across the workspace jars, including META-INF,
            .properties and versioned META-INF/versions copies, stored as the path is written.
            Omit query to list every path. A query matches the entry path.
            Use it to find a resource file. Manifest attributes are jdx_manifest. SPI registrations
            are jdx_services. String constants and property text are jdx_literals. cat true prints
            the unique match's text instead of listing paths, and then query is required. More than
            one match with cat is error code 2 and lists each path with its artifact. Binary or
            non-UTF-8 payloads are not dumped; the answer reports the size. maxLines caps cat text
            (default 200). limit applies to listing.
            Nothing matched is error code 1. Reads zip entries and never loads or executes classes.
            """,
        ),
        params = listOf(
            queryParam(
                description = "Entry-path substring, for example 'META-INF' or " +
                    "'META-INF/maven/com.google.code.gson/gson/pom.properties'. Empty lists every " +
                    "non-class resource. With regex true, a Java regex. Required when cat is true.",
                required = false,
                examples = listOf("META-INF"),
            ),
            workspaceParam(),
            flagParam(
                name = "cat",
                description = "Print the unique matching resource's text instead of listing paths. " +
                    "Requires query. Error code 2 when more than one path matches, with each path and " +
                    "artifact as a candidate. Binary or non-UTF-8 payloads report their size and are " +
                    "not dumped.",
            ),
            intParam(
                "maxLines",
                "Maximum text lines shown when cat is true. Default 200.",
                default = 200,
            ),
            regexParam(),
            ignoreCaseParam(),
            inParam(),
            excludeParam(),
            limitParam(50),
        ),
    ),
    McpToolDefinition(
        command = RpcCommand.DIFF,
        title = "Diff the public API of two artifacts",
        description = paragraph(
            """
            Returns a public-API diff between two artifacts, using named binary-compatibility rules
            such as MEMBER_REMOVED and RETURN_TYPE_CHANGED, most breaking first. query is the old
            artifact and new is the candidate. Each side is a jar path, a class directory, a glob,
            or a Maven coordinate group:artifact:version. No other jdx_* tool compares two artifacts.
            The comparison itself is code 0 whatever it found. failOn breaking or any turns a tripped
            gate into code 1, and result.gate carries the same verdict. visibility public (the default)
            keeps public and protected declarations; all includes every declared type and member.
            severity changes which findings are printed; the tally still describes the whole comparison.
            Maven coordinates resolve from the local Gradle and Maven caches only. This server has no
            fetch parameter and never downloads. A side that resolves to two artifacts is error code 3.
            Bytecode only: no sources and no decompiler. Parses with ASM and never loads or executes classes.
            """,
        ),
        params = listOf(
            queryParam(
                description = "The old (baseline) artifact: a jar file, a class directory, a glob, " +
                    "or group:artifact:version. Resolved from the local caches when it is a coordinate. " +
                    "This server never downloads it.",
                examples = listOf("gson-2.11.0.jar", "com.google.code.gson:gson:2.11.0"),
            ),
            stringParam(
                name = "new",
                description = "The new (candidate) artifact, compared against query. Same shapes as query: " +
                    "jar file, class directory, glob, or group:artifact:version.",
                required = true,
                examples = listOf("gson-2.14.0.jar", "com.google.code.gson:gson:2.14.0"),
            ),
            workspaceParam(),
            enumParam(
                name = "visibility",
                description = "Which declarations are compared. public (default) is public and protected. " +
                    "all is every declared type and member.",
                values = listOf("public", "all"),
                default = "public",
            ),
            flagParam(
                name = "includeSynthetic",
                description = "Compare bridge and synthetic members too. Hidden when omitted or false.",
            ),
            enumParam(
                name = "severity",
                description = "Which findings to print. all (default) prints everything. suspicious prints " +
                    "breaking and suspicious. breaking prints breaking only. The tally always describes " +
                    "the whole comparison.",
                values = listOf("all", "suspicious", "breaking"),
                default = "all",
            ),
            enumParam(
                name = "failOn",
                description = "When the call is an error. none (default) is code 0 whenever the comparison " +
                    "ran. breaking is code 1 when a breaking change is present. any is code 1 when any " +
                    "difference is present. result.gate carries the same verdict.",
                values = listOf("none", "breaking", "any"),
                default = "none",
            ),
            limitParam(200),
        ),
    ),
    McpToolDefinition(
        command = RpcCommand.VERSION,
        title = "Report the jdx build version",
        description = paragraph(
            """
            Returns the jdx version this server was built as, in result.version of the JSON envelope.
            Use it to check which build is answering. Use jdx_health for liveness (uptime, workspace,
            queries answered). jdx_doctor does not run over MCP; run `jdx doctor` in a shell.
            query is an optional label echoed back and may be empty. Does not read jars.
            """,
        ),
        params = listOf(
            queryParam(
                description = "Optional label echoed back in the envelope. Empty is fine. It does not select a symbol.",
                required = false,
            ),
            workspaceParam(),
        ),
    ),
    McpToolDefinition(
        command = RpcCommand.DOCTOR,
        title = "Environment report, refused over MCP",
        description = paragraph(
            """
            Refuses to run over MCP and returns error code 6. jdx doctor reports the machine it runs
            on, which would be this server's host rather than the client's.
            Run `jdx doctor` in a shell on the machine you want diagnosed. Use jdx_version for the
            build identity and jdx_health to see whether this server is answering. query is an
            optional label and is not consulted.
            """,
        ),
        params = listOf(
            queryParam(
                description = "Optional label. Ignored: this tool always refuses with error code 6.",
                required = false,
            ),
            workspaceParam(),
        ),
    ),
    McpToolDefinition(
        command = RpcCommand.HEALTH,
        title = "Report server liveness",
        description = paragraph(
            """
            Returns a liveness snapshot for this process: workspace, uptimeSeconds, queryCount,
            indexedArtifacts (always 0), indexMode "live-roots-first", rpcVersion, appVersion, pid
            and memoryUsedMb. queryCount counts tool calls this process has answered, including this one.
            Use it to check that the server is up. indexedArtifacts stays 0 because usages, hierarchy
            and call queries re-scan bytecode roots per call; there is no persistent index yet.
            Use jdx_version for the build string alone. query is an optional label echoed back.
            Does not scan jars.
            """,
        ),
        params = listOf(
            queryParam(
                description = "Optional label echoed back in the envelope. Empty is fine. It does not select a symbol.",
                required = false,
            ),
            workspaceParam(),
        ),
    ),
)

/** Looks up a tool by its `jdx_<wire>` name, or returns `null`. Never throws. */
public fun toolForName(name: String): McpToolDefinition? = ALL_MCP_TOOLS.firstOrNull { it.toolName == name }

/**
 * Generates the MCP input schema for this tool from its [McpParam] rows.
 * `query` is required exactly when the command needs a subject (`diff` also
 * requires `new`). Enum, default and examples are emitted only when the row
 * has them. Deterministic — equal tables give equal schemas, so the tool
 * listing is stable across restarts.
 */
public fun McpToolDefinition.inputSchema(): ToolSchema = ToolSchema(
    properties = buildJsonObject {
        for (param in params) {
            put(param.name, param.toSchemaProperty())
        }
    },
    required = params.filter { it.required }.map { it.name },
)

/** One property object. Key order is fixed so the schema bytes stay stable. */
private fun McpParam.toSchemaProperty(): JsonObject = buildJsonObject {
    put("type", kind.schemaType)
    put("description", description)
    if (enumValues.isNotEmpty()) {
        put("enum", buildJsonArray {
            for (value in enumValues) add(JsonPrimitive(value))
        })
    }
    val defaultValue = defaultJson
    if (defaultValue != null) put("default", defaultValue)
    if (examples.isNotEmpty()) {
        put("examples", buildJsonArray {
            for (example in examples) add(JsonPrimitive(example))
        })
    }
}

/**
 * Builds the wire [RpcRequest] for this tool from already-scalarised params.
 * `query` and `workspace` are consumed here (subject and root selection);
 * every other entry forwards verbatim as a wire param (D-058). Unknown entries
 * are ignored — the dispatch owns validation and answers exit 3 naming the
 * real problem. Never throws. `fetch` is not a declared param on any tool, so
 * it is dropped here and the call cannot download.
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
