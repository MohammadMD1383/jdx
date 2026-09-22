package dev.jdx.cli.commands

import com.github.ajalt.clikt.core.parse
import dev.jdx.core.render.buildUsageListing
import dev.jdx.index.service.JdxService
import dev.jdx.index.workspace.InMemoryWorkspaceStore
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import org.junit.jupiter.api.Test

/**
 * In-process tests for `jdx usages` (T-030).
 *
 * No disk, no jars: the service query and the process exit are both injected, so every
 * path — including the non-zero exits — runs in tier 1. Real-artifact behaviour (the
 * fixture jar) is tier 2 in `UsagesCommandsServiceTest`.
 */
class UsagesCommandTest {

    private class TestExit(val code: Int) : RuntimeException()

    private val noExit: (Int) -> Nothing = { throw TestExit(it) }

    private val noDiscovery: ProjectDiscoveryFn = { _, _, _, _ -> null }

    // Hermetic roots (T-066/T-070): an empty in-memory store and an empty environment,
    // so the contributor's ambient `~/.config/jdx/active-workspace` (e.g. `fx`) and
    // `JDX_WORKSPACE` can never re-root these assertions.
    private val noEnv: (String) -> String? = { null }

    private fun usageOutcome() = JdxService.ServiceOutcome.UsageList(
        buildUsageListing(
            query = "com.example.Lib#greet",
            targetRef = "com.example.Lib#greet",
            hits = listOf(
                dev.jdx.core.render.UsageHit(
                    fromRef = "com.example.App#run()",
                    artifact = "app.jar",
                    kind = "call",
                    targetRef = "com.example.Lib#greet(java.lang.String)",
                ),
            ),
        ),
    )

    private data class Run(val output: String, val exit: Int, val options: JdxService.UsageOptions?)

    private fun run(args: List<String>): Run {
        var seen: JdxService.UsageOptions? = null
        val query: UsagesQuery = { _, _, options ->
            seen = options
            usageOutcome()
        }
        val original = System.out
        val buffer = ByteArrayOutputStream()
        System.setOut(PrintStream(buffer))
        val exit = try {
            UsagesCommand(
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
    fun `usages prints the listing text and exits 0`() {
        val run = run(listOf("com.example.Lib#greet"))
        run.exit shouldBe 0
        run.output shouldContain "usages of 'com.example.Lib#greet'"
        run.output shouldContain "call com.example.App#run()"
    }

    @Test
    fun `kind limit filters and context reach the service`() {
        val run = run(
            listOf(
                "com.example.Lib",
                "--kind", "call",
                "--limit", "10",
                "--in", "app*",
                "--exclude", "jdk*",
                "--context", "3",
            ),
        )
        run.exit shouldBe 0
        run.options shouldBe JdxService.UsageOptions(
            kind = JdxService.UsageKindFilter.CALL,
            inArtifact = "app*",
            exclude = "jdk*",
            limit = 10,
            contextLines = 3,
        )
    }

    @Test
    fun `every kind value reaches the service for it to accept or reject`() {
        // Only impl/override stay deferred (exit 3 naming hierarchy) — the
        // adapter passes every other kind through for the service to answer.
        for (kind in listOf("all", "call", "read", "write", "ref", "impl", "override", "new", "throw", "annotation")) {
            val run = run(listOf("com.example.Lib", "--kind", kind))
            run.exit shouldBe 0
            run.options?.kind shouldBe usageKindOf(kind)
        }
    }

    @Test
    fun `negative limit is a usage error`() {
        val run = run(listOf("com.example.Lib", "--limit", "-1"))
        run.exit shouldBe 3
        run.output shouldContain "--limit"
    }

    @Test
    fun `src flag reaches the roots`() {
        var seenRoots: JdxService.RootsSpec? = null
        val query: UsagesQuery = { _, roots, _ ->
            seenRoots = roots
            usageOutcome()
        }
        val original = System.out
        val buffer = ByteArrayOutputStream()
        System.setOut(PrintStream(buffer))
        try {
            UsagesCommand(
                query = query,
                terminate = noExit,
                store = InMemoryWorkspaceStore(),
                getenv = noEnv,
                discover = noDiscovery,
            ).parse(listOf("--jars", "app.jar", "--src", "src/main/java", "com.example.Lib"))
        } catch (e: TestExit) {
            // exit 0 never throws
        } finally {
            System.setOut(original)
        }
        seenRoots?.srcSpecs shouldBe listOf("src/main/java")
    }

    @Test
    fun `json flag renders the envelope`() {
        val run = run(listOf("com.example.Lib#greet", "--json"))
        run.exit shouldBe 0
        run.output shouldContain "\"command\":\"usages\""
        run.output shouldContain "\"ok\":true"
    }
}
