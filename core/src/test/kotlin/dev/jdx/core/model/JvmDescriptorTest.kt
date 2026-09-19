package dev.jdx.core.model

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * Tests for [JvmDescriptor] — field and method descriptors, JVMS §4.3.
 * The acceptance criterion from T-002: descriptor → parsed → printed is a fixed point.
 */
class JvmDescriptorTest {

    @Test
    fun `every non-void primitive round-trips as a field descriptor`() {
        // 'V' is excluded: JVMS §4.3.2 allows void only as a method return type, never
        // as a field type — the parser must reject it there.
        JvmPrimitive.entries.filter { it != JvmPrimitive.VOID }.forEach { primitive ->
            val descriptor = JvmDescriptor.Field(TypeName.PrimitiveType(primitive))
            JvmDescriptor.parse(descriptor.descriptor) shouldBe descriptor
        }
        JvmDescriptor.parse("V").shouldBeNull()
    }

    @Test
    fun `a class type field descriptor round-trips`() {
        val descriptor = JvmDescriptor.Field(typeNameFromBinaryName("java.lang.String"))
        descriptor.descriptor shouldBe "Ljava/lang/String;"
        JvmDescriptor.parse("Ljava/lang/String;") shouldBe descriptor
    }

    @Test
    fun `a nested class descriptor keeps its dollar form`() {
        val builder = typeNameFromBinaryName("com.google.gson.Gson\$GsonBuilder")
        val descriptor = JvmDescriptor.Field(builder)
        descriptor.descriptor shouldBe "Lcom/google/gson/Gson\$GsonBuilder;"
        JvmDescriptor.parse(descriptor.descriptor) shouldBe descriptor
    }

    @Test
    fun `an array field descriptor round-trips`() {
        val descriptor = JvmDescriptor.Field(
            TypeName.ArrayType(typeNameFromBinaryName("java.lang.String"), 2),
        )
        descriptor.descriptor shouldBe "[[Ljava/lang/String;"
        JvmDescriptor.parse("[[Ljava/lang/String;") shouldBe descriptor
    }

    @Test
    fun `a method descriptor with mixed parameters round-trips`() {
        val descriptor = JvmDescriptor.Method(
            parameters = listOf(
                typeNameFromBinaryName("java.lang.String"),
                TypeName.PrimitiveType(JvmPrimitive.INT),
                TypeName.ArrayType(TypeName.PrimitiveType(JvmPrimitive.BOOLEAN), 1),
            ),
            returnType = TypeName.PrimitiveType(JvmPrimitive.VOID),
        )
        descriptor.descriptor shouldBe "(Ljava/lang/String;I[Z)V"
        JvmDescriptor.parse("(Ljava/lang/String;I[Z)V") shouldBe descriptor
    }

    @Test
    fun `a no-argument method descriptor round-trips`() {
        JvmDescriptor.parse("()V") shouldBe
            JvmDescriptor.Method(emptyList(), TypeName.PrimitiveType(JvmPrimitive.VOID))
        JvmDescriptor.parse("()Ljava/lang/Object;") shouldBe
            JvmDescriptor.Method(emptyList(), typeNameFromBinaryName("java.lang.Object"))
    }

    @Test
    fun `a method returning an array round-trips`() {
        val descriptor = JvmDescriptor.Method(
            parameters = emptyList(),
            returnType = TypeName.ArrayType(typeNameFromBinaryName("java.lang.String"), 1),
        )
        descriptor.descriptor shouldBe "()[Ljava/lang/String;"
        JvmDescriptor.parse("()[Ljava/lang/String;") shouldBe descriptor
    }

    @Test
    fun `of builds a field descriptor from a TypeName`() {
        JvmDescriptor.of(typeNameFromBinaryName("java.lang.String")).descriptor shouldBe "Ljava/lang/String;"
        JvmDescriptor.of(TypeName.PrimitiveType(JvmPrimitive.INT)).descriptor shouldBe "I"
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "",                        // empty
            "X",                       // unknown type char
            "Q",                       // not a descriptor character
            "Ljava/lang/String",       // missing semicolon
            "Ljava/lang/String;x",     // trailing garbage
            "(I",                      // unclosed parameter list
            "(I)Vx",                   // trailing garbage after method
            "(Q)V",                    // bad parameter type
            "()Q",                     // bad return type
            "(Ljava/lang/Object;",     // parameter not terminated
            "L1C)1JQLB)Q.;",           // T-055 property find: threw instead of null
            "L\$Entry;",               // empty nesting segment
            "L/;",                     // separator as the whole name
            "(I)",                     // missing return type (T-060: `<=` mutant reads past the end)
        ],
    )
    fun `malformed descriptors parse to null rather than throwing`(text: String) {
        JvmDescriptor.parse(text).shouldBeNull()
    }
}
