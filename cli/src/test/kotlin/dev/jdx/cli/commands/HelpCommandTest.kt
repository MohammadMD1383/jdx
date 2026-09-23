package dev.jdx.cli.commands

import com.github.ajalt.clikt.core.parse
import com.github.ajalt.clikt.core.subcommands
import dev.jdx.cli.JdxCli
import dev.jdx.cli.render.HELP_ROWS
import dev.jdx.cli.render.helpResult
import dev.jdx.cli.render.renderHelpText
import dev.jdx.cli.render.renderText
import dev.jdx.cli.render.toJson
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.property.Arb
import io.kotest.property.arbitrary.of
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream

class HelpCommandTest {

    @Test
    fun `human sheet lists every command once`() {
        val text = renderHelpText(agent = false)

        text.lines().first() shouldBe "jdx help"
        for (row in HELP_ROWS) {
            text shouldContain "jdx ${row.command}"
        }
    }

    @Test
    fun `agent sheet is the compact paste-ready block`() {
        val text = renderHelpText(agent = true)

        text.lines().first() shouldContain "agent cheat sheet"
        for (row in HELP_ROWS) {
            text shouldContain "jdx ${row.command} — ${row.summary}"
        }
        // Same rows, different frame: the agent block drops the human header.
        text.lines().first() shouldBe
            "jdx agent cheat sheet (paste into CLAUDE.md / system prompt):"
    }

    @Test
    fun `json envelope carries every row`() {
        val parsed = Json.parseToJsonElement(helpResult(agent = true).toJson()).jsonObject

        parsed["command"]?.jsonPrimitive?.content shouldBe "help"
        parsed["ok"]?.jsonPrimitive?.content shouldBe "true"
        val rows = parsed["result"]?.jsonObject?.get("rows")?.jsonArray
            ?: error("help json has no result.rows")
        rows.size shouldBe HELP_ROWS.size
        rows.map { it.jsonObject["command"]?.jsonPrimitive?.content } shouldBe
            HELP_ROWS.map { it.command }
    }

    @Test
    fun `help --agent after the subcommand prints the agent block`() {
        val output = captureStdout {
            JdxCli().subcommands(HelpCommand()).parse(listOf("help", "--agent"))
        }

        output.trim() shouldBe renderHelpText(agent = true)
    }

    @Test
    fun `help --json prints the envelope`() {
        val output = captureStdout {
            JdxCli().subcommands(HelpCommand()).parse(listOf("help", "--json"))
        }

        val parsed = Json.parseToJsonElement(output.trim()).jsonObject
        parsed["command"]?.jsonPrimitive?.content shouldBe "help"
    }

    @Test
    fun `plain help prints the human sheet`() {
        val output = captureStdout {
            JdxCli().subcommands(HelpCommand()).parse(listOf("help"))
        }

        output.trim() shouldBe renderHelpText(agent = false)
    }

    // -- generating family: determinism + hostile never-throws --

    @Test
    fun `sheets are deterministic over generated flavours`(): Unit = runBlocking {
        checkAll(200, Arb.of(true, false)) { agent ->
            helpResult(agent).renderText() shouldBe renderHelpText(agent)
            helpResult(agent).toJson() shouldBe helpResult(agent).toJson()
        }
    }

    private fun captureStdout(block: () -> Unit): String {
        val original = System.out
        val buffer = ByteArrayOutputStream()
        System.setOut(PrintStream(buffer))
        try {
            block()
        } finally {
            System.setOut(original)
        }
        return buffer.toString(Charsets.UTF_8)
    }
}
