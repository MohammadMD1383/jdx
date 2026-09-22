package dev.jdx.cli.commands

import com.github.ajalt.clikt.core.parse
import dev.jdx.core.model.Origin
import dev.jdx.core.model.Provenance
import dev.jdx.core.render.MemberKind
import dev.jdx.core.render.SignatureEntry
import dev.jdx.core.render.buildSignatureBlock
import dev.jdx.index.service.JdxService
import dev.jdx.index.workspace.InMemoryWorkspaceStore
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import org.junit.jupiter.api.Test

/**
 * In-process tests for `jdx signature` (T-024).
 *
 * No disk, no jars: the service query and the process exit are both injected, so every
 * path — including the non-zero exits — runs in tier 1. Real-artifact behaviour (the
 * fixture jar, goldens) is tier 2 in `SignatureCommandsServiceTest` and `SignatureGoldenTest`.
 */
class SignatureCommandTest {

    private class TestExit(val code: Int) : RuntimeException()

    private val noExit: (Int) -> Nothing = { throw TestExit(it) }

    private val noDiscovery: ProjectDiscoveryFn = { _, _, _, _ -> null }

    // Hermetic roots (T-066/T-070): an empty in-memory store and an empty environment,
    // so the contributor's ambient `~/.config/jdx/active-workspace` (e.g. `fx`) and
    // `JDX_WORKSPACE` can never re-root these assertions.
    private val noEnv: (String) -> String? = { null }

    private fun signatureBlock() = JdxService.ServiceOutcome.SignatureList(
        buildSignatureBlock(
            query = "com.example.Point#getX",
            declaringType = "com.example.Point",
            entries = listOf(
                SignatureEntry(
                    canonicalRef = "com.example.Point#getX()",
                    kind = MemberKind.METHOD,
                    signature = "public int getX()",
                    declaringType = "com.example.Point",
                ),
            ),
            provenance = listOf(Provenance(artifact = "app.jar", origin = Origin.BYTECODE)),
        ),
    )

    private data class Run(val output: String, val exit: Int, val options: JdxService.SignatureOptions?)

    private fun run(args: List<String>): Run {
        var seen: JdxService.SignatureOptions? = null
        val query: SignatureQuery = { _, _, options ->
            seen = options
            signatureBlock()
        }
        val original = System.out
        val buffer = ByteArrayOutputStream()
        System.setOut(PrintStream(buffer))
        val exit = try {
            SignatureCommand(
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
    fun `signature prints the block text and exits 0`() {
        val run = run(listOf("com.example.Point#getX()"))
        run.exit shouldBe 0
        run.output shouldContain "signatures of com.example.Point#getX"
        run.output shouldContain "public int getX()"
    }

    @Test
    fun `include-synthetic and limit reach the service`() {
        val run = run(listOf("com.example.Point#getX()", "--include-synthetic", "--limit", "10"))
        run.exit shouldBe 0
        run.options shouldBe JdxService.SignatureOptions(includeSynthetic = true, maxSignatures = 10)
    }

    @Test
    fun `view jvm reaches the service, kotlin by default`() {
        val jvm = run(listOf("com.example.Point#getX()", "--view", "jvm"))
        jvm.exit shouldBe 0
        jvm.options?.view shouldBe JdxService.MemberView.JVM

        val kotlin = run(listOf("com.example.Point#getX()"))
        kotlin.options?.view shouldBe JdxService.MemberView.KOTLIN
    }

    @Test
    fun `negative limit is a usage error`() {
        val run = run(listOf("com.example.Point#getX()", "--limit", "-1"))
        run.exit shouldBe 3
        run.output shouldContain "--limit"
    }

    @Test
    fun `json flag renders the envelope`() {
        val run = run(listOf("com.example.Point#getX()", "--json"))
        run.exit shouldBe 0
        run.output shouldContain "\"command\":\"signature\""
        run.output shouldContain "\"ok\":true"
    }
}
