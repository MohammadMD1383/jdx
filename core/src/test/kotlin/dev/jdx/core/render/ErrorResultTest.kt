package dev.jdx.core.render

import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Machine-legible failure (T-010, D-015/D-016): not-found exits 1 with
 * did-you-mean refs; ambiguity exits 2 with every candidate as a
 * copy-pasteable canonical ref — in text and in JSON alike.
 */
class ErrorResultTest {

    @Test
    fun `not found text follows the proposal layout`() {
        ErrorResult.notFound(
            query = "com.google.gson.JsonParse",
            suggestions = listOf("com.google.gson.JsonParser", "com.google.gson.JsonParseException"),
        ).renderText() shouldBe """
            not found: com.google.gson.JsonParse
            did you mean:
              com.google.gson.JsonParser
              com.google.gson.JsonParseException
        """.trimIndent()
    }

    @Test
    fun `not found without suggestions is one line`() {
        ErrorResult.notFound(query = "com.example.Nope").renderText() shouldBe
            "not found: com.example.Nope"
    }

    @Test
    fun `ambiguous text lists every candidate with a retry hint`() {
        ErrorResult.ambiguous(
            query = "Gson#toJson",
            candidates = listOf(
                "com.google.gson.Gson#toJson(java.lang.Object)",
                "com.google.gson.Gson#toJson(com.google.gson.JsonElement)",
            ),
        ).renderText() shouldBe """
            ambiguous: 2 candidates for Gson#toJson
              com.google.gson.Gson#toJson(java.lang.Object)
              com.google.gson.Gson#toJson(com.google.gson.JsonElement)
            hint: re-run with one of the refs above
        """.trimIndent()
    }

    @Test
    fun `exit codes follow the public contract`() {
        ErrorResult.notFound(query = "x").exitCode shouldBe 1
        ErrorResult.ambiguous(query = "x", candidates = listOf("a#B", "a#C")).exitCode shouldBe 2
    }

    @Test
    fun `error json carries code message and candidates`() {
        val json = ErrorResult.ambiguous(
            query = "Gson#toJson",
            candidates = listOf("com.google.gson.Gson#toJson(java.lang.Object)"),
        ).toJson(command = "body")
        json shouldContain "\"ok\":false"
        json shouldContain "\"command\":\"body\""
        json shouldContain "\"query\":\"Gson#toJson\""
        json shouldContain "\"code\":2"
        json shouldContain "\"candidates\":[\"com.google.gson.Gson#toJson(java.lang.Object)\"]"
    }

    @Test
    fun `error text has no ANSI and error json has no truncation block`() {
        val result = ErrorResult.notFound(query = "x")
        result.renderText() shouldNotContain "\u001B"
        result.toJson(command = "show") shouldNotContain "truncated"
    }

    @Test
    fun `generic errors carry their exit code in text and json`() {
        val usage = ErrorResult.generic(
            query = "members",
            exitCode = 3,
            message = "usage error: --sort name is not yet implemented (T-062)",
        )
        usage.exitCode shouldBe 3
        usage.renderText() shouldBe "usage error: --sort name is not yet implemented (T-062)"
        val json = usage.toJson(command = "members")
        json shouldContain "\"ok\":false"
        json shouldContain "\"code\":3"
        json shouldContain "not yet implemented"

        val artifact = ErrorResult.generic(
            query = "app.jar",
            exitCode = 5,
            message = "artifact read error: no such artifact: app.jar",
        )
        artifact.exitCode shouldBe 5
        artifact.renderText() shouldBe "artifact read error: no such artifact: app.jar"
        artifact.toJson(command = "show") shouldContain "\"code\":5"
    }

    @Test
    fun `generic errors reject result codes`() {
        io.kotest.assertions.throwables.shouldThrow<IllegalArgumentException> {
            ErrorResult.generic(query = "x", exitCode = 1, message = "not found: x")
        }
        io.kotest.assertions.throwables.shouldThrow<IllegalArgumentException> {
            ErrorResult.generic(query = "x", exitCode = 0, message = "ok")
        }
    }

    // -- T-060: error killers ---------------------------------------------------
    //
    // The `3..6` range edges and the not-found suggestion path were unasserted.

    @Test
    fun `generic errors accept the full 3 to 6 range`() {
        // `exitCode in 3..6` boundary mutants reject 3 or 6.
        ErrorResult.generic(query = "x", exitCode = 3, message = "usage").exitCode shouldBe 3
        ErrorResult.generic(query = "x", exitCode = 6, message = "internal").exitCode shouldBe 6
        io.kotest.assertions.throwables.shouldThrow<IllegalArgumentException> {
            ErrorResult.generic(query = "x", exitCode = 2, message = "ambiguous")
        }
        io.kotest.assertions.throwables.shouldThrow<IllegalArgumentException> {
            ErrorResult.generic(query = "x", exitCode = 7, message = "beyond")
        }
    }

    @Test
    fun `a generic error carries its query and no candidates`() {
        val error = ErrorResult.generic(query = "members", exitCode = 4, message = "workspace error: x")
        error.query shouldBe "members"
        error.candidates shouldBe emptyList()
    }

    @Test
    fun `not found suggestions double as candidates`() {
        val result = ErrorResult.notFound(
            query = "Gson#toJson",
            suggestions = listOf("com.google.gson.Gson#toJson(java.lang.Object)"),
        )
        result.candidates shouldBe listOf("com.google.gson.Gson#toJson(java.lang.Object)")
        result.renderText() shouldContain "did you mean:"
        result.renderText() shouldContain "com.google.gson.Gson#toJson(java.lang.Object)"
        result.toJson(command = "body") shouldContain
            "\"candidates\":[\"com.google.gson.Gson#toJson(java.lang.Object)\"]"
    }
}
