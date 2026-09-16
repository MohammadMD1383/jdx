package dev.jdx.core.render

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Hand-rolled JSON quoting (T-010, D-028): `core` stays dependency-free, so the
 * envelope is built with string code, not kotlinx-serialization. Every byte here
 * must be exactly what a real parser expects — the cli tier-2 suite parses our
 * output with kotlinx.serialization as the outside check.
 */
class JsonEscapeTest {

    @Test
    fun `plain strings pass through with quotes`() {
        JsonEscape.quote("hello") shouldBe "\"hello\""
    }

    @Test
    fun `quotes and backslashes are escaped`() {
        JsonEscape.quote("say \"hi\" \\ bye") shouldBe "\"say \\\"hi\\\" \\\\ bye\""
    }

    @Test
    fun `control characters use short or unicode escapes`() {
        JsonEscape.quote("a\nb\tc\rd") shouldBe "\"a\\nb\\tc\\rd\""
        JsonEscape.quote("e${0x01.toChar()}f") shouldBe "\"e\\u0001f\""
    }

    @Test
    fun `unicode beyond ascii is emitted literally`() {
        // JSON is UTF-8; only controls need escaping. A literal λ keeps goldens readable.
        JsonEscape.quote("λ") shouldBe "\"λ\""
    }

    @Test
    fun `empty string quotes to empty quotes`() {
        JsonEscape.quote("") shouldBe "\"\""
    }
}
