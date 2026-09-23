package dev.jdx.mcp

import dev.jdx.core.rpc.RpcCommand
import dev.jdx.core.rpc.RpcRequest
import dev.jdx.index.service.JdxService
import dev.jdx.index.service.dispatch
import dev.jdx.index.workspace.InMemoryWorkspaceStore
import dev.jdx.index.workspace.WorkspaceDefinition
import dev.jdx.testsupport.fixtures.FixtureJars
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Tier 2: the MCP session over the real fixture jar (T-043).
 *
 * The acceptance is byte parity: every `jdx_<wire>` tool returns the same
 * bytes as the in-process service call for the hand-written equivalent
 * [RpcRequest], so MCP answers are byte-identical to CLI `--json` answers by
 * construction (the T-046 proof feeds on this, D-056 §1). Param errors read
 * as exit-3 envelopes naming the param; missing workspaces as exit 4;
 * `doctor` as an honest exit-6 refusal; every line is one line (framing
 * safety, mirroring the daemon's strict 1:1 mapping).
 */
@Tag("tier2")
class McpSessionTest {

    private val lenientJson: Json = Json { ignoreUnknownKeys = true }

    private fun fixtureStore(): InMemoryWorkspaceStore {
        val jar = FixtureJars.binaryJar().absolutePath
        return InMemoryWorkspaceStore().also {
            it.save(WorkspaceDefinition(name = "fx", jars = listOf(jar), includeJdk = false))
        }
    }

    private fun session(workspace: String = "fx"): McpSession =
        McpSession(workspace = workspace, appVersion = "test", store = fixtureStore())

    private fun envelopeOf(text: String) = lenientJson.parseToJsonElement(text).jsonObject

    /** Calls one tool and asserts the parity contract: identical bytes, one line, matching command. */
    private fun checkParity(
        toolName: String,
        args: kotlinx.serialization.json.JsonObject,
        expectedRequest: RpcRequest,
        roots: JdxService.RootsSpec,
    ): McpSession.McpResult {
        val result = session().callTool(toolName, args)
        val expected = JdxService.dispatch(expectedRequest, roots).toJson(expectedRequest.command.wire)
        result.text shouldBe expected
        result.text shouldNotContain "\n"
        envelopeOf(result.text)["command"]?.jsonPrimitive?.content shouldBe expectedRequest.command.wire
        return result
    }

    private fun roots(): JdxService.RootsSpec = JdxService.RootsSpec(
        jarSpecs = listOf(FixtureJars.binaryJar().absolutePath),
        includeJdk = false,
    )

    private fun args(query: String, vararg params: Pair<String, Any?>) = buildJsonObject {
        put("query", query)
        for ((key, value) in params) {
            when (value) {
                is String -> put(key, value)
                is Int -> put(key, value)
                is Boolean -> put(key, value)
                else -> throw IllegalArgumentException("test only builds scalar args")
            }
        }
    }

    // -- parity: one request per read tool -------------------------------------------------

    @Test
    fun `show answers the class card`() {
        val query = "dev.jdx.fixtures.Generics"
        val result = checkParity("jdx_show", args(query), RpcRequest(RpcCommand.SHOW, query), roots())
        result.isError shouldBe false
    }

    @Test
    fun `members forwards kind limit and view`() {
        val query = "dev.jdx.fixtures.Generics"
        val result = checkParity(
            "jdx_members", args(query, "kind" to "method", "limit" to 5, "view" to "jvm"),
            RpcRequest(RpcCommand.MEMBERS, query, mapOf("kind" to "method", "limit" to "5", "view" to "jvm")),
            roots(),
        )
        result.isError shouldBe false
    }

    @Test
    fun `outline forwards access and sort`() {
        val query = "dev.jdx.fixtures.Generics"
        val result = checkParity(
            "jdx_outline", args(query, "access" to "public", "sort" to "name"),
            RpcRequest(RpcCommand.OUTLINE, query, mapOf("access" to "public", "sort" to "name")),
            roots(),
        )
        result.isError shouldBe false
    }

    @Test
    fun `body forwards maxLines and withSignature`() {
        val query = "dev.jdx.fixtures.Generics#identity(java.lang.Object)"
        val result = checkParity(
            "jdx_body", args(query, "maxLines" to 50, "withSignature" to true),
            RpcRequest(RpcCommand.BODY, query, mapOf("maxLines" to "50", "withSignature" to "true")),
            roots(),
        )
        result.isError shouldBe false
    }

    @Test
    fun `source forwards a lines window`() {
        val query = "dev.jdx.fixtures.Generics"
        checkParity(
            "jdx_source", args(query, "lines" to "1:10", "lineNumbers" to true),
            RpcRequest(RpcCommand.SOURCE, query, mapOf("lines" to "1:10", "lineNumbers" to "true")),
            roots(),
        )
    }

    @Test
    fun `signature forwards view and limit`() {
        val query = "dev.jdx.fixtures.Generics#identity"
        val result = checkParity(
            "jdx_signature", args(query, "view" to "jvm", "limit" to 10),
            RpcRequest(RpcCommand.SIGNATURE, query, mapOf("view" to "jvm", "limit" to "10")),
            roots(),
        )
        result.isError shouldBe false
    }

    @Test
    fun `doc forwards raw`() {
        val query = "dev.jdx.fixtures.Generics"
        checkParity(
            "jdx_doc", args(query, "raw" to true),
            RpcRequest(RpcCommand.DOC, query, mapOf("raw" to "true")),
            roots(),
        )
    }

    @Test
    fun `search forwards kind`() {
        val result = checkParity(
            "jdx_search", args("*Generic*", "kind" to "class"),
            RpcRequest(RpcCommand.SEARCH, "*Generic*", mapOf("kind" to "class")),
            roots(),
        )
        result.isError shouldBe false
    }

    @Test
    fun `resolve forwards limit`() {
        val result = checkParity(
            "jdx_resolve", args("Generics", "limit" to 10),
            RpcRequest(RpcCommand.RESOLVE, "Generics", mapOf("limit" to "10")),
            roots(),
        )
        result.isError shouldBe false
    }

    @Test
    fun `ls answers an exact package`() {
        val result = checkParity(
            "jdx_ls", args("dev.jdx.fixtures"),
            RpcRequest(RpcCommand.LS, "dev.jdx.fixtures"),
            roots(),
        )
        result.isError shouldBe false
    }

    @Test
    fun `tree forwards depth and counts`() {
        val result = checkParity(
            "jdx_tree", args("", "depth" to 3, "counts" to true),
            RpcRequest(RpcCommand.TREE, "", mapOf("depth" to "3", "counts" to "true")),
            roots(),
        )
        result.isError shouldBe false
    }

    @Test
    fun `usages forwards kind`() {
        checkParity(
            "jdx_usages", args("dev.jdx.fixtures.Generics#identity", "kind" to "call"),
            RpcRequest(RpcCommand.USAGES, "dev.jdx.fixtures.Generics#identity", mapOf("kind" to "call")),
            roots(),
        )
    }

    @Test
    fun `hierarchy forwards direct`() {
        checkParity(
            "jdx_hierarchy", args("dev.jdx.fixtures.Generics", "direct" to true),
            RpcRequest(RpcCommand.HIERARCHY, "dev.jdx.fixtures.Generics", mapOf("direct" to "true")),
            roots(),
        )
    }

    @Test
    fun `implementors dispatches downward`() {
        checkParity(
            "jdx_implementors", args("dev.jdx.fixtures.Generics"),
            RpcRequest(RpcCommand.IMPLEMENTORS, "dev.jdx.fixtures.Generics"),
            roots(),
        )
    }

    @Test
    fun `callers forwards depth`() {
        checkParity(
            "jdx_callers", args("dev.jdx.fixtures.Generics#identity", "depth" to 2),
            RpcRequest(RpcCommand.CALLERS, "dev.jdx.fixtures.Generics#identity", mapOf("depth" to "2")),
            roots(),
        )
    }

    @Test
    fun `calls forwards externalOnly`() {
        checkParity(
            "jdx_calls", args("dev.jdx.fixtures.Generics#identity", "externalOnly" to true),
            RpcRequest(RpcCommand.CALLS, "dev.jdx.fixtures.Generics#identity", mapOf("externalOnly" to "true")),
            roots(),
        )
    }

    @Test
    fun `samples forwards preferSources`() {
        checkParity(
            "jdx_samples", args("dev.jdx.fixtures.Generics#identity", "preferSources" to true),
            RpcRequest(RpcCommand.SAMPLES, "dev.jdx.fixtures.Generics#identity", mapOf("preferSources" to "true")),
            roots(),
        )
    }

    // -- transport commands ------------------------------------------------------------------

    @Test
    fun `version answers the build version`() {
        val result = session().callTool("jdx_version", buildJsonObject {})
        result.isError shouldBe false
        result.text shouldContain "\"command\":\"version\""
        result.text shouldContain "\"version\":\"test\""
    }

    @Test
    fun `health reports workspace and query count`() {
        val session = session()
        session.callTool("jdx_version", buildJsonObject {})
        val result = session.callTool("jdx_health", buildJsonObject {})
        result.isError shouldBe false
        val envelope = envelopeOf(result.text)
        envelope["command"]?.jsonPrimitive?.content shouldBe "health"
        val health = envelope["result"]?.jsonObject
        (health?.get("workspace")?.jsonPrimitive?.content) shouldBe "fx"
        (health?.get("appVersion")?.jsonPrimitive?.content) shouldBe "test"
        // Two calls so far on this session: version + this health.
        (health?.get("queryCount")?.jsonPrimitive?.content) shouldBe "2"
    }

    @Test
    fun `doctor refuses honestly`() {
        val result = session().callTool("jdx_doctor", buildJsonObject {})
        result.isError shouldBe true
        result.text shouldContain "\"code\":6"
        result.text shouldContain "jdx doctor"
    }

    // -- roots and errors ----------------------------------------------------------------------

    @Test
    fun `a missing workspace fails exit 4 naming the fix`() {
        val result = session("nope").callTool("jdx_show", args("dev.jdx.fixtures.Generics"))
        result.isError shouldBe true
        result.text shouldContain "\"code\":4"
        result.text shouldContain "no such workspace 'nope'"
    }

    @Test
    fun `the per-call workspace override wins over the server default`() {
        val result = session("nope").callTool(
            "jdx_show",
            buildJsonObject {
                put("query", "dev.jdx.fixtures.Generics")
                put("workspace", "fx")
            },
        )
        result.isError shouldBe false
        result.text shouldContain "\"command\":\"show\""
    }

    @Test
    fun `version and health answer without roots`() {
        session("nope").callTool("jdx_version", buildJsonObject {}).isError shouldBe false
        session("nope").callTool("jdx_health", buildJsonObject {}).isError shouldBe false
    }

    @Test
    fun `unknown tools and structured args are honest errors, never throws`() {
        val unknown = session().callTool("jdx_nope", buildJsonObject {})
        unknown.isError shouldBe true
        unknown.text shouldContain "\"code\":6"

        val hostile = session().callTool(
            "jdx_members",
            buildJsonObject {
                put("query", "dev.jdx.fixtures.Generics")
                put("kind", buildJsonObject { put("nested", "object") })
            },
        )
        hostile.isError shouldBe true
        hostile.text shouldContain "\"code\":3"
        hostile.text shouldContain "--kind"
    }

    @Test
    fun `usage errors ride the envelope with isError`() {
        val result = session().callTool(
            "jdx_members",
            args("dev.jdx.fixtures.Generics", "kind" to "bogus"),
        )
        result.isError shouldBe true
        result.text shouldContain "\"code\":3"
        result.text shouldContain "--kind 'bogus'"
    }

    // -- SDK registration ------------------------------------------------------------------------

    @Test
    fun `the SDK server exposes every tool`() {
        val server = buildMcpServer(session(), "test")
        try {
            server.tools.keys shouldBe ALL_MCP_TOOLS.map { it.toolName }.toSet()
        } finally {
            kotlinx.coroutines.runBlocking { server.close() }
        }
    }
}
