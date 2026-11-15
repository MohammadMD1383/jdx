package dev.jdx.cli.commands

import com.github.ajalt.clikt.core.parse
import dev.jdx.core.model.AnnotationWhere
import dev.jdx.core.render.buildUsageListing
import dev.jdx.index.service.JdxService
import dev.jdx.index.workspace.InMemoryWorkspaceStore
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import org.junit.jupiter.api.Test

/**
 * In-process tests for `jdx annotated` (issue #26 dedicated command).
 *
 * No disk, no jars: the service query and the process exit are both injected.
 */
class AnnotatedCommandTest {

    private class TestExit(val code: Int) : RuntimeException()

    private val noExit: (Int) -> Nothing = { throw TestExit(it) }

    private val noDiscovery: ProjectDiscoveryFn = { _, _, _, _ -> null }

    private val noEnv: (String) -> String? = { null }

    private fun usageOutcome() = JdxService.ServiceOutcome.UsageList(
        buildUsageListing(
            query = "java.lang.Deprecated",
            targetRef = "java.lang.Deprecated",
            hits = listOf(
                dev.jdx.core.render.UsageHit(
                    fromRef = "com.example.Lib#oldApi()",
                    artifact = "app.jar",
                    kind = "annotation",
                    targetRef = "java.lang.Deprecated",
                    annotation = "@java.lang.Deprecated(forRemoval=true)",
                ),
            ),
        ),
    )

    private data class Run(
        val output: String,
        val exit: Int,
        val options: JdxService.AnnotatedOptions?,
        val ref: String?,
    )

    private fun run(args: List<String>): Run {
        var seen: JdxService.AnnotatedOptions? = null
        var seenRef: String? = null
        val query: AnnotatedQueryFn = { ref, _, options ->
            seenRef = ref
            seen = options
            usageOutcome()
        }
        val original = System.out
        val buffer = ByteArrayOutputStream()
        System.setOut(PrintStream(buffer))
        val exit = try {
            AnnotatedCommand(
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
        return Run(buffer.toString(Charsets.UTF_8), exit, seen, seenRef)
    }

    @Test
    fun `annotated prints the listing and exits 0`() {
        val run = run(listOf("forRemoval"))
        run.exit shouldBe 0
        run.ref shouldBe "forRemoval"
        run.output shouldContain "usages of 'java.lang.Deprecated'"
        run.output shouldContain "annotation com.example.Lib#oldApi()"
    }

    @Test
    fun `where limit filters and context reach the service`() {
        val run = run(
            listOf(
                "java.lang.Deprecated",
                "--where", "forRemoval=true",
                "--limit", "10",
                "--in", "app*",
                "--exclude", "jdk*",
                "--context", "3",
            ),
        )
        run.exit shouldBe 0
        run.options shouldBe JdxService.AnnotatedOptions(
            inArtifact = "app*",
            exclude = "jdk*",
            limit = 10,
            contextLines = 3,
            where = listOf(AnnotationWhere("forRemoval", "true")),
        )
    }

    @Test
    fun `annotation-use spelling without at-sign reaches the service`() {
        val run = run(listOf("java.lang.Deprecated(forRemoval=true)"))
        run.exit shouldBe 0
        run.ref shouldBe "java.lang.Deprecated(forRemoval=true)"
    }

    @Test
    fun `malformed where is a usage error`() {
        val run = run(listOf("deprecated", "--where", "noEquals"))
        run.exit shouldBe 3
        run.output shouldContain "key=value"
    }

    @Test
    fun `negative limit is a usage error`() {
        val run = run(listOf("deprecated", "--limit", "-1"))
        run.exit shouldBe 3
        run.output shouldContain "--limit"
    }

    @Test
    fun `json flag renders the annotated envelope`() {
        val run = run(listOf("deprecated", "--json"))
        run.exit shouldBe 0
        run.output shouldContain "\"command\":\"annotated\""
        run.output shouldContain "\"ok\":true"
    }
}
