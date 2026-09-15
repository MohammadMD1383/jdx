package dev.jdx.core.model

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The T-008 reader needs somewhere to put class deprecation, annotation-element defaults
 * and field constants. These pin the new (defaulted, backward-compatible) model slots.
 */
class MemberDefaultsTest {

    private val voidDescriptor =
        JvmDescriptor.Method(emptyList(), TypeName.PrimitiveType(JvmPrimitive.VOID))

    @Test
    fun `class deprecation defaults to false`() {
        val info = ClassInfo(
            name = TypeName.ClassType("", listOf("Foo")),
            kind = TypeKind.CLASS,
        )
        info.deprecated shouldBe false
        info.copy(deprecated = true).deprecated shouldBe true
    }

    @Test
    fun `method annotation default defaults to null`() {
        val method = MethodInfo("value", voidDescriptor, Access.NONE)
        method.annotationDefault shouldBe null
        method.copy(annotationDefault = "{}").annotationDefault shouldBe "{}"
    }

    @Test
    fun `field constant value defaults to null`() {
        val field = FieldInfo("serialVersionUID", TypeName.PrimitiveType(JvmPrimitive.LONG), Access.NONE)
        field.constantValue shouldBe null
        field.copy(constantValue = "1").constantValue shouldBe "1"
    }
}
