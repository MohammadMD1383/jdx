package dev.jdx.cli.commands

import com.github.ajalt.clikt.core.parse
import dev.jdx.core.render.ErrorResult
import dev.jdx.core.rpc.RpcRequest
import dev.jdx.index.service.JdxService
import dev.jdx.index.workspace.InMemoryWorkspaceStore
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import java.io.BufferedReader
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test

/**
 * Tier 1: `jdx batch` framing (T-045). No IO — the query dispatch is injected,
 * stdin is injected, roots resolve against an isolated store with no
 * environment and no project discovery.
 */
class BatchCommandTest {

    private class TestExit(val code: Int) : RuntimeException()

    private val noDiscovery: ProjectDiscoveryFn = { _, _, _, _ -> null }
    private val noEnv: (String) -> String? = { null }
    private val lenientJson: Json = Json { ignoreUnknownKeys = true }

    private data class Run(val lines: List<String>, val exit: Int)

    private fun run(
        stdinText: String,
        args: List<String> = listOf("--no-jdk"),
        dispatch: (RpcRequest, JdxService.RootsSpec) -> JdxService.ServiceOutcome = { request, _ ->
            JdxService.ServiceOutcome.Failure(ErrorResult.notFound(request.query))
        },
    ): Run {
        val printed = mutableListOf<String>()
        val exit = try {
            BatchCommand(
                terminate = { throw TestExit(it) },
                store = InMemoryWorkspaceStore(),
                getenv = noEnv,
                discover = noDiscovery,
                stdin = { BufferedReader(stdinText.reader()) },
                printer = printed::add,
                dispatch = dispatch,
            ).parse(args)
            0
        } catch (e: TestExit) {
            e.code
        }
        return Run(printed, exit)
    }

    private fun envelope(line: String) = lenientJson.parseToJsonElement(line).jsonObject

    private fun exitOf(line: String): Int {
        val root = envelope(line)
        return if (root["ok"]?.jsonPrimitive?.booleanOrNull == true) {
            0
        } else {
            root["error"]?.jsonObject?.get("code")?.jsonPrimitive?.intOrNull ?: -1
        }
    }

    @Test
    fun `a mixed stream answers every line and exits the max code`() {
        val stdinText = listOf(
            """{"command":"version","query":""}""",
            """{"command":"show","query":"com.example.Missing"}""",
            "this is not json",
            """{"command":"health","query":""}""",
        ).joinToString("\n")
        val seen = mutableListOf<RpcRequest>()
        val run = run(stdinText, dispatch = { request, _ ->
            seen.add(request)
            JdxService.ServiceOutcome.Failure(ErrorResult.notFound(request.query))
        })
        run.lines.size shouldBe 4
        run.exit shouldBe 6
        exitOf(run.lines[0]) shouldBe 0
        envelope(run.lines[0])["command"]?.jsonPrimitive?.content shouldBe "version"
        exitOf(run.lines[1]) shouldBe 1
        envelope(run.lines[1])["command"]?.jsonPrimitive?.content shouldBe "show"
        // The malformed line rides an exit-6 envelope and never aborts the stream:
        // the health line after it is still answered.
        exitOf(run.lines[2]) shouldBe 6
        envelope(run.lines[2])["command"]?.jsonPrimitive?.content shouldBe "unknown"
        exitOf(run.lines[3]) shouldBe 0
        envelope(run.lines[3])["command"]?.jsonPrimitive?.content shouldBe "health"
        // Only the real read query reached the dispatch; version/health are internal.
        seen.size shouldBe 1
        seen.single().query shouldBe "com.example.Missing"
    }

    @Test
    fun `health carries the daemon shape and counts the stream`() {
        val stdinText = listOf(
            """{"command":"version","query":""}""",
            """{"command":"health","query":""}""",
        ).joinToString("\n")
        val run = run(stdinText)
        run.exit shouldBe 0
        val health = envelope(run.lines[1])
        val result = health["result"]?.jsonObject ?: error("health has no result: ${run.lines[1]}")
        result["rpcVersion"]?.jsonPrimitive?.intOrNull shouldBe 1
        result["indexedArtifacts"]?.jsonPrimitive?.intOrNull shouldBe 0
        // version + health so far: the daemon counts every request before answering.
        result["queryCount"]?.jsonPrimitive?.intOrNull shouldBe 2
    }

    @Test
    fun `an empty batch exits 3 with one envelope`() {
        val run = run("   \n \n")
        run.exit shouldBe 3
        run.lines.size shouldBe 1
        exitOf(run.lines.single()) shouldBe 3
    }

    @Test
    fun `blank lines are framing noise, not requests`() {
        val stdinText = "\n\n" + """{"command":"version","query":""}""" + "\n\n"
        val run = run(stdinText)
        run.exit shouldBe 0
        run.lines.size shouldBe 1
    }

    @Test
    fun `a root failure serialises as every line keeping the 1-1 mapping`() {
        val stdinText = listOf(
            """{"command":"show","query":"A"}""",
            """{"command":"members","query":"B"}""",
        ).joinToString("\n")
        // A malformed --coord fails root resolution before any query runs (exit 3).
        val run = run(stdinText, args = listOf("--no-jdk", "--coord", "not-a-coordinate"))
        run.exit shouldBe 3
        run.lines.size shouldBe 2
        exitOf(run.lines[0]) shouldBe 3
        envelope(run.lines[0])["command"]?.jsonPrimitive?.content shouldBe "show"
        exitOf(run.lines[1]) shouldBe 3
        envelope(run.lines[1])["command"]?.jsonPrimitive?.content shouldBe "members"
    }

    @Test
    fun `--json is accepted and changes nothing`() {
        val stdinText = """{"command":"version","query":""}"""
        val plain = run(stdinText, args = listOf("--no-jdk"))
        val flagged = run(stdinText, args = listOf("--no-jdk", "--json"))
        flagged shouldBe plain
    }

    @Test
    fun `hostile lines never throw and the stream is deterministic`() = runBlocking<Unit> {
        val hostile = listOf(
            "{",
            "{\"command\":\"show\"",
            "{\"command\":\"nope\",\"query\":\"x\"}",
            "{\"jdx\":999,\"command\":\"show\",\"query\":\"x\"}",
            "{\"command\":\"show\",\"query\":42}",
            "{\"command\":\"show\",\"query\":\"x\",\"params\":[]}",
            "{\"command\":\"show\",\"query\":\"x\"} trailing",
            "null",
            "[]",
        )
        checkAll(200, Arb.string(0, 120)) { filler ->
            // One line in, one line out: the filler rides a single line even when
            // it carries newlines of its own (readLines splits them — still total).
            val stdinText = (hostile + filler).joinToString("\n")
            val first = run(stdinText)
            val second = run(stdinText)
            first shouldBe second
            first.lines.forEach { line ->
                // Every output line is a parseable envelope with an exit signal.
                (exitOf(line) in 0..6) shouldBe true
            }
        }
    }

    @Test
    fun `doctor answers in-process with its own envelope`() {
        val stdinText = """{"command":"doctor","query":""}"""
        val run = run(stdinText)
        run.lines.size shouldBe 1
        envelope(run.lines.single())["command"]?.jsonPrimitive?.content shouldBe "doctor"
        // exitOf parses ok:true as 0; a failing machine reads its exit-6 envelope.
        run.exit shouldBe exitOf(run.lines.single())
    }
}
