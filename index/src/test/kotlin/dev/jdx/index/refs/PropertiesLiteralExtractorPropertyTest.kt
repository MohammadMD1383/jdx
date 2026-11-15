package dev.jdx.index.refs

import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.Codepoint
import io.kotest.property.arbitrary.az
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.pair
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.util.Properties

/**
 * Generative family for [PropertiesLiteralExtractor] (issue #86): every
 * non-empty key we write into a Properties file is recovered exactly once,
 * and the extractor never throws on junk bytes.
 */
class PropertiesLiteralExtractorPropertyTest {

    private val arbToken: Arb<String> = Arb.string(1..10, Codepoint.az())

    @Test
    fun `round-trips emitted properties keys and values`(): Unit = runBlocking {
        checkAll(40, Arb.list(Arb.pair(arbToken, arbToken), 1..8)) { pairs ->
            val unique = LinkedHashMap<String, String>()
            for ((key, value) in pairs) {
                if (key.isEmpty()) continue
                unique.putIfAbsent(key, value)
            }
            if (unique.isEmpty()) return@checkAll
            val props = Properties()
            for ((key, value) in unique) props.setProperty(key, value)
            val bytes = props.storeToBytes()
            val hits = PropertiesLiteralExtractor.extract("gen.properties", bytes)
            hits.map { it.key to it.value }.toMap() shouldBe unique
            hits.map { it.path }.toSet() shouldBe setOf("gen.properties")
        }
    }

    @Test
    fun `never throws on random-length junk`(): Unit = runBlocking {
        checkAll(40, Arb.int(0..64), Arb.element(0x00, 0xCA, 0xFE.toByte().toInt() and 0xFF)) { size, fill ->
            val junk = ByteArray(size) { fill.toByte() }
            PropertiesLiteralExtractor.extract("junk.properties", junk) // must not throw
            PropertiesLiteralExtractor.isPropertiesPath("x".repeat(size.coerceAtLeast(1)) + ".properties")
            true shouldBe true
        }
    }

    private fun Properties.storeToBytes(): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        // Writer form keeps UTF-16→char round-trip free of ISO-8859-1 surprises
        // for the az()-only alphabet the Arb emits.
        this.store(out.writer(StandardCharsets.UTF_8), null)
        return out.toByteArray()
    }
}
