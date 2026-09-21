package dev.jdx.cli.commands

import com.github.ajalt.clikt.core.parse
import dev.jdx.core.render.SampleHit
import dev.jdx.core.render.buildSampleListing
import dev.jdx.index.service.JdxService
import dev.jdx.index.workspace.InMemoryWorkspaceStore
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import org.junit.jupiter.api.Test

/**
 * In-process tests for `jdx samples` (T-034).
 *
 * No disk, no jars: the service query and the process exit are both injected, so every
 * path — including the non-zero exits — runs in tier 1. Real-artifact behaviour (the
 * fixture jar) is tier 2 in `SamplesCommandsServiceTest`.
 */
class SamplesCommandTest {

    private class TestExit(val code: Int) : RuntimeException()

    private val noExit: (Int) -> Nothing = { throw TestExit(it) }

    private val noDiscovery: ProjectDiscoveryFn = { _, _, _, _ -> null }

    // Hermetic roots (T-066/T-070): an empty in-memory store and an empty environment,
    // so the contributor's ambient `~/.config/jdx/active-workspace` (e.g. `fx`) and
    // `JDX_WORKSPACE` can never re-root these assertions.
    private val noEnv: (String) -> String? = { null }

    private fun sampleOutcome(query: String) = JdxService.ServiceOutcome.SampleList(
        buildSampleListing(
            query = query,
            targetRef = "com.example.Lib#greet",
            hits = listOf(
                SampleHit(
                    fromRef = "com.example.App#run()",
                    artifact = "app.jar",
                    targetRef = "com.example.Lib#greet(java.lang.String)",
                    snippet = null,
                ),
            ),
        ),
    )

    private data class Run(val output: String, val exit: Int, val options: JdxService.SampleOptions?)

    private fun run(args: List<String>): Run {
        var seen: JdxService.SampleOptions? = null
        val query: SamplesQuery = { ref, _, options ->
            seen = options
            sampleOutcome(ref)
        }
        val original = System.out
        val buffer = ByteArrayOutputStream()
        System.setOut(PrintStream(buffer))
        val exit = try {
            SamplesCommand(
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
    fun `samples prints the examples text and exits 0`() {
        val run = run(listOf("com.example.Lib#greet(java.lang.String)"))
        run.exit shouldBe 0
        run.output shouldContain "samples of 'com.example.Lib#greet(java.lang.String)'"
        run.output shouldContain "example com.example.App#run()"
    }

    @Test
    fun `limit default is three and flags reach the service`() {
        val run = run(
            listOf(
                "com.example.Lib#greet",
                "--limit", "10",
                "--in", "app*",
                "--exclude", "jdk*",
                "--prefer-sources",
            ),
        )
        run.exit shouldBe 0
        run.options?.limit shouldBe 10
        run.options?.inArtifact shouldBe "app*"
        run.options?.exclude shouldBe "jdk*"
        run.options?.preferSources shouldBe true
    }

    @Test
    fun `default options carry limit three without prefer-sources`() {
        val run = run(listOf("com.example.Lib#greet"))
        run.exit shouldBe 0
        run.options?.limit shouldBe 3
        run.options?.preferSources shouldBe false
    }

    @Test
    fun `negative limit exits 3 without querying`() {
        var queried = false
        val query: SamplesQuery = { ref, _, _ ->
            queried = true
            sampleOutcome(ref)
        }
        val original = System.out
        System.setOut(PrintStream(ByteArrayOutputStream()))
        try {
            SamplesCommand(
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
    }
}
