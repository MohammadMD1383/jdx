package dev.jdx.core.model

import dev.jdx.core.gen.JDX_PROPERTY_ITERATIONS
import dev.jdx.core.gen.arbJvmDescriptor
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.of
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/**
 * Generative invariants for JVM descriptors (T-055, TESTING.md §4): descriptor text is a
 * fixed point — `parse(d).descriptor == d` for every generated descriptor — and malformed
 * input never throws (errors are values, CONTRIBUTING.md).
 */
class JvmDescriptorPropertyTest {

    @Test
    fun `descriptor text is a fixed point`() = runBlocking<Unit> {
        checkAll(JDX_PROPERTY_ITERATIONS, arbJvmDescriptor()) { descriptor ->
            val parsed = JvmDescriptor.parse(descriptor.descriptor)
            parsed.shouldNotBeNull { "generated descriptor '${descriptor.descriptor}' must parse" }
            parsed.descriptor shouldBe descriptor.descriptor
        }
    }

    @Test
    fun `a malformed descriptor never throws`() = runBlocking<Unit> {
        // Any string over the descriptor alphabet parses to a value or null — never throws.
        val alphabet = listOf('L', '(', ')', ';', '[', '/', 'V', 'I', 'J', 'Z', 'B', 'C', 'S', 'F', 'D', 'Q', '.', '$', 'a', '1')
        val arbNastyDescriptor = Arb.list(Arb.of(alphabet), 0..30)
            .map { chars -> chars.joinToString("") }
        checkAll(JDX_PROPERTY_ITERATIONS, arbNastyDescriptor) { text ->
            try {
                JvmDescriptor.parse(text)
            } catch (t: Throwable) {
                throw AssertionError("JvmDescriptor.parse threw on '$text': $t")
            }
        }
    }
}
