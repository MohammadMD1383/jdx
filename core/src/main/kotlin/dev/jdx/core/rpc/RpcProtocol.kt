package dev.jdx.core.rpc

import dev.jdx.core.render.JsonEscape

/**
 * The `jdx` RPC wire contract, v1 (T-040; PROPOSAL.md §14.5).
 *
 * One contract, four servers: the daemon (T-041), the CLI client (T-042), MCP (T-043),
 * HTTP (T-044) and `batch` (T-045) all speak exactly what is in this file, which is why
 * the T-046 parity proof is structural rather than a pile of per-adapter goldens.
 *
 * **Requests** are newline-delimited JSON — one request per line:
 *
 * ```json
 * {"jdx":1,"command":"members","query":"com.google.gson.Gson","params":{"inherited":"true"}}
 * ```
 *
 * **Responses are not defined here.** A response is the `--json` envelope already produced
 * by `ServiceOutcome.toJson(command)` (`render/Envelope.kt`), verbatim. There is deliberately
 * no second response shape to keep in sync.
 *
 * `core` is dependency-free (D-028), so both directions are hand-rolled: [JsonEscape] writes,
 * and the small reader at the bottom of this file parses. The reader's one hard promise is
 * that [RpcRequest.decode] never throws — a daemon parses whatever a socket hands it.
 */

/**
 * The wire version, mirroring `ENVELOPE_VERSION`. Requests carrying a different explicit
 * version are refused rather than half-understood; the daemon's socket path is version-
 * stamped for the same reason (PROPOSAL.md §14.3).
 */
public const val RPC_VERSION: Int = 1

/** The framing character: every encoded request is one line, terminated by this. */
public const val REQUEST_TERMINATOR: Char = '\n'

/**
 * A query `JdxService` can answer over the wire, with its stable wire name.
 *
 * The name is the contract — MCP derives `jdx_<wire>` tool names from it (T-043) and HTTP
 * `/v1/<wire>` paths (T-044), so renaming one is a breaking change. State-changing commands
 * (`ws`, `cache`) are deliberately absent from v1: the wire surface is read-only, so a
 * daemon serving several clients cannot have one of them reconfigure the others.
 *
 * [requiresQuery] says whether the command needs a subject at all — `ls`/`tree` default their
 * glob to `*` and `version`/`doctor`/`health` take none. It lives here so all four adapters
 * validate identically instead of each re-deriving it (CONTRIBUTING.md: no logic in adapters).
 */
public enum class RpcCommand(public val wire: String, public val requiresQuery: Boolean) {
    SHOW("show", true),
    MEMBERS("members", true),
    OUTLINE("outline", true),
    BODY("body", true),
    SOURCE("source", true),
    SIGNATURE("signature", true),
    DOC("doc", true),
    SEARCH("search", true),
    RESOLVE("resolve", true),
    LS("ls", false),
    TREE("tree", false),
    USAGES("usages", true),
    HIERARCHY("hierarchy", true),
    IMPLEMENTORS("implementors", true),
    CALLERS("callers", true),
    CALLS("calls", true),
    SAMPLES("samples", true),

    /** Build identity — answered without touching an index. */
    VERSION("version", false),

    /** Environment report (`jdx doctor`). */
    DOCTOR("doctor", false),

    /** Daemon liveness probe; the one command with no CLI equivalent. */
    HEALTH("health", false),
    ;

    public companion object {
        private val byWire: Map<String, RpcCommand> = entries.associateBy { it.wire }

        /** The command with this exact wire name, or `null`. Case-sensitive by design. */
        public fun fromWire(wire: String): RpcCommand? = byWire[wire]
    }
}

/**
 * One request on the wire: a [command], its subject [query] (empty when the command takes
 * none), and the flag [params] the adapter would otherwise have passed as CLI options.
 *
 * Param values are text — `"true"`, `"50"` — because that is what the CLI's own flags are,
 * and it keeps one spelling of a value across all four adapters. Typed clients (MCP schemas,
 * HTTP query strings) may send JSON numbers and booleans; [decode] canonicalises those to
 * their text form.
 *
 * This type is a codec, not a validator: it does not check that a [requiresQuery] command
 * actually got a query, nor that a param name exists. `JdxService` already answers bad input
 * with exit 3 and a message, and duplicating that here would give two sources of truth.
 */
public data class RpcRequest(
    public val command: RpcCommand,
    public val query: String = "",
    public val params: Map<String, String> = emptyMap(),
) {
    /**
     * The canonical single-line encoding: fixed key order (`jdx`, `command`, `query`,
     * `params`), params sorted by key, no whitespace. Deterministic — equal requests encode
     * to equal bytes, whatever order the [params] map happens to iterate in.
     *
     * The result never contains a raw newline (every control character is escaped by
     * [JsonEscape]), which is what makes newline framing safe.
     */
    public fun encode(): String = buildString {
        append("{\"jdx\":").append(RPC_VERSION)
        append(",\"command\":").append(JsonEscape.quote(command.wire))
        append(",\"query\":").append(JsonEscape.quote(query))
        append(",\"params\":{")
        params.entries.sortedBy { entry -> entry.key }.forEachIndexed { index, entry ->
            if (index > 0) append(',')
            append(JsonEscape.quote(entry.key)).append(':').append(JsonEscape.quote(entry.value))
        }
        append("}}")
    }

    /** [encode] plus the framing newline — one complete line, ready to write to a stream. */
    public fun frame(): String = encode() + REQUEST_TERMINATOR

    public companion object {
        /**
         * Parses one request line, or returns `null` if [text] is not a request this version
         * understands. **Never throws**, for any input.
         *
         * Generous in what it accepts (CLAUDE.md §6): surrounding whitespace and a trailing
         * `\r\n` are fine, `jdx`/`query`/`params` may be absent, unknown top-level keys are
         * ignored so a later version can add fields, and number/boolean param values are
         * accepted as their text. Strict where ambiguity would be dangerous: an unknown
         * command, a mismatched version, a wrongly-typed field, a structured or null param
         * value, a truncated object or trailing content after it are all `null`, never a
         * best guess at what the caller meant.
         */
        public fun decode(text: String): RpcRequest? {
            val root = JsonReader(text).readDocument() as? JsonValue.Obj ?: return null

            when (val version = root.entries["jdx"]) {
                null -> Unit // absent: assume the current version
                is JsonValue.Num -> if (version.text.toIntOrNull() != RPC_VERSION) return null
                else -> return null
            }

            val command = (root.entries["command"] as? JsonValue.Str)?.let { RpcCommand.fromWire(it.value) }
                ?: return null

            val query = when (val raw = root.entries["query"]) {
                null -> ""
                is JsonValue.Str -> raw.value
                else -> return null
            }

            val params = when (val raw = root.entries["params"]) {
                null -> emptyMap()
                is JsonValue.Obj -> raw.entries.mapValues { (_, value) ->
                    when (value) {
                        is JsonValue.Str -> value.value
                        is JsonValue.Num -> value.text
                        is JsonValue.Bool -> value.value.toString()
                        else -> return null // array, object or null: no single text meaning
                    }
                }
                else -> return null
            }

            return RpcRequest(command, query, params)
        }
    }
}

// --- the reader -----------------------------------------------------------------------------
//
// A minimal JSON parser, private to this file. It exists because `core` takes no dependencies
// (D-028) and a daemon must survive hostile input; it is not a general-purpose JSON library and
// should not grow into one. Two deliberate leniencies, both harmless to the grammar above:
// numbers are kept as verbatim text and validated with `toDoubleOrNull`, so a few non-standard
// spellings (`+1`, `1.`) parse; and nesting deeper than MAX_DEPTH is refused rather than
// recursed into, so no input can exhaust the stack.

private const val MAX_DEPTH: Int = 32

/** A parsed JSON value. Only the shapes the request grammar can contain carry their contents. */
private sealed interface JsonValue {
    data class Str(val value: String) : JsonValue
    data class Num(val text: String) : JsonValue
    data class Bool(val value: Boolean) : JsonValue
    data object Null : JsonValue
    data object Arr : JsonValue
    data class Obj(val entries: Map<String, JsonValue>) : JsonValue
}

private class JsonReader(private val text: String) {
    private var position: Int = 0

    /** The whole input as one JSON value, or `null` if it is malformed or has trailing content. */
    fun readDocument(): JsonValue? {
        val value = readValue(depth = 0) ?: return null
        skipWhitespace()
        return if (position == text.length) value else null
    }

    private fun readValue(depth: Int): JsonValue? {
        if (depth > MAX_DEPTH) return null
        skipWhitespace()
        return when (peek()) {
            null -> null
            '{' -> readObject(depth)
            '[' -> readArray(depth)
            '"' -> readString()?.let { JsonValue.Str(it) }
            't' -> readKeyword("true")?.let { JsonValue.Bool(true) }
            'f' -> readKeyword("false")?.let { JsonValue.Bool(false) }
            'n' -> readKeyword("null")?.let { JsonValue.Null }
            else -> readNumber()
        }
    }

    private fun readObject(depth: Int): JsonValue? {
        position++ // '{'
        val entries = LinkedHashMap<String, JsonValue>()
        skipWhitespace()
        if (peek() == '}') {
            position++
            return JsonValue.Obj(entries)
        }
        while (true) {
            skipWhitespace()
            val key = readString() ?: return null
            skipWhitespace()
            if (peek() != ':') return null
            position++
            val value = readValue(depth + 1) ?: return null
            entries[key] = value
            skipWhitespace()
            when (peek()) {
                ',' -> position++
                '}' -> {
                    position++
                    return JsonValue.Obj(entries)
                }
                else -> return null
            }
        }
    }

    /** Arrays are parsed only to find where they end: no request field can contain one. */
    private fun readArray(depth: Int): JsonValue? {
        position++ // '['
        skipWhitespace()
        if (peek() == ']') {
            position++
            return JsonValue.Arr
        }
        while (true) {
            readValue(depth + 1) ?: return null
            skipWhitespace()
            when (peek()) {
                ',' -> position++
                ']' -> {
                    position++
                    return JsonValue.Arr
                }
                else -> return null
            }
        }
    }

    /** A quoted string with JSON escapes. Raw control characters are refused, as the spec says. */
    private fun readString(): String? {
        if (peek() != '"') return null
        position++
        val builder = StringBuilder()
        while (true) {
            val char = peek() ?: return null // unterminated
            position++
            when {
                char == '"' -> return builder.toString()
                char == '\\' -> builder.append(readEscape() ?: return null)
                char < ' ' -> return null
                else -> builder.append(char)
            }
        }
    }

    private fun readEscape(): Char? {
        val marker = peek() ?: return null
        position++
        return when (marker) {
            '"' -> '"'
            '\\' -> '\\'
            '/' -> '/'
            'b' -> '\b'
            'f' -> '\u000C'
            'n' -> '\n'
            'r' -> '\r'
            't' -> '\t'
            'u' -> readUnicodeEscape()
            else -> null
        }
    }

    /**
     * The four hex digits of a `\uXXXX` escape, accumulated one digit at a time. Done by hand
     * rather than `substring(...).toIntOrNull(16)` because that spelling also accepts a signed
     * run like `+12`, and the extra guard it would need is a second way to say the same thing.
     */
    private fun readUnicodeEscape(): Char? {
        if (position + 4 > text.length) return null
        var code = 0
        for (offset in 0 until 4) {
            val digit = text[position + offset].digitToIntOrNull(radix = 16) ?: return null
            code = code * 16 + digit
        }
        position += 4
        return code.toChar()
    }

    /**
     * The maximal run of number characters, kept verbatim so a param value round-trips as the
     * text the caller wrote. Validated by parsing, not by a grammar — see the note above.
     */
    private fun readNumber(): JsonValue? {
        val start = position
        while (true) {
            val char = peek() ?: break
            if (char in "+-.eE" || char in '0'..'9') position++ else break
        }
        val raw = text.substring(start, position)
        return if (raw.toDoubleOrNull() == null) null else JsonValue.Num(raw)
    }

    private fun readKeyword(keyword: String): Unit? {
        if (!text.startsWith(keyword, position)) return null
        position += keyword.length
        return Unit
    }

    private fun peek(): Char? = if (position < text.length) text[position] else null

    private fun skipWhitespace() {
        while (position < text.length && text[position].isJsonWhitespace()) position++
    }

    private fun Char.isJsonWhitespace(): Boolean = this == ' ' || this == '\t' || this == '\n' || this == '\r'
}
