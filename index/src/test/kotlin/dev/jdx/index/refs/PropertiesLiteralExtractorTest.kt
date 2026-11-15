package dev.jdx.index.refs

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets

/**
 * Tier-1 coverage for [PropertiesLiteralExtractor] (issue #86 properties
 * slice): path predicate, UTF-8 / ISO-8859-1 decode, comments, escapes,
 * empty-key skip, hostile degradation.
 */
class PropertiesLiteralExtractorTest {

    @Test
    fun `isPropertiesPath accepts properties extension case-insensitively`() {
        PropertiesLiteralExtractor.isPropertiesPath("messages/Bundle.properties") shouldBe true
        PropertiesLiteralExtractor.isPropertiesPath("META-INF/versions/11/x.PROPERTIES") shouldBe true
        PropertiesLiteralExtractor.isPropertiesPath("messages/Bundle.props") shouldBe false
        PropertiesLiteralExtractor.isPropertiesPath("messages/") shouldBe false
        PropertiesLiteralExtractor.isPropertiesPath("") shouldBe false
    }

    @Test
    fun `extracts keys and values from UTF-8 properties bundle`() {
        val text = """
            |# comment
            |notification.missing.plugin.update.sources.title=Cannot update plugins
            |other.unused:hello world
            |blank=
            |
            |! another comment
            |escaped=line1\
            |line2
        """.trimMargin()
        val hits = PropertiesLiteralExtractor.extract(
            "messages/Bundle.properties",
            text.toByteArray(StandardCharsets.UTF_8),
        )
        hits.map { Triple(it.path, it.key, it.value) }.shouldContainExactly(
            Triple("messages/Bundle.properties", "blank", ""),
            Triple("messages/Bundle.properties", "escaped", "line1line2"),
            Triple(
                "messages/Bundle.properties",
                "notification.missing.plugin.update.sources.title",
                "Cannot update plugins",
            ),
            Triple("messages/Bundle.properties", "other.unused", "hello world"),
        )
    }

    @Test
    fun `decodes ISO-8859-1 when UTF-8 refuses and expands unicode escapes`() {
        // Classic Properties: high-bit Latin-1 bytes + \u escapes.
        val latin = "greeting=caf\u00e9\nkey=\\u0041BC\n".toByteArray(StandardCharsets.ISO_8859_1)
        val hits = PropertiesLiteralExtractor.extract("i18n.properties", latin)
        hits.map { it.key to it.value }.shouldContainExactly(
            "greeting" to "café",
            "key" to "ABC",
        )
    }

    @Test
    fun `skips non-properties paths and binary payloads too`() {
        PropertiesLiteralExtractor.extract(
            "readme.txt",
            "a=b\n".toByteArray(StandardCharsets.UTF_8),
        ).shouldBeEmpty()
        PropertiesLiteralExtractor.extract(
            "x.properties",
            byteArrayOf(0x00, 0x01, 0x02),
        ).shouldBeEmpty()
        PropertiesLiteralExtractor.extract("x.properties", ByteArray(0)).shouldBeEmpty()
    }

    @Test
    fun `hostile text never throws`() {
        PropertiesLiteralExtractor.extract(
            "x.properties",
            "not a properties file at all {{{".toByteArray(StandardCharsets.UTF_8),
        ) // must not throw
    }
}
