package dev.jdx.cli.commands

import com.github.ajalt.clikt.core.parse
import dev.jdx.core.render.buildHierarchyListing
import dev.jdx.index.service.JdxService
import dev.jdx.index.workspace.InMemoryWorkspaceStore
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import org.junit.jupiter.api.Test

/**
 * In-process tests for `jdx hierarchy` and `jdx implementors` (T-032).
 *
 * No disk, no jars: the service query and the process exit are both injected,
 * so every path — including the non-zero exits — runs in tier 1. Real-artifact
 * behaviour (the fixture jar) is tier 2 in `HierarchyCommandsServiceTest`.
 */
class HierarchyCommandTest {

    private class TestExit(val code: Int) : RuntimeException()

    private val noExit: (Int) -> Nothing = { throw TestExit(it) }

    private val noDiscovery: ProjectDiscoveryFn = { _, _, _, _ -> null }

    // Hermetic roots (T-066/T-070): an empty in-memory store and an empty environment,
    // so the contributor's ambient `~/.config/jdx/active-workspace` (e.g. `fx`) and
    // `JDX_WORKSPACE` can never re-root these assertions.
    private val noEnv: (String) -> String? = { null }

    private fun hierarchyOutcome() = JdxService.ServiceOutcome.Hierarchy(
        buildHierarchyListing(
            query = "com.example.Base",
            targetRef = "com.example.Base",
            supertypes = listOf(
                dev.jdx.core.render.SupertypeEntry(
                    binary = "java.lang.Object",
                    relation = "extends",
                    artifact = null,
                    depth = 1,
                ),
            ),
            subtypes = listOf(
                dev.jdx.core.render.SubtypeEntry(
                    binary = "com.example.Child",
                    artifact = "app.jar",
                    via = null,
                ),
            ),
        ),
    )

    private data class Run(val output: String, val exit: Int, val options: JdxService.HierarchyOptions?)

    private fun runHierarchy(args: List<String>): Run {
        var seen: JdxService.HierarchyOptions? = null
        val query: HierarchyQuery = { _, _, options ->
            seen = options
            hierarchyOutcome()
        }
        val original = System.out
        val buffer = ByteArrayOutputStream()
        System.setOut(PrintStream(buffer))
        val exit = try {
            HierarchyCommand(
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

    private fun runImplementors(args: List<String>): Run {
        var seen: JdxService.HierarchyOptions? = null
        val query: HierarchyQuery = { _, _, options ->
            seen = options
            hierarchyOutcome()
        }
        val original = System.out
        val buffer = ByteArrayOutputStream()
        System.setOut(PrintStream(buffer))
        val exit = try {
            ImplementorsCommand(
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
    fun `hierarchy prints the listing text and exits 0`() {
        val run = runHierarchy(listOf("com.example.Base"))
        run.exit shouldBe 0
        run.output shouldContain "hierarchy of 'com.example.Base'"
        run.output shouldContain "extends java.lang.Object"
        run.output shouldContain "class com.example.Child"
    }

    @Test
    fun `direction flags reach the service`() {
        runHierarchy(listOf("com.example.Base")).options shouldBe JdxService.HierarchyOptions()
        runHierarchy(listOf("com.example.Base", "--up")).options shouldBe
            JdxService.HierarchyOptions(up = true, down = false)
        runHierarchy(listOf("com.example.Base", "--down")).options shouldBe
            JdxService.HierarchyOptions(up = false, down = true)
        runHierarchy(listOf("com.example.Base", "--up", "--down")).options shouldBe
            JdxService.HierarchyOptions(up = true, down = true)
    }

    @Test
    fun `filters depth and limit reach the service`() {
        val run = runHierarchy(
            listOf(
                "com.example.Base",
                "--direct",
                "--depth", "3",
                "--in", "app*",
                "--exclude", "jdk*",
                "--limit", "10",
            ),
        )
        run.exit shouldBe 0
        run.options shouldBe JdxService.HierarchyOptions(
            directOnly = true,
            depth = 3,
            inArtifact = "app*",
            exclude = "jdk*",
            limit = 10,
        )
    }

    @Test
    fun `negative limit and zero depth are usage errors`() {
        val limited = runHierarchy(listOf("com.example.Base", "--limit", "-1"))
        limited.exit shouldBe 3
        limited.output shouldContain "--limit"
        val deep = runHierarchy(listOf("com.example.Base", "--depth", "0"))
        deep.exit shouldBe 3
        deep.output shouldContain "--depth"
    }

    @Test
    fun `implementors forces the downward scan`() {
        val run = runImplementors(listOf("com.example.Base", "--limit", "5"))
        run.exit shouldBe 0
        run.options shouldBe JdxService.HierarchyOptions(up = false, down = true, limit = 5)
        run.output shouldContain "hierarchy of 'com.example.Base'"
        run.output shouldContain "class com.example.Child"
    }

    @Test
    fun `json flag renders the envelope`() {
        val run = runHierarchy(listOf("com.example.Base", "--json"))
        run.exit shouldBe 0
        run.output shouldContain "\"command\":\"hierarchy\""
        run.output shouldContain "\"ok\":true"
    }
}
