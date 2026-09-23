package dev.jdx.mcp

import dev.jdx.core.rpc.RpcCommand
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test

/**
 * Tier 1: the MCP tool table without jars or sockets (T-043).
 *
 * [ALL_MCP_TOOLS] is the single source of truth the task demands — schemas
 * are generated from it, wire requests are built from it — so this suite pins
 * the table shape (one tool per wire command, `query` required exactly where
 * the wire needs a subject, every tool overridable to a stored workspace) and
 * the two derivations (schema keys, request params). The live parity —
 * tool text == CLI `--json` bytes — lives in tier 2 (`McpSessionTest`).
 */
class McpToolsTest {

    // -- the table covers the wire exactly ------------------------------------------------

    @Test
    fun `one tool per wire command, named jdx_wire`() {
        ALL_MCP_TOOLS.map { it.command } shouldContainExactlyInAnyOrder RpcCommand.entries.toList()
        ALL_MCP_TOOLS.map { it.toolName } shouldContainExactlyInAnyOrder
            RpcCommand.entries.map { "jdx_${it.wire}" }
    }

    @Test
    fun `query is required exactly where the wire needs a subject`() {
        for (tool in ALL_MCP_TOOLS) {
            val query = tool.params.firstOrNull { it.name == "query" }
            query shouldNotBe null
            query!!.required shouldBe tool.command.requiresQuery
        }
    }

    @Test
    fun `every tool takes an optional workspace override`() {
        for (tool in ALL_MCP_TOOLS) {
            val workspace = tool.params.firstOrNull { it.name == "workspace" }
            workspace shouldNotBe null
            workspace!!.required shouldBe false
            workspace.kind shouldBe McpParamKind.STRING
        }
    }

    @Test
    fun `version doctor and health take only a label and workspace`() {
        for (command in listOf(RpcCommand.VERSION, RpcCommand.DOCTOR, RpcCommand.HEALTH)) {
            val tool = ALL_MCP_TOOLS.first { it.command == command }
            tool.params.map { it.name } shouldContainExactlyInAnyOrder listOf("query", "workspace")
        }
    }

    // -- schema generation -----------------------------------------------------------------

    @Test
    fun `schema properties mirror the param rows`() {
        val tool = ALL_MCP_TOOLS.first { it.command == RpcCommand.MEMBERS }
        val schema = tool.inputSchema()
        schema.properties?.keys shouldContainExactlyInAnyOrder tool.params.map { it.name }.toSet()
        schema.required shouldContainExactlyInAnyOrder listOf("query")
        val queryJson = schema.properties?.getValue("query").toString()
        (queryJson.contains("\"type\":\"string\"")) shouldBe true
        (queryJson.contains("Quote refs")) shouldBe true
    }

    @Test
    fun `schema generation is deterministic`() {
        for (tool in ALL_MCP_TOOLS) {
            tool.inputSchema() shouldBe tool.inputSchema()
        }
    }

    // -- wire requests ----------------------------------------------------------------------

    @Test
    fun `requestFor forwards declared params and consumes routing keys`() {
        val tool = ALL_MCP_TOOLS.first { it.command == RpcCommand.MEMBERS }
        val request = tool.requestFor(
            "com.Example",
            mapOf("query" to "ignored", "workspace" to "ws", "kind" to "method", "limit" to "5", "bogus" to "x"),
        )
        request.command shouldBe RpcCommand.MEMBERS
        request.query shouldBe "com.Example"
        request.params shouldBe mapOf("kind" to "method", "limit" to "5")
    }

    @Test
    fun `splitArgs partitions routing keys params and hostile values`() {
        val args = buildJsonObject {
            put("query", "com.Example")
            put("workspace", "ws")
            put("limit", 5)
            put("withDoc", true)
            put("unlimited", null)
            put("kind", buildJsonArray { add("method") })
            put("grep", buildJsonObject { put("pattern", "x") })
        }
        val split = splitArgs(args)
        split.query shouldBe "com.Example"
        split.workspace shouldBe "ws"
        split.params shouldBe mapOf("limit" to "5", "withDoc" to "true")
        split.structured shouldContainExactlyInAnyOrder listOf("kind", "grep")
    }

    @Test
    fun `scalarText canonicalises typed values like the wire decoder`() {
        scalarText(JsonPrimitive("method")) shouldBe "method"
        scalarText(JsonPrimitive(5)) shouldBe "5"
        scalarText(JsonPrimitive(true)) shouldBe "true"
        scalarText(JsonPrimitive(null)) shouldBe null
        scalarText(buildJsonArray {}) shouldBe "__structured__"
    }

    // -- generating family: the table functions never throw -----------------------------------

    @Test
    fun `hostile strings never break the table functions`() {
        runBlocking {
            checkAll(Arb.string(), Arb.string(), Arb.string()) { name, key, value ->
                toolForName(name) // null or a tool, never a throw
                splitArgs(buildJsonObject { put(key, value) })
                for (tool in ALL_MCP_TOOLS) {
                    tool.inputSchema()
                    tool.hasParam(key)
                    tool.requestFor(name, mapOf(key to value))
                    tool.requestFor(name, emptyMap())
                }
            }
        }
    }
}
