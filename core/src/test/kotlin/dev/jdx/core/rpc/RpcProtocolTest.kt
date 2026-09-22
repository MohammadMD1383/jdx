package dev.jdx.core.rpc

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The v1 wire contract (T-040, PROPOSAL.md §14): one request per line of
 * newline-delimited JSON, decoded by a hand-rolled reader because `core` stays
 * dependency-free (D-028).
 *
 * These are the *pinned* vectors. `RpcProtocolPropertyTest` carries the generating
 * family (round-trip, determinism, never-throws). If a test here changes, the wire
 * format changed — which is a breaking change for every T-041…T-046 adapter.
 */
class RpcProtocolTest {

    // --- the pinned byte-level vectors ---------------------------------------------------

    @Test
    fun `encodes the fixed key order with params`() {
        val request = RpcRequest(
            command = RpcCommand.MEMBERS,
            query = "com.example.Point",
            params = mapOf("limit" to "50", "inherited" to "true"),
        )
        request.encode() shouldBe
            """{"jdx":1,"command":"members","query":"com.example.Point",""" +
            """"params":{"inherited":"true","limit":"50"}}"""
    }

    @Test
    fun `encodes an empty params object rather than omitting it`() {
        // One shape on the wire, always — adapters never branch on presence.
        RpcRequest(RpcCommand.VERSION).encode() shouldBe
            """{"jdx":1,"command":"version","query":"","params":{}}"""
    }

    @Test
    fun `params are sorted by key regardless of insertion order`() {
        val ascending = RpcRequest(RpcCommand.SEARCH, "*Http*", mapOf("a" to "1", "b" to "2", "c" to "3"))
        val descending = RpcRequest(RpcCommand.SEARCH, "*Http*", mapOf("c" to "3", "b" to "2", "a" to "1"))
        ascending.encode() shouldBe descending.encode()
        ascending.encode() shouldBe
            """{"jdx":1,"command":"search","query":"*Http*","params":{"a":"1","b":"2","c":"3"}}"""
    }

    @Test
    fun `framing appends exactly one newline and the payload carries none`() {
        val request = RpcRequest(RpcCommand.BODY, "a\nb", mapOf("k" to "v\nw"))
        val framed = request.frame()
        framed.endsWith("\n") shouldBe true
        framed.count { it == '\n' } shouldBe 1
        request.encode().contains('\n') shouldBe false
    }

    // --- decoding -------------------------------------------------------------------------

    @Test
    fun `decodes the canonical encoding back to the identical request`() {
        val request = RpcRequest(RpcCommand.USAGES, "com.example.Gson", mapOf("limit" to "20"))
        RpcRequest.decode(request.encode()) shouldBe request
    }

    @Test
    fun `decodes a framed line, tolerating CRLF and surrounding whitespace`() {
        val request = RpcRequest(RpcCommand.SHOW, "com.example.Point")
        RpcRequest.decode(request.frame()) shouldBe request
        RpcRequest.decode(request.encode() + "\r\n") shouldBe request
        RpcRequest.decode("  " + request.encode() + "  ") shouldBe request
    }

    @Test
    fun `accepts a missing query and missing params as the empty defaults`() {
        // Generous in what we accept, canonical in what we print (CLAUDE.md §6).
        RpcRequest.decode("""{"command":"doctor"}""") shouldBe RpcRequest(RpcCommand.DOCTOR)
        RpcRequest.decode("""{"jdx":1,"command":"ls"}""") shouldBe RpcRequest(RpcCommand.LS)
    }

    @Test
    fun `accepts number and boolean param values and canonicalises them to text`() {
        // MCP (T-043) and HTTP (T-044) clients build params from typed schemas, where
        // `limit` is a JSON number and `inherited` a JSON boolean. Both mean the flag text.
        val decoded = RpcRequest.decode(
            """{"command":"members","query":"C","params":{"inherited":true,"limit":50,"ratio":-1.5}}""",
        )
        decoded shouldBe RpcRequest(
            RpcCommand.MEMBERS,
            "C",
            mapOf("inherited" to "true", "limit" to "50", "ratio" to "-1.5"),
        )
    }

    @Test
    fun `ignores unknown top-level keys so a later version can add fields`() {
        RpcRequest.decode("""{"jdx":1,"command":"show","query":"C","params":{},"trace":"abc"}""") shouldBe
            RpcRequest(RpcCommand.SHOW, "C")
    }

    @Test
    fun `decodes string escapes including unicode`() {
        RpcRequest.decode("""{"command":"body","query":"a\"b\\c\ndA"}""") shouldBe
            RpcRequest(RpcCommand.BODY, "a\"b\\c\ndA")
    }

    // --- decoding refuses, and never throws ------------------------------------------------

    @Test
    fun `refuses garbage, empty input and non-objects`() {
        listOf("", "   ", "not json", "[]", "\"just a string\"", "null", "42", "{", "{}").forEach { text ->
            RpcRequest.decode(text) shouldBe null
        }
    }

    @Test
    fun `refuses an unknown or wrongly-typed command`() {
        RpcRequest.decode("""{"command":"explode","query":"C"}""") shouldBe null
        RpcRequest.decode("""{"command":"SHOW","query":"C"}""") shouldBe null
        RpcRequest.decode("""{"command":7,"query":"C"}""") shouldBe null
        RpcRequest.decode("""{"query":"C"}""") shouldBe null
    }

    @Test
    fun `refuses a version it does not speak, but accepts an absent version`() {
        RpcRequest.decode("""{"jdx":2,"command":"show","query":"C"}""") shouldBe null
        RpcRequest.decode("""{"jdx":"1","command":"show","query":"C"}""") shouldBe null
        RpcRequest.decode("""{"command":"show","query":"C"}""") shouldBe RpcRequest(RpcCommand.SHOW, "C")
    }

    @Test
    fun `refuses structured or null param values`() {
        RpcRequest.decode("""{"command":"show","query":"C","params":{"k":["a"]}}""") shouldBe null
        RpcRequest.decode("""{"command":"show","query":"C","params":{"k":{"n":"v"}}}""") shouldBe null
        RpcRequest.decode("""{"command":"show","query":"C","params":{"k":null}}""") shouldBe null
        RpcRequest.decode("""{"command":"show","query":"C","params":[]}""") shouldBe null
    }

    @Test
    fun `refuses trailing content after the request object`() {
        RpcRequest.decode("""{"command":"show","query":"C"} {"command":"show"}""") shouldBe null
        RpcRequest.decode("""{"command":"show","query":"C"}x""") shouldBe null
    }

    @Test
    fun `refuses malformed strings — raw control characters and bad escapes`() {
        // Non-raw Kotlin strings: `\\` below is ONE backslash in the JSON text.
        RpcRequest.decode("{\"command\":\"show\",\"query\":\"a\tb\"}") shouldBe null
        RpcRequest.decode("{\"command\":\"show\",\"query\":\"a\\qb\"}") shouldBe null
        RpcRequest.decode("{\"command\":\"show\",\"query\":\"a\\u12\"}") shouldBe null
        RpcRequest.decode("{\"command\":\"show\",\"query\":\"a\\u+123\"}") shouldBe null
        RpcRequest.decode("{\"command\":\"show\",\"query\":\"unterminated}") shouldBe null
        // The positive control for the same code path: a real escape is honoured.
        RpcRequest.decode("{\"command\":\"show\",\"query\":\"a\\\\q\\u00e9\"}") shouldBe
            RpcRequest(RpcCommand.SHOW, "a\\qé")
    }

    @Test
    fun `refuses malformed numbers and keywords`() {
        RpcRequest.decode("""{"command":"show","query":"C","params":{"k":1.2.3}}""") shouldBe null
        RpcRequest.decode("""{"command":"show","query":"C","params":{"k":-}}""") shouldBe null
        RpcRequest.decode("""{"command":"show","query":"C","params":{"k":tru}}""") shouldBe null
        RpcRequest.decode("""{"command":"show","query":"C","params":{"k":}}""") shouldBe null
    }

    @Test
    fun `refuses absurdly nested input instead of exhausting the stack`() {
        // A daemon reads whatever the socket sends; depth is bounded, not recursed into.
        val deep = "[".repeat(200) + "]".repeat(200)
        RpcRequest.decode("""{"command":"show","query":"C","trace":$deep}""") shouldBe null
        // Well within the bound, the same shape is merely ignored as an unknown key.
        RpcRequest.decode("""{"command":"show","query":"C","trace":[[["x"]]]}""") shouldBe
            RpcRequest(RpcCommand.SHOW, "C")
    }

    @Test
    fun `refuses a malformed object or array structure`() {
        RpcRequest.decode("""{"command":"show",}""") shouldBe null
        RpcRequest.decode("""{"command" "show"}""") shouldBe null
        RpcRequest.decode("""{command:"show"}""") shouldBe null
        RpcRequest.decode("""{"command":"show","trace":[1,]}""") shouldBe null
        RpcRequest.decode("""{"command":"show","trace":[1 2]}""") shouldBe null
    }

    @Test
    fun `refuses a non-string query`() {
        RpcRequest.decode("""{"command":"show","query":7}""") shouldBe null
    }

    @Test
    fun `accepts whitespace between tokens, as a pretty-printing client sends it`() {
        // An HTTP or MCP client may well indent its JSON; only the framing forbids newlines
        // *inside* a request we encode ourselves.
        val pretty = """
            { "jdx" : 1 ,
              "command" : "members" ,
              "query" : "com.example.Point" ,
              "params" : { "inherited" : true , "limit" : 50 } }
        """.trimIndent()
        RpcRequest.decode(pretty) shouldBe RpcRequest(
            RpcCommand.MEMBERS,
            "com.example.Point",
            mapOf("inherited" to "true", "limit" to "50"),
        )
    }

    @Test
    fun `accepts false and null and empty containers in the places they may appear`() {
        // `false` is a param value; `null` and `[]` can only arrive as an ignored unknown key.
        RpcRequest.decode("""{"command":"members","query":"C","params":{"inherited":false}}""") shouldBe
            RpcRequest(RpcCommand.MEMBERS, "C", mapOf("inherited" to "false"))
        RpcRequest.decode("""{"command":"show","query":"C","trace":null}""") shouldBe
            RpcRequest(RpcCommand.SHOW, "C")
        RpcRequest.decode("""{"command":"show","query":"C","trace":[]}""") shouldBe
            RpcRequest(RpcCommand.SHOW, "C")
        RpcRequest.decode("""{"command":"show","query":"C","trace":{}}""") shouldBe
            RpcRequest(RpcCommand.SHOW, "C")
    }

    @Test
    fun `accepts numeric param values across the digit range and both exponent signs`() {
        RpcRequest.decode(
            """{"command":"tree","params":{"a":0,"b":9,"c":1e3,"d":0.5,"e":1e+3,"f":-2E-1}}""",
        ) shouldBe RpcRequest(
            RpcCommand.TREE,
            "",
            mapOf("a" to "0", "b" to "9", "c" to "1e3", "d" to "0.5", "e" to "1e+3", "f" to "-2E-1"),
        )
    }

    @Test
    fun `accepts whitespace inside empty and populated containers`() {
        // The skip-whitespace calls guarding `{ }`, `[ ]` and `[1 , 2 ]` have no other witness.
        RpcRequest.decode("""{"command":"show","query":"C","trace":{ }}""") shouldBe
            RpcRequest(RpcCommand.SHOW, "C")
        RpcRequest.decode("""{"command":"show","query":"C","trace":[ ]}""") shouldBe
            RpcRequest(RpcCommand.SHOW, "C")
        RpcRequest.decode("""{"command":"show","query":"C","trace":[1 , 2 ]}""") shouldBe
            RpcRequest(RpcCommand.SHOW, "C")
    }

    @Test
    fun `nesting is bounded at exactly MAX_DEPTH`() {
        // Pins the constant: 32 levels parse, 33 are refused before the stack is at risk.
        fun nested(levels: Int) =
            """{"command":"show","query":"C","trace":${"[".repeat(levels)}${"]".repeat(levels)}}"""
        RpcRequest.decode(nested(32)) shouldBe RpcRequest(RpcCommand.SHOW, "C")
        RpcRequest.decode(nested(33)) shouldBe null
        // Objects are bounded by the same counter, not a separate one.
        val deepObject = "{\"a\":".repeat(33) + "1" + "}".repeat(33)
        RpcRequest.decode("""{"command":"show","query":"C","trace":$deepObject}""") shouldBe null
    }

    @Test
    fun `refuses a params object with a non-string key`() {
        RpcRequest.decode("""{"command":"show","query":"C","params":{:"v"}}""") shouldBe null
        RpcRequest.decode("""{"command":"show","query":"C","params":{7:"v"}}""") shouldBe null
    }

    @Test
    fun `exposes the decoded parts as the adapters read them`() {
        // Adapters reach for these properties directly; assert them, not just value equality.
        val decoded = RpcRequest.decode("""{"command":"calls","query":"C#m()","params":{"depth":"2"}}""")!!
        decoded.command shouldBe RpcCommand.CALLS
        decoded.command.wire shouldBe "calls"
        decoded.query shouldBe "C#m()"
        decoded.params shouldBe mapOf("depth" to "2")
    }

    // --- the command table is itself the contract ------------------------------------------

    @Test
    fun `wire names are pinned, unique and lowercase`() {
        RpcCommand.entries.map { it.wire } shouldBe listOf(
            "show", "members", "outline", "body", "source", "signature", "doc",
            "search", "resolve", "ls", "tree", "usages", "hierarchy", "implementors",
            "callers", "calls", "samples", "version", "doctor", "health",
        )
        RpcCommand.entries.map { it.wire }.toSet().size shouldBe RpcCommand.entries.size
        RpcCommand.entries.all { it.wire == it.wire.lowercase() } shouldBe true
    }

    @Test
    fun `every command resolves from its own wire name and nothing else does`() {
        RpcCommand.entries.forEach { command ->
            RpcCommand.fromWire(command.wire) shouldBe command
        }
        RpcCommand.fromWire("nope") shouldBe null
        RpcCommand.fromWire("") shouldBe null
        RpcCommand.fromWire("Show") shouldBe null
    }

    @Test
    fun `query requirement is pinned per command`() {
        // `ls`/`tree` default their glob to `*`; version/doctor/health take no subject.
        RpcCommand.entries.filterNot { it.requiresQuery }.map { it.wire } shouldBe
            listOf("ls", "tree", "version", "doctor", "health")
    }

    @Test
    fun `the protocol version mirrors the envelope version`() {
        RPC_VERSION shouldBe 1
        RPC_VERSION shouldBe dev.jdx.core.render.ENVELOPE_VERSION
    }
}
