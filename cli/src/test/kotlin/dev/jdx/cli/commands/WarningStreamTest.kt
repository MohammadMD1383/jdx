package dev.jdx.cli.commands

import com.github.ajalt.clikt.core.parse
import dev.jdx.core.render.WarningLines
import dev.jdx.index.workspace.InMemoryWorkspaceStore
import dev.jdx.testsupport.fixtures.FixtureJars
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Issue #86: text-mode warnings go to stderr (capped); `--json` keeps them only
 * in the envelope. Stdout stays the answer an agent can parse or pipe.
 */
@Tag("tier2")
class WarningStreamTest {

    private class TestExit(val code: Int) : RuntimeException()

    private val noExit: (Int) -> Nothing = { throw TestExit(it) }
    private val noDiscovery: ProjectDiscoveryFn = { _, _, _, _ -> null }
    private val noEnv: (String) -> String? = { null }

    private data class Captured(val out: String, val err: String, val exit: Int)

    private fun runMembers(args: List<String>): Captured {
        val originalOut = System.out
        val originalErr = System.err
        val outBuf = ByteArrayOutputStream()
        val errBuf = ByteArrayOutputStream()
        System.setOut(PrintStream(outBuf))
        System.setErr(PrintStream(errBuf))
        val exit = try {
            MembersCommand(
                terminate = noExit,
                store = InMemoryWorkspaceStore(),
                getenv = noEnv,
                discover = noDiscovery,
            ).parse(args)
            0
        } catch (e: TestExit) {
            e.code
        } finally {
            System.setOut(originalOut)
            System.setErr(originalErr)
        }
        return Captured(outBuf.toString(Charsets.UTF_8), errBuf.toString(Charsets.UTF_8), exit)
    }

    @Test
    fun `text mode puts UNRESOLVED_SUPERTYPE on stderr not stdout`() {
        val jar = FixtureJars.binaryJar()
        val run = runMembers(
            listOf(
                "dev.jdx.fixtures.Generics",
                "--jars", jar.absolutePath,
                "--no-jdk",
            ),
        )
        run.exit shouldBe 0
        run.out shouldContain "members of dev.jdx.fixtures.Generics"
        run.out shouldContain "source:"
        run.out shouldNotContain "warning UNRESOLVED_SUPERTYPE"
        run.err shouldContain "warning UNRESOLVED_SUPERTYPE"
        // Cap constant is the public contract for the soft limit.
        WarningLines.DEFAULT_MAX_PER_CODE shouldBe 5
    }

    @Test
    fun `json mode keeps warnings in the envelope and silent on stderr`() {
        val jar = FixtureJars.binaryJar()
        val run = runMembers(
            listOf(
                "dev.jdx.fixtures.Generics",
                "--jars", jar.absolutePath,
                "--no-jdk",
                "--json",
            ),
        )
        run.exit shouldBe 0
        run.err.trim() shouldBe ""
        run.out shouldNotContain "warning UNRESOLVED_SUPERTYPE"
        val warnings = Json.parseToJsonElement(run.out).jsonObject["warnings"]?.jsonArray
        requireNotNull(warnings)
        warnings.toString() shouldContain "UNRESOLVED_SUPERTYPE"
    }
}
