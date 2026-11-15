package dev.jdx.index.refs

import dev.jdx.index.asm.buildClass
import io.kotest.property.Arb
import io.kotest.property.arbitrary.Codepoint
import io.kotest.property.arbitrary.az
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.objectweb.asm.Opcodes

/**
 * Generative family for [LiteralExtractor] (issue #86): every non-empty
 * string we emit as LDC / ConstantValue is recovered exactly once, and the
 * extractor never throws on the bytes it produced.
 */
class LiteralExtractorPropertyTest {

    private val arbToken: Arb<String> = Arb.string(1..12, Codepoint.az())

    @Test
    fun `round-trips emitted LDC and ConstantValue strings`(): Unit = runBlocking {
        checkAll(50, Arb.list(arbToken, 1..6), Arb.list(arbToken, 0..4)) { fieldValues, ldcValues ->
            val uniqueFields = fieldValues.distinct()
            val uniqueLdcs = ldcValues.filter { it.isNotEmpty() }.distinct()
            val bytes = buildClass("prop/Lit") {
                uniqueFields.forEachIndexed { index, value ->
                    field(
                        Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC or Opcodes.ACC_FINAL,
                        "F$index",
                        "Ljava/lang/String;",
                        null,
                        value,
                    )
                }
                method(Opcodes.ACC_PUBLIC, "run", "()V", null, null) {
                    for (value in uniqueLdcs) ldc(value)
                    // Pop each LDC then return.
                    repeat(uniqueLdcs.size) { code(Opcodes.POP to null) }
                    code(Opcodes.RETURN to null)
                }
            }
            val hits = LiteralExtractor.extract(bytes)
            hits.filter { it.kind == "const" }.map { it.value }.toSet() shouldBe uniqueFields.toSet()
            hits.filter { it.kind == "ldc" }.map { it.value }.toSet() shouldBe uniqueLdcs.toSet()
        }
    }

    @Test
    fun `never throws on random-length junk`(): Unit = runBlocking {
        checkAll(40, Arb.int(0..64), Arb.element(0x00, 0xCA, 0xFE.toByte().toInt() and 0xFF)) { size, fill ->
            val junk = ByteArray(size) { fill.toByte() }
            LiteralExtractor.extract(junk) // must not throw
            true shouldBe true
        }
    }
}
