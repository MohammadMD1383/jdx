package dev.jdx.core.model

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Tests for [TypeName] — the vocabulary every other model type is built on.
 * Round-trip expectations follow JVMS §4.2 (binary names) and JVMS §4.3 (descriptors).
 */
class TypeNameTest {

    @Test
    fun `a top-level class exposes package simple binary and dotted names`() {
        val gson = TypeName.ClassType("com.google.gson", listOf("Gson"))
        gson.packageName shouldBe "com.google.gson"
        gson.simpleName shouldBe "Gson"
        gson.binaryName shouldBe "com.google.gson.Gson"
        gson.fqn shouldBe "com.google.gson.Gson"
    }

    @Test
    fun `a nested class binary name uses dollars while the fqn uses dots`() {
        val entry = TypeName.ClassType("java.util", listOf("Map", "Entry"))
        entry.binaryName shouldBe "java.util.Map\$Entry"
        entry.fqn shouldBe "java.util.Map.Entry"
        entry.simpleName shouldBe "Entry"
        entry.nestedNames shouldBe listOf("Map", "Entry")
    }

    @Test
    fun `a default-package class has an empty package`() {
        val foo = typeNameFromBinaryName("Foo")
        foo shouldBe TypeName.ClassType("", listOf("Foo"))
        foo.packageName shouldBe ""
    }

    @Test
    fun `fromBinaryName splits the package from the nesting chain`() {
        typeNameFromBinaryName("java.util.Map\$Entry") shouldBe
            TypeName.ClassType("java.util", listOf("Map", "Entry"))
        typeNameFromBinaryName("com.google.gson.Gson\$GsonBuilder\$Token") shouldBe
            TypeName.ClassType("com.google.gson", listOf("Gson", "GsonBuilder", "Token"))
    }

    @Test
    fun `fromBinaryName recognises primitive keywords`() {
        typeNameFromBinaryName("int") shouldBe TypeName.PrimitiveType(JvmPrimitive.INT)
        typeNameFromBinaryName("void") shouldBe TypeName.PrimitiveType(JvmPrimitive.VOID)
    }

    @Test
    fun `fromBinaryName parses array descriptors`() {
        typeNameFromBinaryName("[Ljava/lang/String;") shouldBe
            TypeName.ArrayType(typeNameFromBinaryName("java.lang.String"), 1)
        typeNameFromBinaryName("[[J") shouldBe
            TypeName.ArrayType(TypeName.PrimitiveType(JvmPrimitive.LONG), 2)
    }

    @Test
    fun `fromBinaryName rejects empty nesting segments`() {
        assertThrows<IllegalArgumentException> { typeNameFromBinaryName("java.util.Map\$") }
        assertThrows<IllegalArgumentException> { typeNameFromBinaryName("") }
    }

    @Test
    fun `arrays expose element dimensions and readable names`() {
        val grid = TypeName.ArrayType(TypeName.PrimitiveType(JvmPrimitive.INT), 2)
        grid.simpleName shouldBe "int[][]"
        grid.fqn shouldBe "int[][]"
        grid.binaryName shouldBe "[[I"
        grid.packageName shouldBe ""
    }

    @Test
    fun `an array of a class type delegates package and simple name to its element`() {
        val strings = TypeName.ArrayType(typeNameFromBinaryName("java.lang.String"), 1)
        strings.binaryName shouldBe "[Ljava/lang/String;"
        strings.simpleName shouldBe "String[]"
        strings.fqn shouldBe "java.lang.String[]"
        strings.packageName shouldBe "java.lang"
    }

    @Test
    fun `every primitive maps to its keyword and descriptor character`() {
        val expected = mapOf(
            JvmPrimitive.VOID to "V",
            JvmPrimitive.BOOLEAN to "Z",
            JvmPrimitive.BYTE to "B",
            JvmPrimitive.CHAR to "C",
            JvmPrimitive.SHORT to "S",
            JvmPrimitive.INT to "I",
            JvmPrimitive.LONG to "J",
            JvmPrimitive.FLOAT to "F",
            JvmPrimitive.DOUBLE to "D",
        )
        JvmPrimitive.entries.forEach { primitive ->
            val type = TypeName.PrimitiveType(primitive)
            type.binaryName shouldBe primitive.keyword
            type.fqn shouldBe primitive.keyword
            type.simpleName shouldBe primitive.keyword
            type.descriptor shouldBe expected.getValue(primitive)
        }
    }

    @Test
    fun `array construction flattens nested dimensions`() {
        // [[J is dimensions=2 over a single element type, not an array of an array.
        arrayTypeName(TypeName.ArrayType(TypeName.PrimitiveType(JvmPrimitive.INT), 1), 1) shouldBe
            TypeName.ArrayType(TypeName.PrimitiveType(JvmPrimitive.INT), 2)
        arrayTypeName(TypeName.PrimitiveType(JvmPrimitive.LONG), 2).binaryName shouldBe "[[J"
    }

    // -- T-060: model killers --------------------------------------------------------

    @Test
    fun `a class type exposes its jvm descriptor`() {
        typeNameFromBinaryName("java.lang.String").descriptor shouldBe "Ljava/lang/String;"
        typeNameFromBinaryName("com.example.Outer\$Inner").descriptor shouldBe
            "Lcom/example/Outer\$Inner;"
    }

    @Test
    fun `a bare array prefix is malformed, not an exception`() {
        // The `[` loop boundary: `<=` reads past the end instead of failing clean.
        io.kotest.assertions.throwables.shouldThrow<IllegalArgumentException> {
            typeNameFromBinaryName("[[")
        }
    }

    @Test
    fun `a class type rejects separator segments`() {
        // The `none { it in ... }` construction guard: a `/` must throw, and a
        // clean construction must pass (the negated mutant throws on clean input).
        io.kotest.assertions.throwables.shouldThrow<IllegalArgumentException> {
            TypeName.ClassType("", listOf("a/b"))
        }
        TypeName.ClassType("com.example", listOf("Point")).binaryName shouldBe "com.example.Point"
    }
}
