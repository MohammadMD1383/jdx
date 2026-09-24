package dev.jdx.core.model

import dev.jdx.core.gen.JDX_PROPERTY_ITERATIONS
import dev.jdx.core.gen.arbClassSignature
import dev.jdx.core.gen.arbGenericSignature
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.of
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/**
 * Generative invariants for generic signatures (T-055, TESTING.md §4): signature text is
 * a fixed point — `parse(s).signature == s` for every generated class, method or field
 * signature — class signatures additionally round-trip through [GenericSignature.parseClass]
 * (the T-009 class-file entry point), and malformed input never throws.
 */
class GenericSignaturePropertyTest {

    @Test
    fun `signature text is a fixed point`() = runBlocking<Unit> {
        checkAll(JDX_PROPERTY_ITERATIONS, arbGenericSignature()) { signature ->
            val text = signature.signature
            // `requireNotNull`, not `shouldNotBeNull { msg }`: the kotest block
            // overload is `(T) -> Unit`, so the message string is dropped (T-083).
            val parsed = requireNotNull(GenericSignature.parse(text)) {
                "generated signature '$text' must parse"
            }
            parsed.signature shouldBe text
        }
    }

    @Test
    fun `class signatures round-trip through parseClass`() = runBlocking<Unit> {
        checkAll(JDX_PROPERTY_ITERATIONS, arbClassSignature()) { signature ->
            val text = signature.signature
            val parsed = requireNotNull(GenericSignature.parseClass(text)) {
                "generated class signature '$text' must parse as a class"
            }
            parsed.signature shouldBe text
        }
    }

    @Test
    fun `a malformed signature never throws`() = runBlocking<Unit> {
        // Any string over the signature alphabet parses to a value or null — never throws.
        val alphabet = listOf('L', 'T', '(', ')', ';', '[', '/', '<', '>', ':', '.', '*', '+', '-', '^', 'V', 'I', 'a', '1')
        val arbNastySignature = Arb.list(Arb.of(alphabet), 0..40)
            .map { chars -> chars.joinToString("") }
        checkAll(JDX_PROPERTY_ITERATIONS, arbNastySignature) { text ->
            try {
                GenericSignature.parse(text)
                GenericSignature.parseClass(text)
            } catch (t: Throwable) {
                throw AssertionError("GenericSignature.parse threw on '$text': $t")
            }
        }
    }
}
