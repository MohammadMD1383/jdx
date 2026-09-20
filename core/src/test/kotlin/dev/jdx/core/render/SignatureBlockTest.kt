package dev.jdx.core.render

import dev.jdx.core.model.Origin
import dev.jdx.core.model.Provenance
import dev.jdx.core.model.Warning
import dev.jdx.core.model.WarningCode
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

/**
 * The signature block behind `jdx signature` (T-024): a canonical
 * `Declaring#name` header, one bare signature line per overload, a source
 * line naming the binary artifact, truncation footer, warnings and a `next:`
 * hint — in text and in JSON alike.
 */
class SignatureBlockTest {

    private val entries = listOf(
        SignatureEntry(
            canonicalRef = "com.example.Point#getX()",
            kind = MemberKind.METHOD,
            signature = "public int getX()",
            declaringType = "com.example.Point",
        ),
        SignatureEntry(
            canonicalRef = "com.example.Point#setX(int)",
            kind = MemberKind.METHOD,
            signature = "public void setX(int arg0)",
            declaringType = "com.example.Point",
        ),
    )

    private fun blockOf(
        entries: List<SignatureEntry> = this.entries,
        max: Int = Int.MAX_VALUE,
        warnings: List<Warning> = emptyList(),
    ) = buildSignatureBlock(
        query = "com.example.Point#getX",
        declaringType = "com.example.Point",
        entries = entries,
        provenance = listOf(Provenance(artifact = "app.jar", origin = Origin.BYTECODE)),
        warnings = warnings,
        maxSignatures = max,
    )

    @Test
    fun `header names the query and each overload prints one bare line`() {
        val text = blockOf().renderText()
        text.lines().first() shouldBe "signatures of com.example.Point#getX"
        text shouldContain "  public int getX()"
        text shouldContain "  public void setX(int arg0)"
    }

    @Test
    fun `source line names the binary artifact and the origin`() {
        val text = blockOf().renderText()
        text shouldContain "source: app.jar (bytecode)"
    }

    @Test
    fun `next hint points at show for the declaring type`() {
        val text = blockOf().renderText()
        text.lines().last() shouldBe "next: jdx show com.example.Point"
    }

    @Test
    fun `limit truncates to whole rows with a footer naming the flag`() {
        val text = blockOf(max = 1).renderText()
        text shouldContain "  public int getX()"
        text shouldNotContain "setX"
        text shouldContain "1 of 2 signatures shown (--limit 2 to see more)"
    }

    @Test
    fun `exact fit is not truncation`() {
        val text = blockOf(max = 2).renderText()
        text shouldNotContain "shown"
    }

    @Test
    fun `warnings print after the footer`() {
        val text = blockOf(
            warnings = listOf(
                Warning(WarningCode.DUPLICATE_FQN, "x is provided by a, b", "x"),
            ),
        ).renderText()
        text shouldContain "warning DUPLICATE_FQN: x is provided by a, b"
    }

    @Test
    fun `json carries every text signature and ref`() {
        val block = blockOf()
        val json = block.toJson(command = "signature")
        json shouldContain "\"ok\":true"
        json shouldContain "\"command\":\"signature\""
        for (entry in entries) {
            json shouldContain entry.signature
            json shouldContain entry.canonicalRef
        }
        json shouldContain "com.example.Point"
    }

    @Test
    fun `json truncation mirrors the text footer`() {
        val json = blockOf(max = 1).toJson(command = "signature")
        json shouldContain "\"shown\":1"
        json shouldContain "\"total\":2"
        json shouldContain "--limit 2"
    }

    @Test
    fun `plain text never carries ansi escapes`() {
        (blockOf().renderText().contains("\u001B")) shouldBe false
    }
}
