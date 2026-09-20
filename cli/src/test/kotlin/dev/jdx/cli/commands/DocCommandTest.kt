package dev.jdx.cli.commands

import com.github.ajalt.clikt.core.parse
import dev.jdx.core.model.Origin
import dev.jdx.core.model.Provenance
import dev.jdx.core.render.DocSubject
import dev.jdx.core.render.buildDocBlock
import dev.jdx.index.service.JdxService
import dev.jdx.index.workspace.InMemoryWorkspaceStore
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import org.junit.jupiter.api.Test

/**
 * In-process tests for `jdx doc` (T-025).
 *
 * No disk, no jars: the service query and the process exit are both injected, so every
 * path — including the non-zero exits — runs in tier 1. Real-artifact behaviour (the
 * fixture jar, goldens) is tier 2 in `DocCommandsServiceTest` and `DocGoldenTest`.
 */
class DocCommandTest {

    private class TestExit(val code: Int) : RuntimeException()

    private val noExit: (Int) -> Nothing = { throw TestExit(it) }

    private val noDiscovery: ProjectDiscoveryFn = { _, _, _, _ -> null }

    // Hermetic roots (T-066/T-070): an empty in-memory store and an empty environment,
    // so the contributor's ambient `~/.config/jdx/active-workspace` (e.g. `fx`) and
    // `JDX_WORKSPACE` can never re-root these assertions.
    private val noEnv: (String) -> String? = { null }

    private fun docBlock() = JdxService.ServiceOutcome.Doc(
        buildDocBlock(
            canonicalRef = "com.example.Point",
            declaringType = "com.example.Point",
            subject = DocSubject.TYPE,
            file = "com/example/Point.java",
            startLine = 3,
            endLine = 5,
            rendered = listOf("A point."),
            provenance = listOf(Provenance(artifact = "app-sources.jar", origin = Origin.SOURCES)),
        ),
    )

    private data class Run(val output: String, val exit: Int, val options: JdxService.DocOptions?)

    private fun run(args: List<String>): Run {
        var seen: JdxService.DocOptions? = null
        val query: DocQuery = { _, _, options ->
            seen = options
            docBlock()
        }
        val original = System.out
        val buffer = ByteArrayOutputStream()
        System.setOut(PrintStream(buffer))
        val exit = try {
            DocCommand(
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
    fun `doc prints the block text and exits 0`() {
        val run = run(listOf("com.example.Point"))
        run.exit shouldBe 0
        run.output shouldContain "com.example.Point"
        run.output shouldContain "A point."
    }

    @Test
    fun `inherited is the default and no-inherited reaches the service`() {
        val default = run(listOf("com.example.Point"))
        default.exit shouldBe 0
        default.options shouldBe JdxService.DocOptions(inherit = true, raw = false, maxLines = 200)
        val explicit = run(listOf("com.example.Point", "--inherited"))
        explicit.exit shouldBe 0
        explicit.options shouldBe JdxService.DocOptions(inherit = true, raw = false, maxLines = 200)
        val disabled = run(listOf("com.example.Point", "--no-inherited"))
        disabled.exit shouldBe 0
        disabled.options shouldBe JdxService.DocOptions(inherit = false, raw = false, maxLines = 200)
    }

    @Test
    fun `raw and max-lines reach the service`() {
        val run = run(listOf("com.example.Point", "--raw", "--max-lines", "10"))
        run.exit shouldBe 0
        run.options shouldBe JdxService.DocOptions(inherit = true, raw = true, maxLines = 10)
    }

    @Test
    fun `inherited with no-inherited exits 3`() {
        val run = run(listOf("com.example.Point", "--inherited", "--no-inherited"))
        run.exit shouldBe 3
        run.output shouldContain "mutually exclusive"
    }

    @Test
    fun `negative max-lines exits 3`() {
        val run = run(listOf("com.example.Point", "--max-lines", "-1"))
        run.exit shouldBe 3
        run.output shouldContain "--max-lines must be >= 0"
    }
}
