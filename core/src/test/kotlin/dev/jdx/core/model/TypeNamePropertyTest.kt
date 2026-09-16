package dev.jdx.core.model

import dev.jdx.core.gen.JDX_PROPERTY_ITERATIONS
import dev.jdx.core.gen.arbFieldTypeName
import dev.jdx.core.gen.arbTypeName
import io.kotest.matchers.shouldBe
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/**
 * Generative invariants for type names (T-055, TESTING.md §4): binary names and field
 * descriptors are fixed points — `fromBinaryName(t.binaryName) == t` for every generated
 * type, `parse(t.descriptor).descriptor == t.descriptor` for every non-void one.
 */
class TypeNamePropertyTest {

    @Test
    fun `binary names survive a parse round-trip`() = runBlocking<Unit> {
        checkAll(JDX_PROPERTY_ITERATIONS, arbTypeName()) { type ->
            typeNameFromBinaryName(type.binaryName) shouldBe type
        }
    }

    @Test
    fun `field descriptors survive a parse round-trip`() = runBlocking<Unit> {
        checkAll(JDX_PROPERTY_ITERATIONS, arbFieldTypeName()) { type ->
            val descriptor = JvmDescriptor.of(type)
            JvmDescriptor.parse(descriptor.descriptor) shouldBe descriptor
        }
    }
}
