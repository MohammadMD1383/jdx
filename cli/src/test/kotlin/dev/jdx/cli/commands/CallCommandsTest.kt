package dev.jdx.cli.commands

import com.github.ajalt.clikt.core.parse
import dev.jdx.core.render.CallDirection
import dev.jdx.core.render.CallNode
import dev.jdx.core.render.buildCallListing
import dev.jdx.index.service.JdxService
import dev.jdx.index.workspace.InMemoryWorkspaceStore
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import org.junit.jupiter.api.Test

/**
 * In-process tests for `jdx callers` / `jdx calls` (T-033).
 *
 * No disk, no jars: the service query and the process exit are both injected, so every
 * path — including the non-zero exits — runs in tier 1. Real-artifact behaviour (the
 * fixture jar) is tier 2 in `CallCommandsServiceTest`.
 */
class CallCommandsTest {

    private class TestExit(val code: Int) : RuntimeException()

    private val noExit: (Int) -> Nothing = { throw TestExit(it) }

    private val noDiscovery: ProjectDiscoveryFn = { _, _, _, _ -> null }

    // Hermetic roots (T-066/T-070): an empty in-memory store and an empty environment,
    // so the contributor's ambient `~/.config/jdx/active-workspace` (e.g. `fx`) and
    // `JDX_WORKSPACE` can never re-root these assertions.
    private val noEnv: (String) -> String? = { null }

    private fun callOutcome(query: String, direction: CallDirection) = JdxService.ServiceOutcome.CallGraph(
        buildCallListing(
            query = query,
            targetRef = "com.example.Lib#greet",
            direction = direction,
            roots = listOf(
                CallNode(ref = "com.example.App#run()", artifact = "app.jar"),
            ),
        ),
    )

    private data class Run(val output: String, val exit: Int, val options: JdxService.CallOptions?)

    private fun runCallers(args: List<String>): Run {
        var seen: JdxService.CallOptions? = null
        val query: CallGraphQuery = { ref, _, options, _ ->
            seen = options
            callOutcome(ref, CallDirection.CALLERS)
        }
        val original = System.out
        val buffer = ByteArrayOutputStream()
        System.setOut(PrintStream(buffer))
        val exit = try {
            CallersCommand(
                query = query,
                terminate = noExit,
                store = InMemoryWorkspaceStore(),
                getenv = noEnv,
                discover = noDiscovery,
            ).parse(listOf("--jars", "app.jar") + args)
            0
        } catch (e: TestExit) {
            e.code
        } finally {
            System.setOut(original)
        }
        return Run(buffer.toString(Charsets.UTF_8), exit, seen)
    }

    private fun runCalls(args: List<String>): Run {
        var seen: JdxService.CallOptions? = null
        val query: CallGraphQuery = { ref, _, options, _ ->
            seen = options
            callOutcome(ref, CallDirection.CALLS)
        }
        val original = System.out
        val buffer = ByteArrayOutputStream()
        System.setOut(PrintStream(buffer))
        val exit = try {
            CallsCommand(
                query = query,
                terminate = noExit,
                store = InMemoryWorkspaceStore(),
                getenv = noEnv,
                discover = noDiscovery,
            ).parse(listOf("--jars", "app.jar") + args)
            0
        } catch (e: TestExit) {
            e.code
        } finally {
            System.setOut(original)
        }
        return Run(buffer.toString(Charsets.UTF_8), exit, seen)
    }

    @Test
    fun `callers prints the tree text and exits 0`() {
        val run = runCallers(listOf("com.example.Lib#greet(java.lang.String)"))
        run.exit shouldBe 0
        run.output shouldContain "callers of 'com.example.Lib#greet(java.lang.String)'"
        run.output shouldContain "call com.example.App#run()"
    }

    @Test
    fun `calls prints the tree text and exits 0`() {
        val run = runCalls(listOf("com.example.App#run()"))
        run.exit shouldBe 0
        run.output shouldContain "calls from 'com.example.App#run()'"
        run.output shouldContain "call com.example.App#run()"
    }

    @Test
    fun `depth limit and artifact filters reach the service`() {
        val run = runCallers(
            listOf(
                "com.example.Lib#greet",
                "--depth", "3",
                "--limit", "10",
                "--in", "app*",
                "--exclude", "jdk*",
            ),
        )
        run.exit shouldBe 0
        run.options?.depth shouldBe 3
        run.options?.limit shouldBe 10
        run.options?.inArtifact shouldBe "app*"
        run.options?.exclude shouldBe "jdk*"
    }

    @Test
    fun `external-only reaches the service for calls only`() {
        val run = runCalls(listOf("com.example.App#run()", "--external-only"))
        run.exit shouldBe 0
        run.options?.externalOnly shouldBe true
        runCallers(listOf("com.example.Lib#greet")).options?.externalOnly shouldBe false
    }

    @Test
    fun `negative limit and zero depth exit 3 without querying`() {
        var queried = false
        val query: CallGraphQuery = { ref, _, _, _ ->
            queried = true
            callOutcome(ref, CallDirection.CALLERS)
        }
        val original = System.out
        System.setOut(PrintStream(ByteArrayOutputStream()))
        try {
            CallersCommand(
                query = query,
                terminate = noExit,
                store = InMemoryWorkspaceStore(),
                getenv = noEnv,
                discover = noDiscovery,
            ).parse(listOf("--jars", "app.jar", "com.example.Lib#greet", "--limit", "-1"))
        } catch (e: TestExit) {
            e.code shouldBe 3
        } finally {
            System.setOut(original)
        }
        queried shouldBe false
        val depthRun = runCalls(listOf("com.example.App#run()", "--depth", "0"))
        depthRun.exit shouldBe 3
        depthRun.output shouldContain "--depth must be >= 1"
    }
}
