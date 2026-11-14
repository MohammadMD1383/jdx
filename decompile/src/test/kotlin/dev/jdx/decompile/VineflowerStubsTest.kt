package dev.jdx.decompile

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * Vineflower `$VF:` failure-stub detection (issue #86): marker-only member
 * bodies are engine failures; bodies that still carry real statements are not.
 */
class VineflowerStubsTest {

    @Test
    fun `the classic suspend-lambda stub is a failure`() {
        val stub = """
            |{
            |   // ${'$'}VF: Could not decompile lambda - root function was not found. Is this a suspend lambda?
            |}
        """.trimMargin()
        VineflowerStubs.isFailureStub(stub) shouldBe true
        VineflowerStubs.failureReason(stub) shouldBe "suspend lambda"
    }

    @Test
    fun `a stub with its Java-seam signature line is still a failure`() {
        // `SourceBody.text` includes the declaration; only the brace interior
        // is the reconstructed body we judge.
        val stub = """
            |    public <U> U identity(U value) {
            |       // ${'$'}VF: Could not decompile lambda - root function was not found. Is this a suspend lambda?
            |    }
        """.trimMargin()
        VineflowerStubs.isFailureStub(stub) shouldBe true
        VineflowerStubs.failureReason(stub) shouldBe "suspend lambda"
    }

    @Test
    fun `a block-comment VF stub is a failure`() {
        val stub = "{\n  /* \$VF: Could not decompile method */\n}"
        VineflowerStubs.isFailureStub(stub) shouldBe true
        VineflowerStubs.failureReason(stub) shouldBe "could not decompile method"
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "",
            "{}",
            "{\n}",
            "{\n  return value;\n}",
            "{\n  int x = 1;\n  // \$VF: Could not decompile lambda\n  return x;\n}",
            "// just a comment\nreturn 1;",
            "{\n  // \$VF: note about something else\n}",
        ],
    )
    fun `non-stub bodies are left alone`(text: String) {
        VineflowerStubs.isFailureStub(text) shouldBe false
    }

    @Test
    fun `a plain could-not-decompile stub drops the trailing question`() {
        val stub = "// \$VF: Could not decompile lambda - root missing. Try again?"
        VineflowerStubs.isFailureStub(stub) shouldBe true
        VineflowerStubs.failureReason(stub) shouldBe "could not decompile lambda - root missing"
    }
}
